package istc.bigdawg.executor;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import istc.bigdawg.executor.plan.BinaryJoinExecutionNode;
import istc.bigdawg.migration.MigrationResult;
import org.apache.commons.lang3.tuple.ImmutablePair;
import com.jcabi.log.Logger;

import istc.bigdawg.exceptions.MigrationException;
import istc.bigdawg.executor.plan.ExecutionNode;
import istc.bigdawg.executor.plan.QueryExecutionPlan;
import istc.bigdawg.migration.Migrator;
import istc.bigdawg.monitoring.Monitor;
import istc.bigdawg.postgresql.PostgreSQLHandler;
import istc.bigdawg.postgresql.PostgreSQLHandler.QueryResult;
import istc.bigdawg.query.ConnectionInfo;

/**
 * TODO:
 *   fully abstracted DbHandlers instead of casting to PostgreSQL
 *   shuffle joins
 *   better exception/error handling in the event of failure
 *   look into implications of too many events in the ForkJoinPool
 * 
 * @author ankush
 */
class PlanExecutor {
    static final Monitor monitor = new Monitor();
    static final ExecutorService threadPool = java.util.concurrent.Executors.newCachedThreadPool();

    private final Multimap<ExecutionNode, ConnectionInfo> resultLocations = Multimaps.synchronizedSetMultimap(HashMultimap.create());
    private final Multimap<ConnectionInfo, String> temporaryTables = Multimaps.synchronizedSetMultimap(HashMultimap.create());

    private final Map<ImmutablePair<String, ConnectionInfo>, CompletableFuture<MigrationResult>> migrations = new ConcurrentHashMap<>();

    private final Map<ExecutionNode, CountDownLatch> locks = new ConcurrentHashMap<>();

    private final QueryExecutionPlan plan;

    /**
     * Class responsible for handling the execution of a single QueryExecutionPlan
     *
     * @param plan
     *            a data structure of the queries to be run and their ordering,
     *            with edges pointing to dependencies
     */
    public PlanExecutor(QueryExecutionPlan plan) {
        this.plan = plan;

        StringBuilder sb = new StringBuilder();
        for(ExecutionNode n : plan) {
            sb.append(String.format("%s -> (%s)\n", n, plan.getDependents(n)));
        }

        Logger.info(this, "Received plan %s", plan.getSerializedName());

        Logger.debug(this, "Nodes for plan %s: \n %s", plan.getSerializedName(), sb);
        Logger.debug(this, "Ordered queries: \n %s",
                StreamSupport.stream(plan.spliterator(), false)
                    .map(ExecutionNode::getQueryString)
                    .filter(Optional::isPresent).map(opt -> opt.get())
                    .collect(Collectors.joining(" \n ---- then ---- \n ")));

        // initialize countdown latches to the proper counts
        for(ExecutionNode node : plan) {
            int latchSize = plan.inDegreeOf(node);
            Logger.debug(this, "Node %s lock initialized with %s dependencies", node, latchSize);
            this.locks.put(node, new CountDownLatch(latchSize));
        }
    }

    /**
     * Execute the plan, and return the result
     */
    public Optional<QueryResult> executePlan() throws SQLException, MigrationException {
        long start = System.currentTimeMillis();

        Logger.info(this, "Executing query plan %s...", plan.getSerializedName());

        CompletableFuture<Optional<QueryResult>> finalResult = CompletableFuture.completedFuture(Optional.empty());
        for (ExecutionNode node : plan) {
            Logger.debug(this, "Creating Future for query node %s...", node);

            CompletableFuture<Optional<QueryResult>> result = CompletableFuture.supplyAsync(() -> this.executeNode(node), threadPool);

            if (plan.getTerminalTableNode().equals(node)) {
                finalResult = result;
            }
        }

        // Block until finalResult has resolved
        Optional<QueryResult> result = Optional.empty();
        try {
            result = finalResult.get();
        } catch (InterruptedException e) {
            Logger.error(this, "Execution of query plan %s was interrupted: %[exception]s", plan.getSerializedName(), e);
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            Logger.error(this, "Error retrieving results of final query node %s: %[exception]s", plan.getSerializedName(), e);
        }

        dropTemporaryTables();

        long end = System.currentTimeMillis();

        Logger.info(this, "Finished executing query plan %s, in %[ms]d.", plan.getSerializedName(), (start - end));
        Logger.info(this, "Sending timing to monitor...");
        monitor.finishedBenchmark(plan, start, end);

        Logger.info(this, "Returning result to planner...");
        return result;
    }
    
