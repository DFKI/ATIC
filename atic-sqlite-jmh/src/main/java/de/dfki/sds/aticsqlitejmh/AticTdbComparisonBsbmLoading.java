package de.dfki.sds.aticsqlitejmh;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.Database;
import de.dfki.sds.aticsqlite.DatabaseLongLivedConnection;
import de.dfki.sds.aticsqlite.DatabaseOptions;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import de.dfki.sds.aticsqlite.SqliteAticGraph;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
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

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
@Fork(1)
public class AticTdbComparisonBsbmLoading {

    @Param({"200", "2000" })
    private int size;
    
    @Param({"ATIC", "TDB2"})
    private String storeType;

    private Dataset ds;
    
    private File bsbmFile;
    
    /*
    private Statement stmt = ResourceFactory.createStatement(
                            ResourceFactory.createResource("http://example.org/s"),
                            ResourceFactory.createProperty("http://example.org/p"),
                            ResourceFactory.createResource("http://example.org/o")
                    );
    */
    
    @Setup
    public void init() throws IOException {

        Path tempDir = Files.createTempDirectory("benchmark-");

        if ("ATIC".equals(storeType)) {

            Path dbFile = tempDir.resolve("benchmark.sqlite");
            
            DatabaseOptions options
                = new DatabaseOptions.Builder(dbFile.toString())
                        .build();

            Database db = new DatabaseLongLivedConnection(options);
            SqliteAticDatasetGraph aticDatasetGraph = new SqliteAticDatasetGraph(db);

            ds = DatasetFactory.wrap(aticDatasetGraph);

            //set to admin
            User adminUser = aticDatasetGraph.calculateRead(() -> {
                return aticDatasetGraph.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
            });
            InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();
            ctx.transferContext(ds.getContext());
            
            //BSBM 200 has 75_550 triples
            //BSBM 2000 has 725_305 triples
            int expectedTripleCount = 0;
            switch(size) {
                case 200 -> expectedTripleCount = 75_550;
                case 2000 -> expectedTripleCount = 725_305;
            }
            
            //so all triples are stored in memory and one time processBuffer is called
            SqliteAticGraph.setDefaultBufferAndBatchSize(expectedTripleCount);

        } else if("TDB2".equals(storeType)) {
            ds = TDB2Factory.connectDataset(tempDir.toString());
        }

        bsbmFile = Downloader.downloadBSBM(size, true).toFile();
    }

    /*
    @Benchmark
    public void sizeOfDefaultGraph() {
        ds.executeRead(() -> {
            long size = ds.getDefaultModel().size();
            System.out.println(size);
        });
    }
    */
    
    /*
    @Benchmark
    public void addSPOToDefaultGraph() {
        ds.executeWrite(() -> {
            ds.getDefaultModel().add(stmt);
        });
    }
    */
    
    @Benchmark
    public void addBSBMToDefaultGraph() {
        ds.executeWrite(() -> {
            try {
                ds.getDefaultModel().read(new FileInputStream(bsbmFile), null, "NT");
            } catch (FileNotFoundException ex) {
                throw new RuntimeException(ex);
            }
        });
        
        ds.executeRead(() -> {
            long size = ds.getDefaultModel().size();
            System.out.println(size);
        });
    }
    

    public static void main(String[] args) throws RunnerException {
        //https://jmh.morethan.io/
        
        Options opt = new OptionsBuilder()
            .verbosity(VerboseMode.NORMAL)
            .include(AticTdbComparisonBsbmLoading.class.getSimpleName())
            .resultFormat(ResultFormatType.JSON)
            .result(AticTdbComparisonBsbmLoading.class.getSimpleName() + ".json")
            .addProfiler("gc")
            .build();
        
        new Runner(opt).run();
    }

}
