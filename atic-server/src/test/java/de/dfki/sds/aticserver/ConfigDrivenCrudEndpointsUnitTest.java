package de.dfki.sds.aticserver;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.conf.ConfigLoader;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import de.dfki.sds.aticsqlite.SqliteAticGraph;
import java.io.File;
import java.io.IOException;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.TxnType;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.sparql.vocabulary.FOAF;
import org.apache.jena.vocabulary.RDF;
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
public class ConfigDrivenCrudEndpointsUnitTest {

    public ConfigDrivenCrudEndpointsUnitTest() {
    }

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

        server.init((app, conf) -> {
            //person
            ConfigDrivenCrudEndpoints personCDCE = new ConfigDrivenCrudEndpoints("/de/dfki/sds/aticserver/cdce/person.yml");
            personCDCE.setGlobalDefaultLimit(5);
            personCDCE.register(app, "", server.getDatasetGraph());
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

    private String loginAsUser() throws IOException, InterruptedException {
        String username = userUsername;
        String password = userPassword;
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
    public void testGetPerson() throws Exception {
        String host = appConfig.getHost();
        int port = appConfig.getPort();

        String token = loginAsAdmin();

        String url = "http://" + host + ":" + port + "/person";

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

        JSONObject bodyObj = new JSONObject(body);

        System.out.println(bodyObj.toString(2));
    }

    @Test
    public void testPostPerson() throws Exception {
        String host = appConfig.getHost();
        int port = appConfig.getPort();

        String token = loginAsAdmin();

        String url = "http://" + host + ":" + port + "/person";

        // JSON-LD payload
        String jsonLd = """
        {
          "@context": {
            "name": "http://xmlns.com/foaf/0.1/name",
            "Person": "http://xmlns.com/foaf/0.1/Person"
          },
          "@type": "Person",
          "name": "John Doe"
        }
        """;
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/ld+json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonLd))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // Typical REST expectation: 201 Created (adjust if your API differs)
        assertTrue(
                response.statusCode() == 204,
                "Expected HTTP 204 but got " + response.statusCode()
        );

        String location = response.headers().firstValue("Location").get();
        assertTrue(location.startsWith("/person/"));

        String resURI = response.headers().firstValue("Atic-Resource-URI").get();
        assertTrue(resURI.startsWith("urn:atic:"));

        url = "http://" + host + ":" + port + "/person";

        request = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + token)
                .uri(URI.create(url))
                .GET()
                .build();

        response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "Expected HTTP 200");

        String body = response.body();
        assertNotNull(body);
        assertFalse(body.isEmpty(), "Response body should not be empty");

