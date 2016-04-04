package istc.bigdawg.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Before;
import org.junit.Test;

import istc.bigdawg.catalog.CatalogInstance;
import istc.bigdawg.plan.extract.SQLPlanParser;
import istc.bigdawg.plan.operators.Join;
import istc.bigdawg.plan.operators.Operator;
import istc.bigdawg.planner.Planner;
import istc.bigdawg.postgresql.PostgreSQLHandler;
import istc.bigdawg.signature.Signature;
import istc.bigdawg.utils.sqlutil.SQLPrepareQuery;

public class TrialsAndErrors {
	
	private static boolean runExplainer = false;
	private static boolean runBuilder = false;
	private static boolean runRegex = false;
	private static boolean runWalker = false;
	private static boolean runPlanner = false;

	@Before
	public void setUp() throws Exception {
		CatalogInstance.INSTANCE.getCatalog();
		
//		setupQueryExplainer();
//		setupQueryBuilder();
//		setupRegexTester();
//		setupTreeWalker();
		setupPlannerTester();
	}
	
	public void setupQueryExplainer() {
		runExplainer = true;
	}; 
	
	public void setupQueryBuilder() {
		runBuilder = true;
	};
	
	public void setupRegexTester() {
		runRegex = true;
	};
	
	public void setupTreeWalker() {
		runWalker = true;
	};
	
	public void setupPlannerTester() {
		runPlanner = true;
	}

	@Test
	public void testRunExplainer() throws Exception {
		
		if ( !runExplainer ) return;
			
		PostgreSQLHandler psqlh = new PostgreSQLHandler(3);
		System.out.println("Explainer -- Type query or \"quit\" to exit: ");
		Scanner scanner = new Scanner(System.in);
		String query = scanner.nextLine();
		while (!query.toLowerCase().equals("quit")) {
			
			String explainQuery = SQLPrepareQuery.generateExplainQueryString(query);
			System.out.println(psqlh.generatePostgreSQLQueryXML(explainQuery) + "\n");
			query = scanner.nextLine();
			
		}
		scanner.close();
	}

	@Test
	public void testRunBuilder() throws Exception {
		
		if ( !runBuilder ) return;
			
		PostgreSQLHandler psqlh = new PostgreSQLHandler(3);
		System.out.println("Builder -- Type query or \"quit\" to exit: ");
		Scanner scanner = new Scanner(System.in);
//		String query = scanner.nextLine();
//		String query = "select l_returnflag, l_linestatus, sum(l_quantity) as sum_qty, sum(l_extendedprice) as sum_base_price, sum(l_extendedprice * (1 - l_discount)) as sum_disc_price, sum(l_extendedprice * (1 - l_discount) * (1 + l_tax)) as sum_charge, avg(l_quantity) as avg_qty, avg(l_extendedprice) as avg_price, avg(l_discount) as avg_disc, count(*) as count_order from lineitem where l_shipdate <= date '1998-12-01' - interval '1' day group by l_returnflag, l_linestatus order by l_returnflag, l_linestatus;";
		
		String query = "select s_acctbal, s_name, n_name, p_partkey, p_mfgr, s_address, s_phone, s_comment from part, supplier, partsupp, nation, region, ( select ps_partkey, min(ps_supplycost) as minsuppcost from partsupp, supplier, nation, region where s_suppkey = ps_suppkey and s_nationkey = n_nationkey and n_regionkey = r_regionkey and r_name = 'AMERICA' group by  ps_partkey ) as temp where p_partkey = partsupp.ps_partkey and s_suppkey = ps_suppkey and p_size = 14 and p_type like '%BRASS' and s_nationkey = n_nationkey and n_regionkey = r_regionkey and r_name = 'AMERICA' and p_partkey = temp.ps_partkey and ps_supplycost = minsuppcost order by s_acctbal desc, n_name, s_name, p_partkey;";
		while (!query.toLowerCase().equals("quit")) {
			
			SQLQueryPlan queryPlan = SQLPlanParser.extractDirect(psqlh, query);
			
			Operator root = queryPlan.getRootNode();
			System.out.println(root.generateSQLString(null) + "\n");
			
			System.out.println(root.getTreeRepresentation(true) + "\n");
			
			Signature.printO2EMapping(root);
			
			System.out.println();
			
			Signature.printStrippedO2EMapping(root);
			
//			System.out.println(RTED.computeDistance(root.getTreeRepresentation(true), "{}"));
			
			break;
//			query = scanner.nextLine();
			
		}
		scanner.close();
		
	}
	