    private Optional<QueryResult> executeNode(ExecutionNode node) {
        // perform shuffle join if equijoin and hint doesn't specify otherwise
        // TODO(ankush): re-enable this and debug
//        if (node instanceof BinaryJoinExecutionNode &&
//                ((BinaryJoinExecutionNode) node).getHint().orElse(BinaryJoinExecutionNode.JoinAlgorithms.SHUFFLE) == BinaryJoinExecutionNode.JoinAlgorithms.SHUFFLE &&
//                ((BinaryJoinExecutionNode) node).isEquiJoin()) {
//            BinaryJoinExecutionNode joinNode = (BinaryJoinExecutionNode) node;
//            if(!joinNode.getHint().isPresent() || joinNode.getHint().get() == BinaryJoinExecutionNode.JoinAlgorithms.SHUFFLE) {
//                try {
//                    colocateDependencies(node, Arrays.asList(joinNode.getLeft().table, joinNode.getRight().table));
//
//                    Optional<QueryResult> result = new ShuffleJoinExecutor(joinNode).execute();
//                    markNodeAsCompleted(node);
//                    return result;
//                } catch (Exception e) {
//                    log.error(String.format("Error executing node %s", joinNode), e);
//                    return Optional.empty();
//                }
//            }
//        }


        // otherwise execute as local query execution (same as broadcast join)
        // colocate dependencies, blocking until completed
        colocateDependencies(node, Collections.emptySet());
        Logger.debug(this, "Executing query node %s...", node);

        return node.getQueryString().flatMap((query) -> {
            try {
                Optional<QueryResult> result = ((PostgreSQLHandler) node.getEngine().getHandler()).executePostgreSQL(query);
                return result;
            } catch (SQLException e) {
                Logger.error(this, "Error executing node %s: %[exception]s", node, e);
                // TODO: if error is actually bad, don't markNodeAsCompleted, and instead fail the QEP gracefully.
                return Optional.empty();
            } finally {
                markNodeAsCompleted(node);
            }
        });
    }

    private void markNodeAsCompleted(ExecutionNode node) {
        Logger.debug(this, "Completed execution of %s.", node);

        if (!plan.getTerminalTableNode().equals(node)) {
            // clean up the intermediate table later
            node.getTableName().ifPresent((table) -> temporaryTables.put(node.getEngine(), table));

            // update nodeLocations to reflect that the results are located on this node's engine
            resultLocations.put(node, node.getEngine());

            final Collection<ExecutionNode> dependants = plan.getDependents(node);
            Logger.debug(this, "Examining dependants %[list]s of %s", dependants, node);

            for (ExecutionNode dependent : dependants) {
                Logger.debug(this, "Decrementing lock of %s because $s completed.", dependent, node);
                this.locks.get(dependent).countDown();
                Logger.debug(this, "%s is now waiting on %d dependencies.", dependent, this.locks.get(dependent).getCount());
            }

            Logger.debug(this, "Completed examination of dependants %[list]s of %s", dependants, node);
        }
    }


