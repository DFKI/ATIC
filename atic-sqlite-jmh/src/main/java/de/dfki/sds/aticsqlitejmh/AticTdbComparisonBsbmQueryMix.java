package de.dfki.sds.aticsqlitejmh;

import benchmark.testdriver.TestDriver;
import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.AticFactory;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import de.dfki.sds.aticsqlite.SqliteAticGraph;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.apache.jena.graph.Graph;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.tdb2.TDB2Factory;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.VerboseMode;

/**
 *
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
@Fork(1)
public class AticTdbComparisonBsbmQueryMix {

    //200 = 75_550
    //2000 = 725_305
    @Param({"200", "2000" })
    private int size;
    
    @Param({"ATIC", "TDB2"}) //"TDB2-InMemory"
    private String storeType;

    private DatasetGraph datasetGraph;
    private Graph defaultGraph;

    private Path bsbmFile;

    private InvocationContext adminCtx;

    private TestDriver testDriver;

    private void initATIC() throws IOException {
        Path tempDir = Files.createTempDirectory("benchmark-");

        Dataset dataset = AticFactory.connectDataset(tempDir.toFile());

        //low level atic
        datasetGraph = (SqliteAticDatasetGraph) dataset.asDatasetGraph();

        //set to admin
        User adminUser = datasetGraph.calculateRead(() -> {
            return ((SqliteAticDatasetGraph) datasetGraph).getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });
        adminCtx = new InvocationContext.Builder().fromUser(adminUser).build();

        defaultGraph = datasetGraph.calculateRead(() -> {
            return (SqliteAticGraph) ((SqliteAticDatasetGraph) datasetGraph).getDefaultGraph(adminCtx);
        });

        adminCtx.transferContext(datasetGraph.getContext());
        
        //BSBM 200 has 75_550 triples
        //BSBM 2000 has 725_305 triples
        int expectedTripleCount = 0;
        switch(size) {
            case 200 -> expectedTripleCount = 75_550;
            case 2000 -> expectedTripleCount = 725_305;
        }

        //so all triples are stored in memory and one time processBuffer is called
        SqliteAticGraph.setDefaultBufferAndBatchSize(expectedTripleCount);
    }

    private void initTDB2InMemory() throws IOException {
        Dataset dataset = TDB2Factory.createDataset();
        datasetGraph = dataset.asDatasetGraph();

        defaultGraph = datasetGraph.calculateRead(() -> {
            return datasetGraph.getDefaultGraph();
        });
    }

    private void initTDB2() throws IOException {
        Path tempDir = Files.createTempDirectory("benchmark-");

        Dataset dataset = TDB2Factory.connectDataset(tempDir.toFile().getAbsolutePath());
        datasetGraph = dataset.asDatasetGraph();

        defaultGraph = datasetGraph.calculateRead(() -> {
            return datasetGraph.getDefaultGraph();
        });
    }

    @Setup
    public void init() throws IOException {

        if (storeType.equals("TDB2-InMemory")) {
            initTDB2InMemory();
        } else if (storeType.equals("TDB2")) {
            initTDB2();
        } else if (storeType.equals("ATIC")) {
            initATIC();
        }

        bsbmFile = Downloader.downloadBSBM(size, true);

        //load data
        datasetGraph.executeWrite(() -> {
            try {
                RDFDataMgr.read(defaultGraph, new FileInputStream(bsbmFile.toFile()), Lang.NT);
            } catch (FileNotFoundException ex) {
                throw new RuntimeException(ex);
            }
        });

        testDriver = new TestDriver(new String[]{});
        testDriver.setSeed(42);
        testDriver.init();
    }

    @Benchmark
    public void mixInOneReadTransaction() {
        datasetGraph.executeRead(() -> {
            testDriver.runMix(queryStr -> {

                Query query = QueryFactory.create(queryStr);
                QueryExecution qe = QueryExecutionFactory.create(query, datasetGraph);

                int resultCount = 0;

                if (query.isSelectType()) {
                    ResultSet rs = qe.execSelect();
                    while (rs.hasNext()) {
                        rs.next();
                        resultCount++;
                    }
                    rs.close();
                } else if (query.isAskType()) {
                    boolean b = qe.execAsk();
                    resultCount = b ? 1 : 0;
                } else if (query.isDescribeType()) {
                    Model model = qe.execDescribe();
                    resultCount = (int) model.size();
                } else if (query.isConstructType()) {
                    Model model = qe.execConstruct();
                    resultCount = (int) model.size();
                } else {
                    throw new IllegalStateException(queryStr);
                }

                qe.close();

                return resultCount;
            });
        });
    }

    /*
    
    public void mixInOneReadTransactionTrial() {
        datasetGraph.executeRead(() -> {
            testDriver.runMix(queryStr -> {

                Query query = QueryFactory.create(queryStr);
                QueryExecution qe = QueryExecutionFactory.create(query, datasetGraph);

                int resultCount = 0;

                if (query.isSelectType()) {
                    ResultSet rs = qe.execSelect();

                    while (rs.hasNext()) {
                        rs.next();
                        resultCount++;
                    }
                    rs.close();
                } else if (query.isAskType()) {
                    boolean b = qe.execAsk();
                    resultCount = b ? 1 : 0;
                } else if (query.isDescribeType()) {
                    Model model = qe.execDescribe();
                    resultCount = (int) model.size();
                } else if (query.isConstructType()) {
                    Model model = qe.execConstruct();
                    resultCount = (int) model.size();
                } else {
                    throw new IllegalStateException(queryStr);
                }

                qe.close();

                System.out.println(queryStr);
                System.out.println(resultCount);
                System.out.println();

                return resultCount;
            });
        });
    }

    */
    
    public static void main(String[] args) throws Exception {
        //trialBenchmark();
        runBenchmark();
    }

    /*
    private static void trialBenchmark() throws IOException {
        AticTdbComparisonBsbmQueryMix b = new AticTdbComparisonBsbmQueryMix();
        b.size = 200;
        b.storeType = "ATIC";

        long start = System.nanoTime();
        b.init();
        long end = System.nanoTime();
        double seconds = (end - start) / 1_000_000_000.0;
        System.out.printf(
                "init() took %.3f seconds%n",
                seconds);
        
        SqliteAticDatasetGraph aticDatasetGraph = (SqliteAticDatasetGraph) b.datasetGraph;
        
        String location = aticDatasetGraph.getContext().get(SqliteAticDatasetGraph.ATIC_LOCATION);
        System.out.println(location);
        FileUtils.copyFile(new File(location + "/atic.sqlite"), new File("../atic-server-home/data/bsbm-" + b.size + ".sqlite"));
        
        aticDatasetGraph.enableQueryLogger("../atic-server-home/data/bsbm-logging.sqlite");
        b.mixInOneReadTransactionTrial();
        aticDatasetGraph.disableQueryLogger();
    }
    */
    
    private static void runBenchmark() throws RunnerException {
        //https://jmh.morethan.io/

        Options opt = new OptionsBuilder()
                .verbosity(VerboseMode.NORMAL)
                .include(AticTdbComparisonBsbmQueryMix.class.getSimpleName())
                .resultFormat(ResultFormatType.JSON)
                .result(AticTdbComparisonBsbmQueryMix.class.getSimpleName() + ".json")
                .build();

        new Runner(opt).run();
    }
}
