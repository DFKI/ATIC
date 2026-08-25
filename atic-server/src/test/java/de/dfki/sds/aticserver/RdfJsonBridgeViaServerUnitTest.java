package de.dfki.sds.aticserver;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.conf.ConfigLoader;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
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
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.RDFPatchOps;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class RdfJsonBridgeViaServerUnitTest {

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

    @Test
    public void query() throws Exception {
        String ttlCode = """
        @prefix ex: <https://example.org/id/> .
        @prefix schema: <https://schema.org/> .
        @prefix foaf: <http://xmlns.com/foaf/0.1/> .
        @prefix dcterms: <http://purl.org/dc/terms/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>.
        
        ex:alice-smith
            a schema:Person ;
            foaf:name "Alice Smith" ;
            schema:email "alice.smith@example.org" .
            
        ex:bob-johnson
            a schema:Person ;
            foaf:name "Bob Johnson" ;
            schema:email "bob.johnson@example.org" .
            
        ex:carol-miller
            a schema:Person ;
            foaf:name "Carol Miller" ;
            schema:email "carol.miller@example.org" .
                         """;

        JSONObject template = new JSONObject("""
        {
            "$type": "array",
            "$map": {
                "@id": "?person",
                "name": "?name",
                "mail": "?em"
            },
            "$where": [
                "?person foaf:name ?name",
                "?person schema:email ?em"
            ],
            "@context": {
                "foaf": "http://xmlns.com/foaf/0.1/",
                "schema": "https://schema.org/"
            }
        }
        """);

        Map<String, List<String>> queryParams = Map.of();

        helperQuery(ttlCode, template, queryParams);
    }

    @Test
    public void post() throws Exception {
        String ttlCode = """
                         
                         """;
        
        JSONObject template = new JSONObject("""
        {
            "$type": "array",
            "$map": {
                "@id": "?person",
                "name": "?name",
                "mail": "?em"
            },
            "$where": [
                "?person foaf:name ?name",
                "?person schema:email ?em"
            ],
            "@context": {
                "foaf": "http://xmlns.com/foaf/0.1/",
                "schema": "https://schema.org/"
            }
        }
        """);
        
        JSONArray data = new JSONArray("""
        [
            {
                "mail": "david.dean@example.org",
                "name": "David Dean",
                "@id": "https://example.org/id/david-dean"
            }
        ]
        """);

        Map<String, List<String>> queryParams = Map.of();
        
        helperModification("POST", ttlCode, template, data, queryParams);
    }

    @Test
    public void put() throws Exception {
        String ttlCode = """
        @prefix ex: <https://example.org/id/> .
        @prefix schema: <https://schema.org/> .
        @prefix foaf: <http://xmlns.com/foaf/0.1/> .
        @prefix dcterms: <http://purl.org/dc/terms/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                         
        ex:alice-smith
            a schema:Person ;
            foaf:name "Alice Smith" ;
            schema:email "alice.smith@example.org" .

        ex:bob-johnson
            a schema:Person ;
            foaf:name "Bob Johnson" ;
            schema:email "bob.johnson@example.org" .
                         """;
        
        JSONObject template = new JSONObject("""
        {
            "$type": "array",
            "$map": {
                "@id": "?person",
                "name": "?name",
                "mail": "?em"
            },
            "$where": [
                "?person a schema:Person",
                "?person foaf:name ?name",
                "?person schema:email ?em"
            ],
            "@context": {
                "foaf": "http://xmlns.com/foaf/0.1/",
                "schema": "https://schema.org/"
            }
        }
        """);
        
        JSONArray data = new JSONArray("""
        [
            {
                "mail": "david.dean@example.org",
                "name": "David Dean",
                "@id": "https://example.org/id/david-dean"
            }
        ]
        """);

        Map<String, List<String>> queryParams = Map.of();
        
        helperModification("PUT", ttlCode, template, data, queryParams);
    }

    @Test
    public void patch() throws Exception {
        String ttlCode = """
        @prefix ex: <https://example.org/id/> .
        @prefix schema: <https://schema.org/> .
        @prefix foaf: <http://xmlns.com/foaf/0.1/> .
        @prefix dcterms: <http://purl.org/dc/terms/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                         
        ex:alice-smith
            a schema:Person ;
            foaf:name "Alice Smith" ;
            schema:email "alice.smith@example.org" .

        ex:bob-johnson
            a schema:Person ;
            foaf:name "Bob Johnson" ;
            schema:email "bob.johnson@example.org" .
                         """;
        
        JSONObject template = new JSONObject("""
        {
            "$type": "array",
            "$map": {
                "@id": "?person",
                "name": "?name",
                "mail": "?em"
            },
            "$where": [
                "?person a schema:Person",
                "?person foaf:name ?name",
                "?person schema:email ?em"
            ],
            "@context": {
                "foaf": "http://xmlns.com/foaf/0.1/",
                "schema": "https://schema.org/"
            }
        }
        """);
        
        JSONArray data = new JSONArray("""
        [
            {
                "name": "Alice Dean",
                "@id": "https://example.org/id/alice-smith"
            }
        ]
        """);

        Map<String, List<String>> queryParams = Map.of();
        
        helperModification("PATCH", ttlCode, template, data, queryParams);
    }

    @Test
    public void deleteNotExist() throws Exception {
        String ttlCode = """
                         
                         """;
        
        JSONObject template = new JSONObject("""
        {
            "$type": "array",
            "$map": {
                "@id": "?person",
                "name": "?name",
                "mail": "?em"
            },
            "$where": [
                "?person foaf:name ?name",
                "?person schema:email ?em"
            ],
            "@context": {
                "foaf": "http://xmlns.com/foaf/0.1/",
                "schema": "https://schema.org/"
            }
        }
        """);
        
        JSONArray data = new JSONArray("""
        [
            {
                "mail": "david.dean@example.org",
                "name": "David Dean",
                "@id": "https://example.org/id/david-dean"
            }
        ]
        """);

        Map<String, List<String>> queryParams = Map.of();
        
        helperModification("DELETE", ttlCode, template, data, queryParams);
    }

    @Test
    public void delete() throws Exception {
        String ttlCode = """
        @prefix ex: <https://example.org/id/> .
        @prefix schema: <https://schema.org/> .
        @prefix foaf: <http://xmlns.com/foaf/0.1/> .
        @prefix dcterms: <http://purl.org/dc/terms/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>.

        ex:david-dean
            a schema:Person ;
            foaf:name "David Dean" ;
            schema:email "david.dean@example.org" .
        """;
        
        JSONObject template = new JSONObject("""
        {
            "$type": "array",
            "$map": {
                "@id": "?person",
                "name": "?name",
                "mail": "?em"
            },
            "$where": [
                "?person foaf:name ?name",
                "?person schema:email ?em"
            ],
            "@context": {
                "foaf": "http://xmlns.com/foaf/0.1/",
                "schema": "https://schema.org/"
            }
        }
        """);
        
        JSONArray data = new JSONArray("""
        [
            {
                "mail": "david.dean@example.org",
                "name": "David Dean",
                "@id": "https://example.org/id/david-dean"
            }
        ]
        """);

        Map<String, List<String>> queryParams = Map.of();
        
        helperModification("DELETE", ttlCode, template, data, queryParams);
    }
    
    //=================================================
    //helper
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

    private void loadData(String ttlCode) throws IOException {

        User adminUser = datasetGraph.calculateRead(() -> {
            return datasetGraph.getUser(
                    UserGroupManagement.ADMIN_USERNAME,
                    InvocationContext.EMPTY
            );
        });

        InvocationContext ictx
                = new InvocationContext.Builder()
                        .fromUser(adminUser)
                        .build();

        AticServer.transferContext(
                ictx,
                datasetGraph.getContext()
        );

        // Read TTL code into the dataset graph.
        // RDFDataMgr supports reading Turtle from a StringReader. :contentReference[oaicite:0]{index=0}
        datasetGraph.executeWrite(() -> {
            RDFDataMgr.read(
                    datasetGraph,
                    new StringReader(ttlCode),
                    null,
                    Lang.TURTLE
            );
        });
    }

    private void helperQuery(String ttlCode, JSONObject template, Map<String, List<String>> queryParams) throws Exception {

        loadData(ttlCode);

        String token = loginAsAdmin();

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(bridgeUri(queryParams))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method(
                        "QUERY",
                        HttpRequest.BodyPublishers.ofString(
                                template.toString()
                        )
                )
                .build();

        HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        if (String.valueOf(response.statusCode()).startsWith("2")) {

            String body = response.body();
            assertNotNull(body);

            Object actualBody = new JSONTokener(body).nextValue();

            if (actualBody instanceof JSONObject object) {
                System.out.println(object.toString(2));
            } else if (actualBody instanceof JSONArray array) {
                System.out.println(array.toString(2));
            } else if (actualBody instanceof String str) {
                System.out.println(str);
            }

            return;

        } else {
            Assertions.fail(response.toString());
        }
    }

    private void helperModification(String method, String ttlCode, JSONObject template, Object data, Map<String, List<String>> queryParams) throws Exception {
        loadData(ttlCode);

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
                        method,
                        HttpRequest.BodyPublishers.ofString(
                                requestJson.toString(2)
                        )
                )
                .build();

        HttpResponse<String> response = client.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        RDFPatch patch;

        try (InputStream in = new ByteArrayInputStream(
                response.body().getBytes(StandardCharsets.UTF_8)
        )) {
            patch = RDFPatchOps.read(in);
        }

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
