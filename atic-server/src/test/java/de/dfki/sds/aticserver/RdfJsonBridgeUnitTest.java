package de.dfki.sds.aticserver;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.conf.ConfigLoader;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class RdfJsonBridgeUnitTest {

    private static Path tempDir;
    private static AticConfig appConfig;
    private static AticServer server;
    private static SqliteAticDatasetGraph datasetGraph;

    @BeforeEach
    public void setUp() throws Exception {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");

        // Create temp directory
        tempDir = Files.createTempDirectory("bridge-test-");

        System.out.println(tempDir);

        // Set as working directory
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());

        String[] args = new String[]{
            "--home", tempDir.toAbsolutePath().toString()
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

    private void loadData(String filename) throws IOException {
        InputStream is = MoleculeEndpointAticServerUnitTest.class.getResourceAsStream("/de/dfki/sds/aticserver/" + filename);
        if (is == null) {
            throw new RuntimeException("01_molecule_testdata.ttl not found");
        }
        String ttl = IOUtils.toString(is, StandardCharsets.UTF_8);

        User adminUser = datasetGraph.calculateRead(() -> {
            return datasetGraph.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ictx = new InvocationContext.Builder().fromUser(adminUser).build();

        AticServer.transferContext(ictx, datasetGraph.getContext());

        // Read TTL into graph
        datasetGraph.executeWrite(() -> {
            RDFDataMgr.read(
                    datasetGraph,
                    new StringReader(ttl),
                    null,
                    Lang.TURTLE
            );
        });
    }

    private JSONObject loadTemplate(String filename) throws IOException {
        try (InputStream is
                = MoleculeEndpointAticServerUnitTest.class
                        .getResourceAsStream(
                                "/de/dfki/sds/aticserver/" + filename
                        )) {

                    if (is == null) {
                        throw new RuntimeException(filename + " not found");
                    }

                    return new JSONObject(IOUtils.toString(
                            is,
                            StandardCharsets.UTF_8
                    ));
                }
    }

    @Test
    public void test() throws Exception {
        helper("03_bridge_persons.ttl", "templPersonTable1.json");
    }
    
    private void helper(String ttlFilename, String templFilename) throws Exception {
        loadData(ttlFilename);
        JSONObject template = loadTemplate(templFilename);

        String host = appConfig.getHost();
        int port = appConfig.getPort();
        
        String token = loginAsAdmin();

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(
                        "http://" + host + ":" + port + "/bridge"
                ))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method(
                        "GET",
                        HttpRequest.BodyPublishers.ofString(
                                template.toString()
                        )
                )
                .build();

        HttpResponse<String> response
                = client.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        //System.out.println(response.statusCode());
        //System.out.println(response.body());

        JSONObject result = new JSONObject(response.body());
        
        System.out.println(result.toString(2));
    }

}
