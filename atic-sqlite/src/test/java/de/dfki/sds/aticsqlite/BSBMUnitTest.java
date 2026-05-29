package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.system.StreamRDF;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class BSBMUnitTest {

    private static int size = 200;

    private static int bufferSize = 20000;

    private static int batchSize = 2000;

    private static int busyTimeoutMs = 5000;

    private static boolean enableWal = true;

    private static boolean enableForeignKeys = true;

    private static SqliteAticDatasetGraph datasetGraph;
    private static SqliteAticGraph defaultGraph;

    private static Path bsbmFile;

    private static InvocationContext adminCtx;

    public BSBMUnitTest() {
    }

    @BeforeAll
    public static void setUpClass() throws IOException {

        Path tempDir = Files.createTempDirectory("benchmark-");

        datasetGraph = TL.createDatasetGraph(tempDir);

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

    @AfterAll
    public static void tearDownClass() {
    }

    @Test
    public void load() {
        long begin = System.nanoTime();
        datasetGraph.executeWrite(() -> {
            StreamRDF streamRDF = defaultGraph.asStreamRDF(adminCtx, bufferSize, batchSize, -1);

            streamRDF.start();

            RDFParser.create()
                    .source(bsbmFile)
                    .lang(Lang.NT)
                    .parse(streamRDF);

            streamRDF.finish();
        });
        long end = System.nanoTime();

        datasetGraph.executeRead(() -> {
            int size = defaultGraph.size(adminCtx);
            Assertions.assertEquals(75550, size);

            long duration = end - begin;
            double seconds = duration / 1_000_000_000.0;

            //System.out.println(seconds + " s for " + size + " triples");
        });
    }

    @Test
    public void loadWithRead() {
        Dataset ds = DatasetFactory.wrap(datasetGraph);
        adminCtx.transferContext(ds.getContext());

        long begin = System.nanoTime();
        ds.executeWrite(() -> {
            try {
                ds.getDefaultModel().read(new FileInputStream(bsbmFile.toFile()), null, "NT");
            } catch (FileNotFoundException ex) {
                throw new RuntimeException(ex);
            }
        });
        long end = System.nanoTime();

        datasetGraph.executeRead(() -> {
            int size = defaultGraph.size(adminCtx);
            Assertions.assertEquals(75550, size);

            long duration = end - begin;
            double seconds = duration / 1_000_000_000.0;

            //System.out.println(seconds + " s for " + size + " triples");
        });
    }

    public static void main(String[] args) {

    }

}
