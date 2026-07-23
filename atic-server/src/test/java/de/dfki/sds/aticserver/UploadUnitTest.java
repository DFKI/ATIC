package de.dfki.sds.aticserver;

import de.dfki.sds.atic.ac.Group;
import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.conf.ConfigLoader;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.UUID;
import org.apache.commons.io.FileUtils;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class UploadUnitTest {

    private static Path tempDir;
    private static AticConfig appConfig;
    private static AticServer server;

    private String userUsername;
    private String userPassword;

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

        userUsername = "john.doe";
        server.getDatasetGraph().executeWrite(() -> {
            userPassword = server.getDatasetGraph().addUser("John", "Doe", "john.doe@example.org", userUsername, InvocationContext.EMPTY);
        });

        server.init((javalinConf, aticConf) -> {
            //person
            ConfigDrivenCrudEndpoints personCDCE = new ConfigDrivenCrudEndpoints("/de/dfki/sds/aticserver/cdce/person.yml");
            personCDCE.setGlobalDefaultLimit(5);
            personCDCE.register(javalinConf.routes, "", server.getDatasetGraph());
        });
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

        return login(username, password);
    }

    private String loginAsUser() throws IOException, InterruptedException {
        return login(userUsername, userPassword);
    }

    private String login(String username, String password) throws IOException, InterruptedException {
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
    public void testUploadTtlFileToGraph() throws Exception {

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

        // URL for upload
        String uploadUrl = "http://" + host + ":" + port + "/upload";

        // TTL content for test
        String ttlContent = """
        @prefix ex: <http://example.org/> .
        ex:subject ex:predicate "object" .
        """;

        // boundary for multipart form
        String boundary = "------------------------" + UUID.randomUUID().toString().replace("-", "");

        // Build multipart body
        String LINE_BREAK = "\r\n";
        StringBuilder bodyBuilder = new StringBuilder();

        // file part (named "file")
        bodyBuilder.append("--").append(boundary).append(LINE_BREAK);
        bodyBuilder.append("Content-Disposition: form-data; name=\"file\"; filename=\"test.ttl\"").append(LINE_BREAK);
        bodyBuilder.append("Content-Type: text/turtle").append(LINE_BREAK).append(LINE_BREAK);
        bodyBuilder.append(ttlContent).append(LINE_BREAK);

        // graph name part
        bodyBuilder.append("--").append(boundary).append(LINE_BREAK);
        bodyBuilder.append("Content-Disposition: form-data; name=\"graph\"").append(LINE_BREAK).append(LINE_BREAK);
        bodyBuilder.append(graphUri).append(LINE_BREAK);

        // permission part
        bodyBuilder.append("--").append(boundary).append(LINE_BREAK);
        bodyBuilder.append("Content-Disposition: form-data; name=\"permission\"").append(LINE_BREAK).append(LINE_BREAK);
        bodyBuilder.append("READ").append(LINE_BREAK);

        // close boundary
        bodyBuilder.append("--").append(boundary).append("--").append(LINE_BREAK);

        byte[] bodyBytes = bodyBuilder.toString().getBytes(StandardCharsets.UTF_8);

        // Build request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uploadUrl))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build();

        // Send upload
        HttpResponse<String> response
                = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Assert 200 OK
        assertEquals(200, response.statusCode(), "Upload should return 200");

        // Named graph node (same as graphUri)
        Node graphNode = NodeFactory.createURI(graphUri);

        // Subject, predicate, and object from your TTL
        Node subj = NodeFactory.createURI("http://example.org/subject");
        Node pred = NodeFactory.createURI("http://example.org/predicate");
        Node obj = NodeFactory.createLiteralString("object");

        User adminUser = server.getDatasetGraph().calculateRead(() -> {
            return server.getDatasetGraph().getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ictx = new InvocationContext.Builder().fromUser(adminUser).build();

        // Find quads matching graph + triple pattern
        server.getDatasetGraph().executeRead(() -> {
            Iterator<Quad> it = server.getDatasetGraph()
                    .find(graphNode, subj, pred, obj, ictx);

            // Assert that at least one quad exists
            assertTrue(it.hasNext(), "The triple should be present in the graph");

            // Optionally inspect the first quad
            Quad found = it.next();
            assertEquals(graphNode, found.getGraph(), "Quad should be in the correct named graph");

        });
    }

    @Test
    public void testUploadRdfTtlAndFindRdfList() throws Exception {

        String host = appConfig.getHost();
        int port = appConfig.getPort();
        String token = loginAsAdmin();

        HttpClient client = HttpClient.newHttpClient();

        // Create a new graph first
        String graphUri = "https://example.org/rdf-schema-graph";
        String postUrl = "http://" + host + ":" + port + "/graph";

        JSONObject payload = new JSONObject();
        payload.put("graph", graphUri);

        HttpRequest postRequest = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .uri(URI.create(postUrl))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> postResponse = client.send(postRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, postResponse.statusCode(), "POST /graph should return 201");

        // Now upload the RDF TTL file from the classpath
        String uploadUrl = "http://" + host + ":" + port + "/upload";

        // Load the TTL file from resources
        InputStream ttlStream = getClass().getResourceAsStream("/de/dfki/sds/aticserver/ont/rdf.ttl");
        assertNotNull(ttlStream, "rdf.ttl resource should be available");

        String ttlContent = new String(ttlStream.readAllBytes(), StandardCharsets.UTF_8);

        String boundary = "------------------------" + UUID.randomUUID().toString().replace("-", "");
        String LINE_BREAK = "\r\n";
        StringBuilder body = new StringBuilder();

        // file part
        body.append("--").append(boundary).append(LINE_BREAK);
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"rdf.ttl\"").append(LINE_BREAK);
        body.append("Content-Type: text/turtle").append(LINE_BREAK).append(LINE_BREAK);
        body.append(ttlContent).append(LINE_BREAK);

        // graph name part
        body.append("--").append(boundary).append(LINE_BREAK);
        body.append("Content-Disposition: form-data; name=\"graph\"").append(LINE_BREAK).append(LINE_BREAK);
        body.append(graphUri).append(LINE_BREAK);

        // no permission needed for schema
        body.append("--").append(boundary).append("--").append(LINE_BREAK);

        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpRequest uploadRq = HttpRequest.newBuilder()
                .uri(URI.create(uploadUrl))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build();

        HttpResponse<String> uploadResponse = client.send(uploadRq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, uploadResponse.statusCode(), "Upload of rdf.ttl should return 200");

        // Now check in the dataset graph that rdf:List exists in that named graph
        Node graphNode = NodeFactory.createURI(graphUri);

        // rdf:List URI
        Node rdfListNode = NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#List");
        Node rdfType = NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
        Node rdfsClass = NodeFactory.createURI("http://www.w3.org/2000/01/rdf-schema#Class");

        // run under read context
        User adminUser = server.getDatasetGraph().calculateRead(()
                -> server.getDatasetGraph().getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY));

        InvocationContext ictx = new InvocationContext.Builder().fromUser(adminUser).build();

        server.getDatasetGraph().executeRead(() -> {
            Iterator<Quad> it = server.getDatasetGraph()
                    .find(graphNode, rdfListNode, rdfType, rdfsClass, ictx);

            assertTrue(it.hasNext(), "rdf:List should be present as a class in the named graph");
        });
    }

    @Test
    public void testUploadToDefaultGraphWithGroupPermission() throws Exception {

        String host = appConfig.getHost();
        int port = appConfig.getPort();
        String adminToken = loginAsAdmin();

        HttpClient client = HttpClient.newHttpClient();

        // ---- Create a non-admin user "John Doe" and group ----
        // transactionally add user
        String johnPassword = server.getDatasetGraph().calculateWrite(() -> {
            return server.getDatasetGraph().addUser("John", "Doe", "john.doe@example.org", "john", InvocationContext.EMPTY);
        });

        Group everyoneGroup = server.getDatasetGraph().calculateRead(() -> {
            return server.getDatasetGraph().getGroup(UserGroupManagement.EVERYONE_GROUP, InvocationContext.EMPTY);
        });

        // ------ Upload TTL to default graph (no graph param) -----
        String uploadUrl = "http://" + host + ":" + port + "/upload";
        String ttlContent = """
        @prefix ex: <http://example.org/> .
        ex:johnSubject ex:johnPredicate "johnObject" .
        """;

        String boundary = "------------------------" + UUID.randomUUID().toString().replace("-", "");
        String LINE_BREAK = "\r\n";
        StringBuilder bodyBuilder = new StringBuilder();

        // file part
        bodyBuilder.append("--").append(boundary).append(LINE_BREAK);
        bodyBuilder.append("Content-Disposition: form-data; name=\"file\"; filename=\"john.ttl\"").append(LINE_BREAK);
        bodyBuilder.append("Content-Type: text/turtle").append(LINE_BREAK).append(LINE_BREAK);
        bodyBuilder.append(ttlContent).append(LINE_BREAK);

        // group part
        bodyBuilder.append("--").append(boundary).append(LINE_BREAK);
        bodyBuilder.append("Content-Disposition: form-data; name=\"group\"").append(LINE_BREAK).append(LINE_BREAK);
        bodyBuilder.append(everyoneGroup.getUri()).append(LINE_BREAK);

        // permission part
        bodyBuilder.append("--").append(boundary).append(LINE_BREAK);
        bodyBuilder.append("Content-Disposition: form-data; name=\"permission\"").append(LINE_BREAK).append(LINE_BREAK);
        bodyBuilder.append("READ").append(LINE_BREAK);

        // close
        bodyBuilder.append("--").append(boundary).append("--").append(LINE_BREAK);

        byte[] bodyBytes = bodyBuilder.toString().getBytes(StandardCharsets.UTF_8);

        HttpRequest uploadRequest = HttpRequest.newBuilder()
                .uri(URI.create(uploadUrl))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build();

        HttpResponse<String> uploadResponse = client.send(uploadRequest,
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, uploadResponse.statusCode(), "Upload with default graph should succeed");

        User johnUser = server.getDatasetGraph().calculateRead(() -> {
            return server.getDatasetGraph().getUser("john", InvocationContext.EMPTY);
        });

        InvocationContext ictx = new InvocationContext.Builder().fromUser(johnUser).build();

        // Find quads matching graph + triple pattern
        server.getDatasetGraph().executeRead(() -> {
            ExtendedIterator<Triple> it = server.getDatasetGraph().getDefaultGraph(ictx).find(Node.ANY, Node.ANY, Node.ANY, ictx);

            assertTrue(it.hasNext(), "The triple should be present in the graph");

            Triple found = it.next();

            assertEquals(NodeFactory.createURI("http://example.org/johnSubject"), found.getSubject());
        });
    }

    @Test
    public void testUploadXsdTtlAndFindRestrictionBlankNode() throws Exception {

        String host = appConfig.getHost();
        int port = appConfig.getPort();
        String token = loginAsAdmin();

        HttpClient client = HttpClient.newHttpClient();

        // Create graph
        String graphUri = "https://example.org/xsd-schema-graph";
        String postUrl = "http://" + host + ":" + port + "/graph";

        JSONObject payload = new JSONObject();
        payload.put("graph", graphUri);

        HttpRequest postRequest = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .uri(URI.create(postUrl))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> postResponse = client.send(postRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, postResponse.statusCode(), "POST /graph should return 201");

        // Upload xsd.ttl
        String uploadUrl = "http://" + host + ":" + port + "/upload";

        InputStream ttlStream = getClass().getResourceAsStream("/de/dfki/sds/aticserver/ont/xsd.ttl");
        assertNotNull(ttlStream, "xsd.ttl resource should be available");

        String ttlContent = new String(ttlStream.readAllBytes(), StandardCharsets.UTF_8);

        String boundary = "------------------------" + UUID.randomUUID().toString().replace("-", "");
        String LINE_BREAK = "\r\n";
        StringBuilder body = new StringBuilder();

        body.append("--").append(boundary).append(LINE_BREAK);
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"xsd.ttl\"").append(LINE_BREAK);
        body.append("Content-Type: text/turtle").append(LINE_BREAK).append(LINE_BREAK);
        body.append(ttlContent).append(LINE_BREAK);

        body.append("--").append(boundary).append(LINE_BREAK);
        body.append("Content-Disposition: form-data; name=\"graph\"").append(LINE_BREAK).append(LINE_BREAK);
        body.append(graphUri).append(LINE_BREAK);

        body.append("--").append(boundary).append("--").append(LINE_BREAK);

        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpRequest uploadRq = HttpRequest.newBuilder()
                .uri(URI.create(uploadUrl))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build();

        HttpResponse<String> uploadResponse = client.send(uploadRq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, uploadResponse.statusCode(), "Upload of xsd.ttl should return 200");

        // Nodes needed for checking the restriction blank node
        Node graphNode = NodeFactory.createURI(graphUri);
        Node xsdPattern = NodeFactory.createURI("http://www.w3.org/2001/XMLSchema#pattern");
        Node patternLiteral = NodeFactory.createLiteralString("[+-]?[0-9]*\\.?[0-9]*");

        // read context
        User adminUser = server.getDatasetGraph().calculateRead(()
                -> server.getDatasetGraph().getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY));

        InvocationContext ictx = new InvocationContext.Builder().fromUser(adminUser).build();

        server.getDatasetGraph().executeRead(() -> {

            // Find triples where some blank node has xsd:pattern "...regex..."
            Iterator<Quad> it = server.getDatasetGraph()
                    .find(graphNode, Node.ANY, xsdPattern, patternLiteral, ictx);

            assertTrue(it.hasNext(),
                    "Blank node restriction with xsd:pattern should exist in xsd.ttl");

            Quad quad = it.next();
            assertTrue(quad.getSubject().isBlank(),
                    "Subject of xsd:pattern triple should be a blank node restriction");
        });
    }
}