        JSONObject bodyObj = new JSONObject(body);
        System.out.println(bodyObj.toString(2));

        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new StringReader(body), null, Lang.JSONLD);

        // Assert a foaf:Person exists
        ResIterator persons = model.listResourcesWithProperty(RDF.type, FOAF.Person);
        assertTrue(persons.hasNext(), "No foaf:Person found in response");

        Resource person = persons.next();

        // Assert the name property
        Statement nameStmt = person.getProperty(FOAF.name);
        assertNotNull(nameStmt, "Person should have foaf:name");

        String name = nameStmt.getString();
        assertEquals("John Doe", name, "Person name mismatch");

        // ensure only one person was created
        assertFalse(persons.hasNext(), "More than one foaf:Person found unexpectedly");
    }

    @Test
    public void testPutPerson() throws Exception {
        String host = appConfig.getHost();
        int port = appConfig.getPort();

        String token = loginAsAdmin();
        String collectionUrl = "http://" + host + ":" + port + "/person";

        HttpClient client = HttpClient.newHttpClient();

        // ---- CREATE PERSON ----
        String jsonLd = """
    {
      "@context": {
        "name": "http://xmlns.com/foaf/0.1/name",
        "Person": "http://xmlns.com/foaf/0.1/Person"
      },
      "@type": "Person",
      "name": "John Doe"
    }
    """;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(collectionUrl))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/ld+json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonLd))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertTrue(
                response.statusCode() == 200 || response.statusCode() == 201 || response.statusCode() == 204,
                "Expected HTTP 200/201/204 but got " + response.statusCode()
        );

        // ---- GET LOCATION HEADER ----
        Optional<String> locationHeader = response.headers().firstValue("Location");
        assertTrue(locationHeader.isPresent(), "POST response should contain Location header");

        String personLocation = locationHeader.get();
        assertNotNull(personLocation);

        Optional<String> aticResourceUriHeader = response.headers().firstValue("Atic-Resource-URI");
        assertTrue(aticResourceUriHeader.isPresent(), "POST response should contain Atic-Resource-URI header");

        String aticResourceUri = aticResourceUriHeader.get();
        assertNotNull(aticResourceUri);

        // ---- VERIFY CREATED PERSON ----
        HttpRequest getRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + personLocation))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> getResponse = client.send(getRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, getResponse.statusCode());

        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new StringReader(getResponse.body()), null, Lang.JSONLD);

        Resource person = model.getResource(aticResourceUri);
        Statement nameStmt = person.getProperty(FOAF.name);

        assertNotNull(nameStmt);
        assertEquals("John Doe", nameStmt.getString());

        // ---- UPDATE PERSON ----
        String updatedJsonLd = """
        {
          "@context": {
            "name": "http://xmlns.com/foaf/0.1/name",
            "Person": "http://xmlns.com/foaf/0.1/Person"
          },
          "@id": "%s",
          "@type": "Person",
          "name": "Jane Doe"
        }
        """.formatted(aticResourceUri);

        HttpRequest putRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + personLocation))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/ld+json")
                .PUT(HttpRequest.BodyPublishers.ofString(updatedJsonLd))
                .build();

        HttpResponse<String> putResponse = client.send(putRequest, HttpResponse.BodyHandlers.ofString());

        assertTrue(
                putResponse.statusCode() == 200 || putResponse.statusCode() == 204,
                "Expected HTTP 200 or 204 but got " + putResponse.statusCode()
        );

        // ---- VERIFY UPDATE ----
        HttpResponse<String> verifyResponse = client.send(getRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, verifyResponse.statusCode());

        Model updatedModel = ModelFactory.createDefaultModel();
        RDFDataMgr.read(updatedModel, new StringReader(verifyResponse.body()), null, Lang.JSONLD);

        Resource updatedPerson = updatedModel.getResource(aticResourceUri);
        Statement updatedNameStmt = updatedPerson.getProperty(FOAF.name);

        assertNotNull(updatedNameStmt);
        assertEquals("Jane Doe", updatedNameStmt.getString());
    }

    @Test
    public void testDeletePerson() throws Exception {
        String host = appConfig.getHost();
        int port = appConfig.getPort();

        String token = loginAsAdmin();
        HttpClient client = HttpClient.newHttpClient();

        String baseUrl = "http://" + host + ":" + port + "/person";

        // JSON-LD payload
        String jsonLd = """
        {
          "@context": {
            "name": "http://xmlns.com/foaf/0.1/name",
            "Person": "http://xmlns.com/foaf/0.1/Person"
          },
          "@type": "Person",
          "name": "John Doe"
        }
        """;

        // ---- CREATE PERSON ----
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/ld+json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonLd))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertTrue(
                response.statusCode() == 200 || response.statusCode() == 204,
                "Expected HTTP 200 or 204 but got " + response.statusCode()
        );

        // ---- GET PERSON LIST ----
        request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        String body = response.body();

        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new StringReader(body), null, Lang.JSONLD);

        // find created person
        ResIterator persons = model.listResourcesWithProperty(RDF.type, FOAF.Person);
        assertTrue(persons.hasNext(), "No foaf:Person found");

        Resource person = persons.next();
        String personUri = person.getURI();

        assertNotNull(personUri, "Person URI should not be null");

        // ---- DELETE PERSON ----
        String encodedUri = URLEncoder.encode(personUri, StandardCharsets.UTF_8);

        request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + encodedUri))
                .header("Authorization", "Bearer " + token)
                .DELETE()
                .build();

        response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertTrue(
                response.statusCode() == 200 || response.statusCode() == 204,
                "Expected HTTP 200 or 204 but got " + response.statusCode()
        );

        // ---- VERIFY DELETION ----
        request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        Model modelAfterDelete = ModelFactory.createDefaultModel();
        RDFDataMgr.read(modelAfterDelete, new StringReader(response.body()), null, Lang.JSONLD);

        boolean stillExists = modelAfterDelete.contains(
                modelAfterDelete.createResource(personUri),
                RDF.type,
                FOAF.Person
        );

        assertFalse(stillExists, "Person should have been deleted");
    }

    @Test
    public void testGetPersonPagination() throws Exception {
        String host = appConfig.getHost();
        int port = appConfig.getPort();

        String token = loginAsAdmin();
        
        SqliteAticDatasetGraph datasetGraph = server.getDatasetGraph();
        
        User adminUser = datasetGraph.calculateRead(() -> {
            return server.getDatasetGraph().getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ictx = new InvocationContext.Builder().fromUser(adminUser).build();

        // ---- INSERT 20 PERSONS ----
        datasetGraph.begin(TxnType.WRITE);
        try {
            SqliteAticGraph sqliteAticGraph = (SqliteAticGraph) datasetGraph.getDefaultGraph(ictx);
            StreamRDF stream = sqliteAticGraph.asStreamRDF(ictx, 500, 500, -1);

            stream.start();

            for (int i = 0; i < 20; i++) {

                String uri = "urn:person:" + UUID.randomUUID();
                String name = "Person-" + UUID.randomUUID().toString().substring(0, 8);

                Node s = NodeFactory.createURI(uri);

                stream.triple(Triple.create(
                        s,
                        RDF.type.asNode(),
                        FOAF.Person.asNode()
                ));

                stream.triple(Triple.create(
                        s,
                        FOAF.name.asNode(),
                        NodeFactory.createLiteralString(name)
                ));
            }

            stream.finish();
            datasetGraph.commit();
        } finally {
            datasetGraph.end();
        }

        HttpClient client = HttpClient.newHttpClient();

        // ---- PAGE 1 ----
        HttpRequest requestPage1 = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + token)
                .uri(URI.create("http://" + host + ":" + port + "/person?page=1"))
                .GET()
                .build();

        HttpResponse<String> responsePage1
                = client.send(requestPage1, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, responsePage1.statusCode());

        Model modelPage1 = ModelFactory.createDefaultModel();
        RDFDataMgr.read(modelPage1, new StringReader(responsePage1.body()), null, Lang.JSONLD);

        modelPage1.write(System.out, "TTL");

        List<Resource> personsPage1
                = modelPage1.listResourcesWithProperty(RDF.type, FOAF.Person).toList();

        assertEquals(5, personsPage1.size(), "Page 1 should contain 5 persons");

        // ---- PAGE 2 ----
        HttpRequest requestPage2 = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + token)
                .uri(URI.create("http://" + host + ":" + port + "/person?page=2"))
                .GET()
                .build();

        HttpResponse<String> responsePage2
                = client.send(requestPage2, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, responsePage2.statusCode());

        Model modelPage2 = ModelFactory.createDefaultModel();
        RDFDataMgr.read(modelPage2, new StringReader(responsePage2.body()), null, Lang.JSONLD);

        modelPage2.write(System.out, "TTL");

        List<Resource> personsPage2
                = modelPage2.listResourcesWithProperty(RDF.type, FOAF.Person).toList();

        assertEquals(5, personsPage2.size(), "Page 2 should contain 5 persons");

        // ---- VERIFY PAGES DIFFER ----
        Set<String> page1Uris = personsPage1.stream()
                .map(Resource::getURI)
                .collect(Collectors.toSet());

        Set<String> page2Uris = personsPage2.stream()
                .map(Resource::getURI)
                .collect(Collectors.toSet());

        page1Uris.retainAll(page2Uris);

        assertTrue(page1Uris.isEmpty(), "Page 1 and Page 2 should not contain the same persons");
    }

    private void createGraph(String host, int port, String token, String graphUri) throws Exception {

        HttpClient client = HttpClient.newHttpClient();

        String url = "http://" + host + ":" + port + "/graph";

        JSONObject payload = new JSONObject();
        payload.put("graph", graphUri);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertTrue(response.statusCode() == 200 || response.statusCode() == 201 || response.statusCode() == 204,
                "Graph creation failed");
    }

    @Test
    public void testGetPersonInGraph() throws Exception {

        String host = appConfig.getHost();
        int port = appConfig.getPort();
        String token = loginAsAdmin();

        String graphUri = "urn:graph:1";
        createGraph(host, port, token, graphUri);

        String encodedGraph = URLEncoder.encode(graphUri, StandardCharsets.UTF_8);

        String url = "http://" + host + ":" + port + "/person?graph=" + encodedGraph;

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + token)
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());

        String body = response.body();
        assertNotNull(body);
        assertFalse(body.isEmpty());

        JSONObject bodyObj = new JSONObject(body);
        System.out.println(bodyObj.toString(2));
    }

    @Test
    public void testPostPersonInGraph() throws Exception {

        String host = appConfig.getHost();
        int port = appConfig.getPort();
        String token = loginAsAdmin();

        String graphUri = "urn:graph:1";
        createGraph(host, port, token, graphUri);

        String encodedGraph = URLEncoder.encode(graphUri, StandardCharsets.UTF_8);

        String url = "http://" + host + ":" + port + "/person?graph=" + encodedGraph;

        String jsonLd = """
    {
      "@context": {
        "name": "http://xmlns.com/foaf/0.1/name",
        "Person": "http://xmlns.com/foaf/0.1/Person"
      },
      "@type": "Person",
      "name": "John Doe"
    }
    """;

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/ld+json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonLd))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertTrue(response.statusCode() == 200 || response.statusCode() == 201 || response.statusCode() == 204);

        String resURI = response.headers().firstValue("Atic-Resource-URI").get();
        assertTrue(resURI.startsWith("urn:atic:"));
    }

    @Test
    public void testPutPersonInGraph() throws Exception {

        String host = appConfig.getHost();
        int port = appConfig.getPort();
        String token = loginAsAdmin();

        HttpClient client = HttpClient.newHttpClient();

        String graphUri = "urn:graph:1";
        String encodedGraph = URLEncoder.encode(graphUri, StandardCharsets.UTF_8);

        // ---- CREATE GRAPH ----
        String graphUrl = "http://" + host + ":" + port + "/graph";

        JSONObject payload = new JSONObject();
        payload.put("graph", graphUri);

        HttpRequest createGraphRequest = HttpRequest.newBuilder()
                .uri(URI.create(graphUrl))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> graphResponse
                = client.send(createGraphRequest, HttpResponse.BodyHandlers.ofString());

        assertTrue(
                graphResponse.statusCode() == 200
                || graphResponse.statusCode() == 201
                || graphResponse.statusCode() == 204
        );

        String collectionUrl
                = "http://" + host + ":" + port + "/person?graph=" + encodedGraph;

        // ---- CREATE PERSON ----
        String jsonLd = """
    {
      "@context": {
        "name": "http://xmlns.com/foaf/0.1/name",
        "Person": "http://xmlns.com/foaf/0.1/Person"
      },
      "@type": "Person",
      "name": "John Doe"
    }
    """;

        HttpRequest postRequest = HttpRequest.newBuilder()
                .uri(URI.create(collectionUrl))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/ld+json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonLd))
                .build();

        HttpResponse<String> postResponse
                = client.send(postRequest, HttpResponse.BodyHandlers.ofString());

        assertTrue(
                postResponse.statusCode() == 200
                || postResponse.statusCode() == 201
                || postResponse.statusCode() == 204
        );

        String location = postResponse.headers().firstValue("Location").get();
        String aticUri = postResponse.headers().firstValue("Atic-Resource-URI").get();

        // ---- VERIFY PERSON ----
        HttpRequest getRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + location))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> getResponse
                = client.send(getRequest, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, getResponse.statusCode());

        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new StringReader(getResponse.body()), null, Lang.JSONLD);

        Resource person = model.getResource(aticUri);
        assertEquals("John Doe", person.getProperty(FOAF.name).getString());

        // ---- UPDATE PERSON ----
        String updatedJsonLd = """
    {
      "@context": {
        "name": "http://xmlns.com/foaf/0.1/name",
        "Person": "http://xmlns.com/foaf/0.1/Person"
      },
      "@id": "%s",
      "@type": "Person",
      "name": "Jane Doe"
    }
    """.formatted(aticUri);

        HttpRequest putRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + location))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/ld+json")
                .PUT(HttpRequest.BodyPublishers.ofString(updatedJsonLd))
                .build();

        HttpResponse<String> putResponse
                = client.send(putRequest, HttpResponse.BodyHandlers.ofString());

        assertTrue(
                putResponse.statusCode() == 200
                || putResponse.statusCode() == 204
        );

        // ---- VERIFY UPDATE ----
        HttpResponse<String> verifyResponse
                = client.send(getRequest, HttpResponse.BodyHandlers.ofString());

        Model updatedModel = ModelFactory.createDefaultModel();
        RDFDataMgr.read(updatedModel, new StringReader(verifyResponse.body()), null, Lang.JSONLD);

        Resource updatedPerson = updatedModel.getResource(aticUri);
        assertEquals("Jane Doe", updatedPerson.getProperty(FOAF.name).getString());
    }

    @Test
    public void testDeletePersonInGraph() throws Exception {

        String host = appConfig.getHost();
        int port = appConfig.getPort();
        String token = loginAsAdmin();

        HttpClient client = HttpClient.newHttpClient();

        String graphUri = "urn:graph:1";
        String encodedGraph = URLEncoder.encode(graphUri, StandardCharsets.UTF_8);

        // ---- CREATE GRAPH ----
        String graphUrl = "http://" + host + ":" + port + "/graph";

        JSONObject payload = new JSONObject();
        payload.put("graph", graphUri);

        HttpRequest createGraphRequest = HttpRequest.newBuilder()
                .uri(URI.create(graphUrl))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> graphResponse
                = client.send(createGraphRequest, HttpResponse.BodyHandlers.ofString());

        assertTrue(
                graphResponse.statusCode() == 200
                || graphResponse.statusCode() == 201
                || graphResponse.statusCode() == 204
        );

        String baseUrl
                = "http://" + host + ":" + port + "/person";
        
        String baseUrlWithGraph = baseUrl + "?graph=" + encodedGraph;

        // ---- CREATE PERSON ----
        String jsonLd = """
    {
      "@context": {
        "name": "http://xmlns.com/foaf/0.1/name",
        "Person": "http://xmlns.com/foaf/0.1/Person"
      },
      "@type": "Person",
      "name": "John Doe"
    }
    """;

        HttpRequest postRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrlWithGraph))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/ld+json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonLd))
                .build();

        HttpResponse<String> postResponse
                = client.send(postRequest, HttpResponse.BodyHandlers.ofString());

        assertTrue(
                postResponse.statusCode() == 200
                || postResponse.statusCode() == 201
                || postResponse.statusCode() == 204
        );

        // ---- GET PERSON LIST ----
        HttpRequest getRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrlWithGraph))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> listResponse
                = client.send(getRequest, HttpResponse.BodyHandlers.ofString());

        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new StringReader(listResponse.body()), null, Lang.JSONLD);

        ResIterator persons = model.listResourcesWithProperty(RDF.type, FOAF.Person);
        assertTrue(persons.hasNext());

        Resource person = persons.next();
        String personUri = person.getURI();

        // ---- DELETE PERSON ----
        String encodedUri = URLEncoder.encode(personUri, StandardCharsets.UTF_8);

        HttpRequest deleteRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + encodedUri + "?graph=" + encodedGraph))
                .header("Authorization", "Bearer " + token)
                .DELETE()
                .build();

        HttpResponse<String> deleteResponse
                = client.send(deleteRequest, HttpResponse.BodyHandlers.ofString());

        assertTrue(
                deleteResponse.statusCode() == 200
                || deleteResponse.statusCode() == 204
        );

        // ---- VERIFY DELETION ----
        HttpResponse<String> verifyResponse
                = client.send(getRequest, HttpResponse.BodyHandlers.ofString());

        Model afterDelete = ModelFactory.createDefaultModel();
        RDFDataMgr.read(afterDelete, new StringReader(verifyResponse.body()), null, Lang.JSONLD);

        boolean exists = afterDelete.contains(
                afterDelete.createResource(personUri),
                RDF.type,
                FOAF.Person
        );

        assertFalse(exists, "Person should have been deleted");
    }

    
    @Test
    public void testPostPersonInGraphDenied() throws Exception {

        String host = appConfig.getHost();
        int port = appConfig.getPort();
        String adminToken = loginAsAdmin();
        String userToken = loginAsUser();

        String graphUri = "urn:graph:1";
        createGraph(host, port, adminToken, graphUri);

        String encodedGraph = URLEncoder.encode(graphUri, StandardCharsets.UTF_8);

        String url = "http://" + host + ":" + port + "/person?graph=" + encodedGraph;

        String jsonLd = """
    {
      "@context": {
        "name": "http://xmlns.com/foaf/0.1/name",
        "Person": "http://xmlns.com/foaf/0.1/Person"
      },
      "@type": "Person",
      "name": "John Doe"
    }
    """;

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + userToken)
                .header("Content-Type", "application/ld+json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonLd))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(403, response.statusCode());
    }

}
