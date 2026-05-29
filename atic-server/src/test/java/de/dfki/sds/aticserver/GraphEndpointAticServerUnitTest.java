package de.dfki.sds.aticserver;

import de.dfki.sds.atic.conf.ConfigLoader;
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
import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class GraphEndpointAticServerUnitTest {

    public GraphEndpointAticServerUnitTest() {
    }

    private static Path tempDir;
    private static AticConfig appConfig;
    private static AticServer server;

    @BeforeEach
    public void setUp() throws Exception {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");

        // Create temp directory
        tempDir = Files.createTempDirectory("atic-test-");

        System.out.println(tempDir);

        // Set as working directory
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());

        String[] args = new String[]{
            "--home", tempDir.toAbsolutePath().toString()
        };

        appConfig = ConfigLoader.load(AticConfig.class, args);

        server = new AticServer(appConfig);

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
    public void testGetGraph() throws Exception {
        String host = appConfig.getHost();
        int port = appConfig.getPort();

        String token = loginAsAdmin();

        String url = "http://" + host + ":" + port + "/graph?triples&permissions";

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + token)
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "Expected HTTP 200");

        String body = response.body();
        assertNotNull(body);
        assertFalse(body.isEmpty(), "Response body should not be empty");

        System.out.println(body);

        // Since endpoint returns JSON-LD, we can still parse it as JSON
        JSONObject bodyObj = new JSONObject(body);

        // Optional checks depending on your output structure
        assertTrue(bodyObj.length() > 0, "Graph list should not be empty");
    }

    @Test
    public void testCreateGraphAndListGraphs() throws Exception {

        String host = appConfig.getHost();
        int port = appConfig.getPort();
        String token = loginAsAdmin();

        HttpClient client = HttpClient.newHttpClient();

        String graphUri = "https://example.org/test-graph";

        // ---- POST /graph ----
        String postUrl = "http://" + host + ":" + port + "/graph";

        JSONObject payload = new JSONObject();
        payload.put("graph", graphUri);

        HttpRequest postRequest = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .uri(URI.create(postUrl))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> postResponse
                = client.send(postRequest, HttpResponse.BodyHandlers.ofString());

        assertEquals(201, postResponse.statusCode(), "POST /graph should return 201");

        // ---- GET /graph?triples ----
        String getUrl = "http://" + host + ":" + port + "/graph?triples";

        HttpRequest getRequest = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + token)
                .uri(URI.create(getUrl))
                .GET()
                .build();

        HttpResponse<String> getResponse
                = client.send(getRequest, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, getResponse.statusCode(), "GET /graph should return 200");

        String body = getResponse.body();
        assertNotNull(body);
        assertFalse(body.isEmpty());

        System.out.println(body);

        JSONObject bodyObj = new JSONObject(body);

        assertTrue(bodyObj.has("items"), "Response must contain items");

        JSONArray items = bodyObj.getJSONArray("items");

        boolean foundDefaultGraph = false;
        boolean foundCreatedGraph = false;

        for (int i = 0; i < items.length(); i++) {
            JSONObject graph = items.getJSONObject(i);

            String id = graph.getString("@id");

            if (id.equals("urn:x-arq:DefaultGraph")) {
                foundDefaultGraph = true;
            }

            if (id.equals(graphUri)) {
                foundCreatedGraph = true;
            }
        }

        assertTrue(foundDefaultGraph, "Default graph should be present");
        assertTrue(foundCreatedGraph, "Created graph should be present");
    }

    @Test
    public void testCreateGraphWithGeneratedUri() throws Exception {

        String host = appConfig.getHost();
        int port = appConfig.getPort();
        String token = loginAsAdmin();

        HttpClient client = HttpClient.newHttpClient();

        // ---- POST /graph (no payload) ----
        String postUrl = "http://" + host + ":" + port + "/graph";

        HttpRequest postRequest = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + token)
                .uri(URI.create(postUrl))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> postResponse
                = client.send(postRequest, HttpResponse.BodyHandlers.ofString());

        assertEquals(201, postResponse.statusCode(), "POST /graph should return 201");

        // ---- extract generated URI from header ----
        String generatedUri = postResponse.headers()
                .firstValue("Atic-Resource-URI")
                .orElseThrow(() -> new AssertionError("Missing Atic-Resource-URI header"));

        System.out.println("Generated graph URI: " + generatedUri);

        // ---- GET /graph ----
        String getUrl = "http://" + host + ":" + port + "/graph";

        HttpRequest getRequest = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + token)
                .uri(URI.create(getUrl))
                .GET()
                .build();

        HttpResponse<String> getResponse
                = client.send(getRequest, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, getResponse.statusCode(), "GET /graph should return 200");

        String body = getResponse.body();
        assertNotNull(body);
        assertFalse(body.isEmpty());

        System.out.println(body);

        JSONObject bodyObj = new JSONObject(body);

        assertTrue(bodyObj.has("items"), "Response must contain items");

        JSONArray items = bodyObj.getJSONArray("items");

        boolean foundGeneratedGraph = false;

        for (int i = 0; i < items.length(); i++) {
            JSONObject graph = items.getJSONObject(i);

            String id = graph.getString("@id");

            if (id.equals(generatedUri)) {
                foundGeneratedGraph = true;
            }
        }

        assertTrue(foundGeneratedGraph, "Generated graph should be present in result set");
    }

    @Test
    public void testCreateAndDeleteGraph() throws Exception {

        String host = appConfig.getHost();
        int port = appConfig.getPort();
        String token = loginAsAdmin();

        HttpClient client = HttpClient.newHttpClient();

        String graphUri = "https://example.org/test-delete-graph";

        // ---- POST /graph ----
        String postUrl = "http://" + host + ":" + port + "/graph";

        JSONObject payload = new JSONObject();
        payload.put("graph", graphUri);

        HttpRequest postRequest = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .uri(URI.create(postUrl))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> postResponse
                = client.send(postRequest, HttpResponse.BodyHandlers.ofString());

        assertEquals(201, postResponse.statusCode(), "POST /graph should return 201");

        // ---- DELETE /graph/{uri} ----
        String encodedUri = URLEncoder.encode(graphUri, StandardCharsets.UTF_8);
        String deleteUrl = "http://" + host + ":" + port + "/graph/" + encodedUri;

        HttpRequest deleteRequest = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + token)
                .uri(URI.create(deleteUrl))
                .DELETE()
                .build();

        HttpResponse<String> deleteResponse
                = client.send(deleteRequest, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, deleteResponse.statusCode(), "DELETE /graph should return 200");

        // ---- GET /graph ----
        String getUrl = "http://" + host + ":" + port + "/graph";

        HttpRequest getRequest = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + token)
                .uri(URI.create(getUrl))
                .GET()
                .build();

        HttpResponse<String> getResponse
                = client.send(getRequest, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, getResponse.statusCode(), "GET /graph should return 200");

        String body = getResponse.body();
        assertNotNull(body);
        assertFalse(body.isEmpty());

        System.out.println(body);

        JSONObject bodyObj = new JSONObject(body);
        JSONArray items = bodyObj.getJSONArray("items");

        boolean foundDeletedGraph = false;

        for (int i = 0; i < items.length(); i++) {
            JSONObject graph = items.getJSONObject(i);
            String id = graph.getString("@id");

            if (id.equals(graphUri)) {
                foundDeletedGraph = true;
            }
        }

        assertFalse(foundDeletedGraph, "Deleted graph should not be present in result set");
    }

    @Test
    public void testDeleteDefaultGraphShouldFail() throws Exception {

        String host = appConfig.getHost();
        int port = appConfig.getPort();
        String token = loginAsAdmin();

        HttpClient client = HttpClient.newHttpClient();

        String defaultGraphUri = "urn:x-arq:DefaultGraph";

        // ---- DELETE /graph/{uri} ----
        String encodedUri = URLEncoder.encode(defaultGraphUri, StandardCharsets.UTF_8);
        String deleteUrl = "http://" + host + ":" + port + "/graph/" + encodedUri;

        HttpRequest deleteRequest = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + token)
                .uri(URI.create(deleteUrl))
                .DELETE()
                .build();

        HttpResponse<String> deleteResponse
                = client.send(deleteRequest, HttpResponse.BodyHandlers.ofString());

        // This operation should be rejected
        assertEquals(400, deleteResponse.statusCode(),
                "Deleting the default graph should return 400 Bad Request");

    }
}
