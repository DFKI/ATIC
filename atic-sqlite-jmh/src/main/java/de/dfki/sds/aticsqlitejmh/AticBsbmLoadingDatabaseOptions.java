package de.dfki.sds.aticsqlitejmh;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.Database;
import de.dfki.sds.aticsqlite.DatabaseLongLivedConnection;
import de.dfki.sds.aticsqlite.DatabaseOptions;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import de.dfki.sds.aticsqlite.SqliteAticGraph;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.system.StreamRDF;
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
@Warmup(iterations = 0, time = 1)
@Measurement(iterations = 3, time = 1)
@State(Scope.Benchmark)
@Fork(1)
public class AticBsbmLoadingDatabaseOptions {

    //200 = 75_550
    @Param({"200"/* "2000"*/})
    private int size;
    
    @Param({"550", "5550", "75550"})
    private int bufferSize;
    
    //@Param({"550", "50", "5"}) //makes no difference
    @Param({"75550"})
    private int batchSize;
    
    @Param({"0", "5000"})
    private int busyTimeoutMs;
    
    @Param({"true", "false"})
    private boolean enableWal;
    
    @Param({"true", "false"})
    private boolean enableForeignKeys;

    private SqliteAticDatasetGraph datasetGraph;
    private SqliteAticGraph defaultGraph;
    
    private Path bsbmFile;
    
    private InvocationContext adminCtx;

    @Setup
    public void init() throws IOException {

        Path tempDir = Files.createTempDirectory("benchmark-");

        Path dbFile = tempDir.resolve("benchmark.sqlite");
        
        DatabaseOptions options
                = new DatabaseOptions.Builder(dbFile.toString())
                        .busyTimeoutMs(busyTimeoutMs)
                        .enableWal(enableWal)
                        .enableForeignKeys(enableForeignKeys)
                        .build();
        
        Database db = new DatabaseLongLivedConnection(options);
        datasetGraph = new SqliteAticDatasetGraph(db);
        
        //set to admin
        User adminUser = datasetGraph.calculateRead(() -> {
            return datasetGraph.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });
        adminCtx = new InvocationContext.Builder().fromUser(adminUser).build();
        
        defaultGraph = datasetGraph.calculateRead(() -> {
            return (SqliteAticGraph) datasetGraph.getDefaultGraph(adminCtx);
        });

        bsbmFile = Downloader.downloadBSBM(size, true);
    }
    
    //@Benchmark
    public void addToDefaultGraph() {
        datasetGraph.executeWrite(() -> {
            StreamRDF streamRDF = defaultGraph.asStreamRDF(adminCtx, bufferSize, batchSize, -1);

            streamRDF.start();

            RDFParser.create()
                    .source(bsbmFile)
                    .lang(Lang.NT)
                    .parse(streamRDF);

            streamRDF.finish();
        });
    }
    
    @Benchmark
    public long addToDefaultGraphAndSize() {
        datasetGraph.executeWrite(() -> {
            StreamRDF streamRDF = defaultGraph.asStreamRDF(adminCtx, bufferSize, batchSize, -1);

            streamRDF.start();

            RDFParser.create()
                    .source(bsbmFile)
                    .lang(Lang.NT)
                    .parse(streamRDF);

            streamRDF.finish();
        });
        
        return datasetGraph.calculateRead(() -> {
            return defaultGraph.size(adminCtx);
        });
    }
    

    public static void main(String[] args) throws RunnerException {
        //https://jmh.morethan.io/
        
        Options opt = new OptionsBuilder()
            .verbosity(VerboseMode.NORMAL)
            .include(AticBsbmLoadingDatabaseOptions.class.getSimpleName())
            .resultFormat(ResultFormatType.JSON)
            .result(AticBsbmLoadingDatabaseOptions.class.getSimpleName() + ".json")
            .build();
        
        new Runner(opt).run();
    }

}