    /**
     * Colocates the dependencies for the given ExecutionNode onto that node's engine.
     *
     * Waits for any incomplete dependencies, and blocks the current thread until completion.
     *
     * @param node
     */
    private void colocateDependencies(ExecutionNode node, Collection<String> ignoreTables) {
        // Block until dependencies are all resolved
        try {
            Logger.debug(this, "Waiting for %d dependencies of query node %s to be resolved...", this.locks.get(node).getCount(), node);
            while(!this.locks.get(node).await(10, TimeUnit.SECONDS)) {
                Logger.debug(this, "Still waiting for %d dependencies of query node %s to be resolved...", this.locks.get(node).getCount(), node);
            }
        } catch (InterruptedException e) {
            Logger.error(this, "Execution of query node %s was interrupted while waiting for dependencies: %[exception]s", node, e);
            Thread.currentThread().interrupt();
        }

        Logger.debug(this, "Colocating dependencies of %s to %s", node, node.getEngine());

        ignoreTables.addAll(plan.getDependencies(node).stream().filter(d -> resultLocations.containsEntry(d, node.getEngine())).map(n -> n.getTableName().orElse("NO_TABLE")).collect(Collectors.toSet()));
        Logger.debug(this, "Ignoring dependencies %[list]s of %s", ignoreTables, node);

//        java.util.stream.Stream<ExecutionNode> deps = plan.getDependencies(node).stream()
//                .filter(d -> d.getTableName().isPresent() && !ignoreTables.contains(d.getTableName().get()));
//
//        Logger.debug(this, "Examining dependencies %[list]s of %s", deps.collect(Collectors.toSet()), node);
//        CompletableFuture[] futures = deps
//                .map((d) -> {
//                    // computeIfAbsent gets a previous migration's Future, or creates one if it doesn't already exist
//                    ImmutablePair<String, ConnectionInfo> migrationKey = new ImmutablePair<>(d.getTableName().get(), node.getEngine());
//                    Logger.debug(PlanExecutor.this, "Examining %s to see if migration is necessary...", d);
//
//                    return migrations.computeIfAbsent(migrationKey, (k) -> {
//                        return CompletableFuture.supplyAsync(() -> {
//                            Logger.debug(PlanExecutor.this, "Started migrating dependency %s of node %s: %s", d, node);
//                            MigrationResult result = colocateSingleDependency(d, node);
//                            Logger.debug(PlanExecutor.this, "Finished migrating dependency %s of node %s: %s", d, node, result);
//                            return result;
//                        }, threadPool);
//                    });
//                }).toArray(CompletableFuture[]::new);

        Collection<ExecutionNode> deps = plan.getDependencies(node).stream()
                .filter(d -> d.getTableName().isPresent() && !ignoreTables.contains(d.getTableName().get()))
                .collect(Collectors.toSet());

        Logger.debug(this, "Examining dependencies %[list]s of %s", deps, node);

        Collection<CompletableFuture<MigrationResult>> futureCollection = new HashSet<>();
        for(ExecutionNode d : deps) {
            ImmutablePair<String, ConnectionInfo> migrationKey = new ImmutablePair<>(d.getTableName().get(), node.getEngine());
            Logger.debug(PlanExecutor.this, "Examining %s to see if migration is necessary...", d);

            synchronized (migrations) {
                if (!migrations.containsKey(migrationKey)) {
                    CompletableFuture<MigrationResult> migration = CompletableFuture.supplyAsync(() -> {
                        Logger.debug(PlanExecutor.this, "Started migrating dependency %s of node %s: %s", d, node);
                        MigrationResult result = colocateSingleDependency(d, node);
                        Logger.debug(PlanExecutor.this, "Finished migrating dependency %s of node %s: %s", d, node, result);
                        return result;
                    }, threadPool);

                    migrations.put(migrationKey, migration);

                    futureCollection.add(migration);
                } else {
                    Logger.debug(PlanExecutor.this, "Already migrating %s, not queueing again.", d);
                }
            }
        }

        CompletableFuture[] futures = futureCollection.toArray(new CompletableFuture[futureCollection.size()]);

        Logger.debug(this, "Waiting on %d dependencies of %s to be migrated...", futures.length, node);
        CompletableFuture.allOf(futures).join();
        Logger.debug(this, "All dependencies of %s to be migrated.", node);

    }

