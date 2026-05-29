package de.dfki.sds.aticserver;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.conf.ConfigLoader;
import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import io.json.compare.JSONCompare;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.jena.datatypes.BaseDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.vocabulary.FOAF;
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
public class MoleculeEndpointAticServerUnitTest {

    public MoleculeEndpointAticServerUnitTest() {
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
    public void testNotExisting() throws Exception {
        String ttl = "";
        
        helper(ttl, "http://example.org/person1", "fragment", Map.of(), null, null, 404);
    }
    
    @Test
    public void testMoleculeEndpointWithTurtleInput() throws Exception {

        String ttl = load("01_molecule_testdata.ttl");
        String expected = load("testMoleculeEndpointWithTurtleInput.jsonld");

        helper(ttl, "http://example.org/person1", "molecule", Map.of("spo-page-size", "50", "spl-page-size", "50"), expected, e -> {
            e.getLabelProperties().add(FOAF.name);
            e.getLabelProperties().add(FOAF.nick);
        }, 200);
    }
    
    @Test
    public void testMoleculeEndpoint() throws Exception {
        String ttl = load("01_molecule_testdata.ttl");
        String expectedBody = load("testMoleculeEndpoint.jsonld");
        
        helper(ttl, "http://example.org/person1", "molecule", Map.of("spo-page-size", "50", "spl-page-size", "50"), expectedBody, null, 200);
    }
    
    @Test
    public void testUnwrapSingle() throws Exception {
        String ttl = load("01_molecule_testdata.ttl");
        String expectedBody = load("testUnwrapSingle.jsonld");
        
        helper(ttl, "http://example.org/person1", "molecule", Map.of("unwrap-single", "", "spo-page-size", "50", "spl-page-size", "50"), expectedBody, null, 200);
    }
    
    @Test
    public void testSingleValueProperty() throws Exception {
        String ttl = load("01_molecule_testdata.ttl");
        String expectedBody = load("testSingleValueProperty.jsonld");
        
        helper(ttl, "http://example.org/person1", "molecule", Map.of("single-value", FOAF.name.getURI()), expectedBody, null, 200);
    }
    
    @Test
    public void testUnwrapSingleAndSingleValuePropertyAndSingleType() throws Exception {
        String ttl = load("01_molecule_testdata.ttl");
        String expectedBody = load("testUnwrapSingleAndSingleValuePropertyAndSingleType.jsonld");
        
        helper(ttl, "http://example.org/person1", "molecule", Map.of("unwrap-single", "", "single-value", FOAF.name.getURI(), "single-type", ""), expectedBody, null, 200);
    }
    
    
    @Test
    public void testPage() throws Exception {
        
        String expectedBody = load("testPage.jsonld");
        
        Node subject = NodeFactory.createURI("http://example.org/s");
        
        helper(g -> {
            for (int i = 0; i < 1000; i++) {
                Node predicate = NodeFactory.createURI("http://example.org/p" + i);
                Node object = NodeFactory.createURI("http://example.org/o" + i);

                g.add(subject, predicate, object);
            }
        }, subject.getURI(), "molecule", Map.of("spo-page-size", "50"), expectedBody, null, 200);
    }
    
    @Test
    public void testReduceAll() throws Exception {
        String ttl = load("01_molecule_testdata.ttl");
        String expectedBody = load("testReduceAll.jsonld");
        
        helper(ttl, "http://example.org/person1", "molecule", Map.of("reduce", "*", "spo-page-size", "50", "spl-page-size", "50"), expectedBody, null, 200);
    }
    
    @Test
    public void testExcludeAll() throws Exception {
        String ttl = load("01_molecule_testdata.ttl");
        String expectedBody = load("testExcludeAll.jsonld");
        
        helper(ttl, "http://example.org/person1", "molecule", Map.of("exclude", "*"), expectedBody, null, 200);
    }
    
    @Test
    public void testExcludeAllButIncludeOne() throws Exception {
        String ttl = load("01_molecule_testdata.ttl");
        String expectedBody = load("testExcludeAllButIncludeOne.jsonld");
        
        helper(ttl, "http://example.org/person1", "molecule", Map.of(
                "exclude", "*", 
                "include", FOAF.knows.getURI()
        ), expectedBody, null, 200);
    }
    
    @Test
    public void testExcludeAllButIncludeOneAndExpand() throws Exception {
        String ttl = load("01_molecule_testdata.ttl");
        String expectedBody = load("testExcludeAllButIncludeOneAndExpand.jsonld");
        
        helper(ttl, "http://example.org/person1", "molecule", Map.of(
                "exclude", "*", 
                "include", FOAF.knows.getURI(),
                "expand", FOAF.knows.getURI()
        ), expectedBody, null, 200);
    }
    
    @Test
    public void testResourceEndpoint() throws Exception {
        String ttl = load("01_molecule_testdata.ttl");
        String expectedBody = """
                              {
                                  "uri": "http://example.org/person1",
                                  "@context": {"uri": "@id"}
                              }
                              """;
        
        helper(ttl, "http://example.org/person1", "resource", Map.of(), expectedBody, null, 200);
    }
    
    @Test
    public void testFragmentEndpoint() throws Exception {
        String ttl = load("01_molecule_testdata.ttl");
        String expectedBody = load("testFragmentEndpoint.jsonld");
        
        helper(ttl, "http://example.org/person1", "fragment", Map.of(), expectedBody, null, 200);
    }
    
    @Test
    public void testFragmentEndpointReduceType() throws Exception {
        String ttl = load("01_molecule_testdata.ttl");
        String expectedBody = load("testFragmentEndpointReduceType.jsonld");
        
        helper(ttl, "http://example.org/person1", "fragment", Map.of("expand-type", "false"), expectedBody, null, 200);
    }
    
    @Test
    public void testFragmentEndpointSingleType() throws Exception {
        String ttl = load("01_molecule_testdata.ttl");
        String expectedBody = load("testFragmentEndpointSingleType.jsonld");
        
        helper(ttl, "http://example.org/person1", "fragment", Map.of("single-type", "true"), expectedBody, null, 200);
    }
    
    @Test
    public void testFragmentEndpointLocaleJapanese() throws Exception {
        String ttl = load("01_molecule_testdata.ttl");
        String expectedBody = load("testFragmentEndpointLocaleJapanese.jsonld");
        
        helper(ttl, "http://example.org/person1", "fragment", Map.of("locale", "ja"), expectedBody, null, 200);
    }
    
    @Test
    public void testLargeLiteralContentReduced() throws Exception {
        String ttl = load("02_molecule_largeliteral.ttl");
        String expectedBody = load("testLargeLiteralContentReduced.jsonld");
        
        helper(ttl, "http://example.org/document", "molecule", Map.of("reduce", "http://example.org/content"), expectedBody, null, 200);
    }
    
    @Test
    public void testFragmentEndpointOtherKeys() throws Exception {
        String ttl = load("01_molecule_testdata.ttl");
        String expectedBody = load("testFragmentEndpointOtherKeys.jsonld");
        
        helper(ttl, "http://example.org/person1", "fragment", Map.of(
                "uri-key", "identifier",
                "label-key", "name",
                "type-key", "kind",
                "comment-key", "description"
        ), expectedBody, null, 200);
    }
    
    @Test
    public void testNativeValues() throws Exception {
        
        String expectedBody = load("testNativeValues.jsonld");
        
        Node subject = NodeFactory.createURI("http://example.org/s");
        
        helper(g -> {
            g.add(subject, NodeFactory.createURI("http://example.org/intValue"), NodeFactory.createLiteralByValue(5));
            g.add(subject, NodeFactory.createURI("http://example.org/booleanValue"), NodeFactory.createLiteralByValue(true));
            g.add(subject, NodeFactory.createURI("http://example.org/doubleValue"), NodeFactory.createLiteralByValue(5.4));
            g.add(subject, NodeFactory.createURI("http://example.org/byteValue"), NodeFactory.createLiteralByValue((byte)42));
            g.add(subject, NodeFactory.createURI("http://example.org/longValue"), NodeFactory.createLiteralByValue(12334434556L));
            
        }, subject.getURI(), "molecule", Map.of("native-value", ""), expectedBody, null, 200);
    }
    
    @Test
    public void testLang() throws Exception {
        
        String expectedBody = load("testLang.jsonld");
        
        Node subject = NodeFactory.createURI("http://example.org/s");
        
        helper(g -> {
            g.add(subject, NodeFactory.createURI("http://example.org/str"), NodeFactory.createLiteralLang("de", "de"));
            g.add(subject, NodeFactory.createURI("http://example.org/str"), NodeFactory.createLiteralLang("en", "en"));
            g.add(subject, NodeFactory.createURI("http://example.org/str"), NodeFactory.createLiteralString("str"));
            
        }, subject.getURI(), "molecule", Map.of("lang", "", "native-value", "false"), expectedBody, null, 200);
    }
    
    @Test
    public void testDatatype() throws Exception {
        
        String expectedBody = load("testDatatype.jsonld");
        
        Node subject = NodeFactory.createURI("http://example.org/s");
        
        helper(g -> {
            g.add(
                    subject, 
                    NodeFactory.createURI("http://example.org/p"), 
                    NodeFactory.createLiteralDT("5", new BaseDatatype("http://example.org/int"))
            );
        }, subject.getURI(), "molecule", Map.of("datatype", "", "native-value", "false"), expectedBody, null, 200);
    }
    
    private void helper(String ttl, String uri, String endpoint, Map<String, String> queryParams, String expectedBody, Consumer<MoleculeEndpoint> init, int status) throws Exception {
        helper(defaultGraph -> {
            // Read TTL into graph
            RDFDataMgr.read(
                    defaultGraph,
                    new StringReader(ttl),
                    null,
                    Lang.TURTLE
            );
        }, uri, endpoint, queryParams, expectedBody, init, status);
    }

    private void helper(Consumer<AticGraph> fillGraph, String uri, String endpoint, Map<String, String> queryParams, String expectedBody, Consumer<MoleculeEndpoint> init, int status) throws Exception {
        String host = appConfig.getHost();
        int port = appConfig.getPort();
        
        if(init != null) {
            init.accept(server.getMoleculeEndpoint());
        }
        
        User adminUser = datasetGraph.calculateRead(() -> {
            return datasetGraph.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ictx = new InvocationContext.Builder().fromUser(adminUser).build();

        // ---- 2. Write TTL into default graph ----
        datasetGraph.begin(ReadWrite.WRITE);
        try {
            AticGraph defaultGraph = datasetGraph.getDefaultGraph(ictx);
            AticServer.transferContext(ictx, datasetGraph.getContext());

            fillGraph.accept(defaultGraph);

            datasetGraph.commit();
        } finally {
            datasetGraph.end();
        }

        // ---- 3. Call molecule endpoint ----
        String token = loginAsAdmin();

        String encodedUri = URLEncoder.encode(uri, StandardCharsets.UTF_8);

        StringBuilder url = new StringBuilder("http://" + host + ":" + port + "/"+ endpoint +"/" + encodedUri);

        if (queryParams != null && !queryParams.isEmpty()) {
            url.append("?");
            boolean first = true;

            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                if (!first) {
                    url.append("&");
                }
                first = false;

                url.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
                url.append("=");
                url.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
        }

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/ld+json")
                .uri(URI.create(url.toString()))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(status, response.statusCode());
        
        if(String.valueOf(status).startsWith("2")) {
            String body = response.body();
            assertNotNull(body);

            System.out.println(body);

            assertNotNull(expectedBody, "expected body not set");

            JSONCompare.assertMatches(expectedBody, body);
        }
    }

    private String load(String filename) throws IOException {
        InputStream is = MoleculeEndpointAticServerUnitTest.class.getResourceAsStream("/de/dfki/sds/aticserver/" + filename);
        if(is == null) {
            throw new RuntimeException("01_molecule_testdata.ttl not found");
        }
        return IOUtils.toString(is, StandardCharsets.UTF_8);
    }
    
    
}
