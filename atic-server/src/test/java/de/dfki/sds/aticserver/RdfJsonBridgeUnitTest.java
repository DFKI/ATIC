package de.dfki.sds.aticserver;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.conf.ConfigLoader;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import io.json.compare.JSONCompare;
import java.io.ByteArrayInputStream;
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
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.RDFPatchOps;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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

    private JSONObject loadJson(String filename) throws IOException {
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

    private Object loadJSON(String filename) throws IOException {
        try (InputStream is = MoleculeEndpointAticServerUnitTest.class
                .getResourceAsStream("/de/dfki/sds/aticserver/" + filename)) {

            if (is == null) {
                throw new RuntimeException(filename + " not found");
            }

            String json = IOUtils.toString(is, StandardCharsets.UTF_8);

            Object result = new JSONTokener(json).nextValue();

            if (result instanceof JSONObject || result instanceof JSONArray) {
                return result;
            }

            throw new JSONException("Root JSON value must be an object or array");
        }
    }

    @Test
    public void personNameList() throws Exception {
        helperQuery("03_bridge_persons.ttl", "bridge_01_personNameList.json", Map.of());
    }

    @Test
    public void personNameListBound() throws Exception {
        helperQuery("03_bridge_persons.ttl", "bridge_02_personNameListBound.json", 
                Map.of("person", List.of("https://example.org/id/alice-smith"))
        );
    }

    
    @Disabled
    @Test
    public void test2() throws Exception {
        helperModification("POST", "03_bridge_persons.ttl", "templPersonTable1.json", "templPersonTable_post_data.json", Map.of());
    }

    //=================================================
    private void helperQuery(String ttlFilename, String templFilename, Map<String, List<String>> queryParams) throws Exception {
        loadData(ttlFilename);
        JSONObject template = loadJson(templFilename);

        String token = loginAsAdmin();

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(bridgeUri(queryParams))
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
        //JSONObject result = new JSONObject(response.body());
        //System.out.println(result.toString(2));
        Object expectedBody = loadJSON(templFilename.replace(".json", "-expected.json"));

        if (String.valueOf(response.statusCode()).startsWith("2")) {
            String body = response.body();
            assertNotNull(body);

            Object actualBody = new JSONTokener(body).nextValue();

            System.out.println(actualBody);

            JSONCompare.assertMatches(expectedBody, actualBody);

        } else {
            Assertions.fail(response.toString());
        }
    }

    private void helperModification(String operation, String ttlFilename, String templFilename, String dataFilename, Map<String, List<String>> queryParams) throws Exception {
        loadData(ttlFilename);
        JSONObject template = loadJson(templFilename);

        JSONObject data = loadJson(dataFilename);

        JSONObject requestJson = new JSONObject();
        requestJson.put("data", data);
        requestJson.put("template", template);

        String token = loginAsAdmin();

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(bridgeUri(queryParams))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method(
                        operation,
                        HttpRequest.BodyPublishers.ofString(
                                requestJson.toString(2)
                        )
                )
                .build();

        HttpResponse<String> response
                = client.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        RDFPatch patch;
        try (InputStream in = new ByteArrayInputStream(
                response.body().getBytes(StandardCharsets.UTF_8))) {

            patch = RDFPatchOps.read(in);
        }

        //System.out.println(response.statusCode());
        //System.out.println(response.body());
        System.out.println(RDFPatchOps.str(patch));
    }

    private URI bridgeUri(Map<String, List<String>> queryParams) {
        
        String host = appConfig.getHost();
        int port = appConfig.getPort();

        StringJoiner joiner = new StringJoiner("&");

        if (queryParams != null) {
            queryParams.forEach((key, values)
                    -> values.forEach(value
                            -> joiner.add(
                            URLEncoder.encode(key, StandardCharsets.UTF_8)
                            + "="
                            + URLEncoder.encode(value, StandardCharsets.UTF_8)
                    )
                    )
            );
        }

        String base = "http://" + host + ":" + port + "/bridge";

        return URI.create(
                joiner.length() == 0
                ? base
                : base + "?" + joiner
        );
    }
}