    private MigrationResult colocateSingleDependency(ExecutionNode dependency, ExecutionNode dependant) {
        return dependency.getTableName().map((table) -> {
            try {
                MigrationResult result = Migrator.migrate(dependency.getEngine(), table, dependant.getEngine(), table);

                if(result.isError()) {
                    throw new MigrationException(result.toString());
                }

                Logger.debug(PlanExecutor.this, "Marking dependency %s as migrated on engine %s...", dependency, dependant.getEngine());

                // mark the dependency's data as being present on node.getEngine()
                resultLocations.put(dependency, dependant.getEngine());

                // mark that this engine now has a copy of the dependency's data
                temporaryTables.put(dependant.getEngine(), table);

                return result;
            } catch (MigrationException e) {
                Logger.error(PlanExecutor.this, "Error migrating dependency %s of node %s: %[exception]s", dependency.getTableName(), dependant.getTableName(), e);
                return MigrationResult.getFailedInstance(e.getLocalizedMessage());
            }
        }).orElse(MigrationResult.getEmptyInstance(String.format("No table to migrate for node %s", dependency.getTableName())));
    }

    private void dropTemporaryTables() throws SQLException {
        synchronized(temporaryTables) {
            Multimap<ConnectionInfo, String> removed = HashMultimap.create();

            for (ConnectionInfo c : temporaryTables.keySet()) {
                Collection<String> tables = temporaryTables.get(c);

                Logger.debug(this, "Cleaning up %s by removing %s...", c, tables);
                ((PostgreSQLHandler) c.getHandler()).executeStatementPostgreSQL(c.getCleanupQuery(tables));

                removed.putAll(c, tables);
            }

            for (Map.Entry<ConnectionInfo, String> entry : removed.entries()) {
                temporaryTables.remove(entry.getKey(), entry.getValue());
            }
        }

        Logger.debug(this, "Temporary tables for query plan %s have been cleaned up", plan.getSerializedName());
    }


    /**
     * Colocates the dependencies for the given ExecutionNode onto that node's engine one at a time.
     *
     * Waits for any incomplete dependencies, and blocks the current thread until completion.
     *
     * @param node
     */
    @Deprecated
    private void colocateDependenciesSerially(ExecutionNode node) {
        // Block until dependencies are all resolved
        try {
            Logger.debug(this, String.format("Waiting for dependencies of query node %s to be resolved", node.getTableName()));
            this.locks.get(node).await();
        } catch (InterruptedException e) {
            Logger.error(this, "Execution of query node %s was interrupted while waiting for dependencies: %[exception]s", node.getTableName(), e);
            Thread.currentThread().interrupt();
        }

        Logger.debug(this, String.format("Colocating dependencies of query node %s", node.getTableName()));

        plan.getDependencies(node).stream()
                // only look at dependencies not already on desired engine
                .filter(d -> !resultLocations.containsEntry(d, node.getEngine()))
                .forEach(d -> {
                    // migrate to node.getEngine()
                    d.getTableName().ifPresent((table) -> {
                        Logger.debug(this, String.format("Migrating dependency table %s from engine %s to engine %s...", table, d.getEngine(),
                                node.getEngine()));
                        try {
                            MigrationResult result = Migrator.migrate(d.getEngine(), table, node.getEngine(), table);

                            if(result.isError()) {
                                throw new MigrationException(result.toString());
                            }

                            // mark the dependency's data as being present on node.getEngine()
                            resultLocations.put(d, node.getEngine());

                            // mark that this engine now has a copy of the dependency's data
                            temporaryTables.put(node.getEngine(), table);
                        } catch (MigrationException e) {
                            Logger.error(this, "Error migrating dependency %s of node %s: %[exception]s", d, node, e);
                        }
                    });
                });
    }
}
