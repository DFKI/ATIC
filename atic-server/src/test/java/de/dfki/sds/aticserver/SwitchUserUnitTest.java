package de.dfki.sds.aticserver;

import de.dfki.sds.atic.conf.ConfigLoader;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
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
public class SwitchUserUnitTest {

    private static Path tempDir;
    private static AticConfig appConfig;
    private static AticServer server;
    private static SqliteAticDatasetGraph datasetGraph;

    @BeforeEach
    public void setUp() throws Exception {

        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");

        // Create temp directory
        tempDir = Files.createTempDirectory("atic-test-");

        //System.out.println(tempDir);
        // Set as working directory
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());

        String[] args = new String[]{
            "--home", tempDir.toAbsolutePath().toString(),
            "--no-print.log",
            "--adminsecret",
            "--switchuser"
        };

        appConfig = ConfigLoader.load(AticConfig.class, args);

        server = new AticServer(appConfig);

        datasetGraph = server.getDatasetGraph();
        
        datasetGraph.executeWrite(() -> {
            datasetGraph.addUser("John", "Doe", "john.doe@example.org", "doe", InvocationContext.EMPTY);
        });

        server.init();
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty(AticServer.ATIC_ADMIN_SECRET_ENV);
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

    @Test
    public void testAdminSecretPositive() throws Exception {

        // Arrange
        String tempSecret = UUID.randomUUID().toString();

        System.setProperty(
                AticServer.ATIC_ADMIN_SECRET_ENV,
                tempSecret
        );

        try {
            // Act
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(
                            "http://" + appConfig.getHost() + ":"
                            + appConfig.getPort() + "/auth/su/admin"
                    ))
                    .header("Authorization", "Bearer " + tempSecret)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            // Assert
            assertEquals(200, response.statusCode());

            JSONObject resp = new JSONObject(response.body());
            
            // Optionally inspect response
            assertTrue(resp.getBoolean("successful"));

        } finally {
            System.clearProperty(AticServer.ATIC_ADMIN_SECRET_ENV);
        }
    }

    @Test
    public void testAdminSecretNegative() throws Exception {

        // Arrange
        String tempSecret = UUID.randomUUID().toString();

        System.setProperty(
                AticServer.ATIC_ADMIN_SECRET_ENV,
                tempSecret
        );

        try {
            // Act
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(
                            "http://" + appConfig.getHost() + ":"
                            + appConfig.getPort() + "/auth/su/admin"
                    ))
                    .header("Authorization", "Bearer " + "another-one")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            // Assert
            assertEquals(401, response.statusCode());

            JSONObject resp = new JSONObject(response.body());
            
            // Optionally inspect response
            assertFalse(resp.getBoolean("successful"));

        } finally {
            System.clearProperty(AticServer.ATIC_ADMIN_SECRET_ENV);
        }
    }

    @Test
    public void testAdminSecretAndSwitchUserPositive() throws Exception {

        // Arrange
        String tempSecret = UUID.randomUUID().toString();

        System.setProperty(
                AticServer.ATIC_ADMIN_SECRET_ENV,
                tempSecret
        );

        HttpClient client = HttpClient.newHttpClient();
        
        String accessTokenAdmin;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(
                            "http://" + appConfig.getHost() + ":"
                            + appConfig.getPort() + "/auth/su/admin"
                    ))
                    .header("Authorization", "Bearer " + tempSecret)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            

            HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            // Assert
            assertEquals(200, response.statusCode());

            JSONObject resp = new JSONObject(response.body());
            
            accessTokenAdmin = resp.getString("access_token");
            
            // Optionally inspect response
            assertTrue(resp.getBoolean("successful"));

        } finally {
            System.clearProperty(AticServer.ATIC_ADMIN_SECRET_ENV);
        }
        
        //=============================================
        //switch user phase
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(
                    "http://" + appConfig.getHost() + ":"
                    + appConfig.getPort() + "/auth/su/doe"
            ))
            .header("Authorization", "Bearer " + accessTokenAdmin)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        
        HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        // Assert
        assertEquals(200, response.statusCode());

        JSONObject resp = new JSONObject(response.body());
        
        String accessTokenDoe = resp.getString("access_token");

        // Optionally inspect response
        assertTrue(resp.getBoolean("successful"));
        
        //================================
        //check
        
        request = HttpRequest.newBuilder()
            .uri(URI.create(
                    "http://" + appConfig.getHost() + ":"
                    + appConfig.getPort() + "/auth/me"
            ))
            .header("Authorization", "Bearer " + accessTokenDoe)
            .GET()
            .build();
        
        response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        // Assert
        assertEquals(200, response.statusCode());
        
        JSONObject userObj = new JSONObject(response.body());
        
        assertEquals("doe", userObj.getString("username"));
    }

    @Test
    public void testAdminSecretAndSwitchUserNegative() throws Exception {

        // Arrange
        String tempSecret = UUID.randomUUID().toString();

        System.setProperty(
                AticServer.ATIC_ADMIN_SECRET_ENV,
                tempSecret
        );

        HttpClient client = HttpClient.newHttpClient();
        
        String accessTokenAdmin;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(
                            "http://" + appConfig.getHost() + ":"
                            + appConfig.getPort() + "/auth/su/admin"
                    ))
                    .header("Authorization", "Bearer " + tempSecret)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            // Assert
            assertEquals(200, response.statusCode());

            JSONObject resp = new JSONObject(response.body());
            
            accessTokenAdmin = resp.getString("access_token");
            
            // Optionally inspect response
            assertTrue(resp.getBoolean("successful"));

        } finally {
            System.clearProperty(AticServer.ATIC_ADMIN_SECRET_ENV);
        }
        
        //=============================================
        //switch user phase
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(
                    "http://" + appConfig.getHost() + ":"
                    + appConfig.getPort() + "/auth/su/nonexisting"
            ))
            .header("Authorization", "Bearer " + accessTokenAdmin)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        
        HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        // Assert
        assertEquals(401, response.statusCode());

        JSONObject resp = new JSONObject(response.body());
        
        // Optionally inspect response
        assertFalse(resp.getBoolean("successful"));
    }

    
}
