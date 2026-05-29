package de.dfki.sds.aticserver;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.conf.ConfigLoader;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import de.dfki.sds.rdfpatchsqlite.Converter;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.changes.RDFChangesApply;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class RDFPatchWriterUnitTest {

    public RDFPatchWriterUnitTest() {
    }

    private static Path tempDir;
    private static AticConfig appConfig;
    private static AticServer server;
    private static SqliteAticDatasetGraph datasetGraph;

    @BeforeEach
    public void setUp() throws Exception {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");

        // Create temp directory
        tempDir = Files.createTempDirectory("atic-test-");

        System.out.println(tempDir);

        // Set as working directory
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());

        String[] args = new String[]{
            "--home", tempDir.toAbsolutePath().toString(),
            "--rdfpatch.rotationinterval", "1"
        };

        appConfig = ConfigLoader.load(AticConfig.class, args);

        server = new AticServer(appConfig);

        datasetGraph = server.getDatasetGraph();

        server.init();
    }

    @AfterEach
    public void tearDown() {
        server.close();
    }

    @Test
    public void testConnection() {
        String host = appConfig.getHost();
        int port = appConfig.getPort();

        try (Socket socket = new Socket(host, port)) {
            assertTrue(socket.isConnected(), "Socket should be connected to server");
        } catch (Exception e) {
            fail("Could not connect to server at " + host + ":" + port + " -> " + e.getMessage());
        }
    }

    private String loginAsAdmin() throws IOException, InterruptedException {
        File passwordsFile = new File(server.getDataFolder(), "passwords.json.generated");
        JSONObject passwords = new JSONObject(FileUtils.readFileToString(passwordsFile, StandardCharsets.UTF_8));

        String username = "admin";
        String password = passwords.getString(username);
        String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + appConfig.getHost() + ":" + appConfig.getPort() + "/auth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        //System.out.println(response.statusCode());
        JSONObject resp = new JSONObject(response.body());
        return resp.getString("access_token");
    }

    @Test
    public void testRDFPatchWriter() throws Exception {

        User adminUser = datasetGraph.calculateRead(() -> {
            return datasetGraph.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ictx = new InvocationContext.Builder().fromUser(adminUser).build();

        File folder = server.getPatchesFolder();

        // clean folder before test
        for (File f : folder.listFiles()) {
            f.delete();
        }

        Converter converter = new Converter();

        Node g = NodeFactory.createURI("http://example.org/graph");
        Node p = NodeFactory.createURI("http://example.org/predicate");

        Node s = NodeFactory.createURI("http://example.org/s1");
        Node s2 = NodeFactory.createURI("http://example.org/s2");
        Node s3 = NodeFactory.createURI("http://example.org/s3");

        Node o = NodeFactory.createLiteralString("value");
        
        datasetGraph.executeWrite(() -> {
            datasetGraph.addGraph(g,Graph.emptyGraph, ictx);
        });

        datasetGraph.executeWrite(() -> {
            datasetGraph.add(g, s, p, o, ictx);
        });

        Thread.sleep(1500);

        datasetGraph.executeWrite(() -> {
            datasetGraph.add(g, s2, p, o, ictx);
        });

        Thread.sleep(1500);

        datasetGraph.executeWrite(() -> {
            datasetGraph.add(g, s3, p, o, ictx);
        });

        // ---- verify files ----
        List<File> dbFiles = Arrays.stream(folder.listFiles())
                .filter(f -> f.getName().endsWith(".db"))
                .collect(Collectors.toList());

        // Expect: 2 done + 1 rolling
        assertEquals(3, dbFiles.size());

        List<File> rolling = dbFiles.stream()
                .filter(f -> f.getName().startsWith("rolling-"))
                .collect(Collectors.toList());

        List<File> done = dbFiles.stream()
                .filter(f -> f.getName().startsWith("done-"))
                .collect(Collectors.toList());

        assertEquals(1, rolling.size());
        assertEquals(2, done.size());

        // ---- verify content of rolling DB ----
        File rollingFile = rolling.get(0);
        String jdbcLink = "jdbc:sqlite:" + rollingFile.getAbsolutePath();

        RDFPatch patch = converter.toPatch(jdbcLink);
        
        DatasetGraph dg = DatasetGraphFactory.create();
        patch.apply(new RDFChangesApply(dg));
        
        // verify latest triple exists
        assertTrue(dg.contains(g, s3, p, o));

        // optional: ensure previous ones are NOT in rolling
        assertFalse(dg.contains(g, s, p, o));
        assertFalse(dg.contains(g, s2, p, o));
    }
}