	@Test
	public void testRegex() {
		
		if ( !runRegex ) return;
		
		String s = "where l_shipdate <= dAte '1998-12-01' - interval '1' day group by";
		
		StringBuilder sb = new StringBuilder();
		sb.append(s);

		Pattern pDayInterval = Pattern.compile("(?i)(interval '[0-9]+\\s?((hour)|(hours)|(day)|(days)|(month)|(months))?'(\\s((hour)|(hours)|(day)|(days)|(month)|(months)))?)");
		Matcher m3 = pDayInterval.matcher(sb);
		
		if (m3.find()) {
			System.out.println("--> INTERVAL: "+sb.substring(m3.start(), m3.end()));
		}
		
		
		s = s.replaceAll("::\\w+( \\w+)*", ""); 
		Pattern p = Pattern.compile("'[0-9]+'");
		Matcher m = p.matcher(s);
		while (m.find()) {
			s = m.replaceFirst(s.substring(m.start()+1, m.end()-1));
			m.reset(s);
		}
		
		System.out.println(s);
	}
	
	@Test
	public void testWalker() throws Exception {
		
		if ( !runWalker ) return;

		String query = "SELECT supplier.s_acctbal, supplier.s_name, nation.n_name, part.p_partkey, part.p_mfgr, supplier.s_address, supplier.s_phone, supplier.s_comment FROM (SELECT partsupp_1.ps_partkey, min(partsupp_1.ps_supplycost) AS minsuppcost FROM nation AS nation_1, region AS region_1, supplier AS supplier_1, partsupp AS partsupp_1 WHERE (supplier_1.s_nationkey = nation_1.n_nationkey) AND (nation_1.n_regionkey = region_1.r_regionkey) AND (region_1.r_name = 'AMERICA') AND (partsupp_1.ps_suppkey = supplier_1.s_suppkey) GROUP BY partsupp_1.ps_partkey) AS BIGDAWGAGGREGATE_1, partsupp, part, supplier, nation, region WHERE ((BIGDAWGAGGREGATE_1.minsuppcost) = partsupp.ps_supplycost) AND (partsupp.ps_partkey = BIGDAWGAGGREGATE_1.ps_partkey) AND ((part.p_type LIKE '%BRASS') AND (part.p_size = 14)) AND (part.p_partkey = partsupp.ps_partkey) AND (part.p_partkey = partsupp.ps_partkey) AND (supplier.s_suppkey = partsupp.ps_suppkey) AND (nation.n_nationkey = supplier.s_nationkey) AND (region.r_name = 'AMERICA') AND (region.r_regionkey = nation.n_regionkey) AND (region.r_regionkey = nation.n_regionkey) ORDER BY supplier.s_acctbal DESC, nation.n_name, supplier.s_name, part.p_partkey;";

		PostgreSQLHandler psqlh = new PostgreSQLHandler(3);
		SQLQueryPlan queryPlan = SQLPlanParser.extractDirect(psqlh, query);
		
		Operator root = queryPlan.getRootNode();
		System.out.println(root.generateSQLString(null) + "\n");
		
		List<Operator> walker = new ArrayList<>();
		walker.add(root);
		while (!walker.isEmpty()) {
			List<Operator> nextgen = new ArrayList<>();
			
			for (Operator o: walker) {
				
				if (o instanceof Join) {
					System.out.println("Join encounter: ");
					System.out.println("Printing: "+((Join)o).getJoinPredicateObjectsForBinaryExecutionNode());
					System.out.println();

				}
				
				
				nextgen.addAll(o.getChildren());
			}
			
			walker = nextgen;
		}
		
	}
	
	@Test
	public void testPlanner() throws Exception {
		if ( !runPlanner ) return;
		
		String userinput = "bdrel(select bucket, count(*) from ( select width_bucket(valuenum, 0.5, 1000, 1000) as bucket from mimic2v26.labevents le,  mimic2v26.d_patients dp  where itemid in (50006,50112) and valuenum is not null and le.subject_id = dp.subject_id and ((DATE_PART('year',le.charttime) - DATE_PART('year',dp.dob))*12 + DATE_PART('month',le.charttime) - DATE_PART('month',dp.dob)) > 15  ) as glucose group by bucket order by bucket);";
		try {
		Planner.processQuery(userinput, false);
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
	}
	
	public void printIndentation(int recLevel) {
		String token = "--";
		for (int i = 0; i < recLevel; i++)
			System.out.print(token);
		System.out.print(' ');
	}
	
}
