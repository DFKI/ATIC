package de.dfki.sds.aticserver;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.conf.ConfigLoader;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticserver.bridge.ResultSetJsonMapper;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import io.json.compare.CompareMode;
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
import java.util.Set;
import java.util.StringJoiner;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.RDFPatchOps;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.vocabulary.FOAF;
import org.apache.jena.vocabulary.RDF;
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
    
    @Test
    public void personList() throws Exception {
        helperQuery("03_bridge_persons.ttl", "bridge_03_personList.json", Map.of());
    }

    @Test
    public void personBooleanList() throws Exception {
        helperQuery("03_bridge_persons.ttl", "bridge_04_personBooleanList.json", Map.of());
    }
    
    @Test
    public void personDateModifier() throws Exception {
        helperQuery("03_bridge_persons.ttl", "bridge_05_personDateModifier.json", Map.of());
    }
    
    @Test
    public void personDescriptionLang() throws Exception {
        helperQuery("03_bridge_persons.ttl", "bridge_06_personDescriptionLang.json", Map.of());
    }
    
    @Test
    public void personDescriptionLangToString() throws Exception {
        helperQuery("03_bridge_persons.ttl", "bridge_07_personDescriptionLangToString.json", Map.of());
    }

    @Test
    public void personListNested() throws Exception {
        helperQuery("03_bridge_persons.ttl", "bridge_08_personListNested.json", Map.of());
    }
    
    @Test
    public void personListValueAsProperty() throws Exception {
        helperQuery("03_bridge_persons.ttl", "bridge_09_personListValueAsProperty.json", Map.of());
    }

    @Test
    public void personListFragment() throws Exception {
        List<ResultSetJsonMapper.FragmentProperty> fragmentSetting = server.getRdfJsonBridge().getFragmentSetting();
        fragmentSetting.clear();
        fragmentSetting.add(new ResultSetJsonMapper.FragmentProperty("@type", RDF.type.asNode(), false));
        fragmentSetting.add(new ResultSetJsonMapper.FragmentProperty("icon", NodeFactory.createURI("https://schema.org/icon"), false));
        fragmentSetting.add(new ResultSetJsonMapper.FragmentProperty("label", FOAF.name.asNode(), true));
        fragmentSetting.add(new ResultSetJsonMapper.FragmentProperty("comment", NodeFactory.createURI("https://schema.org/description"), true));
        
        helperQuery("03_bridge_persons.ttl", "bridge_10_personListFragment.json", Map.of());
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

        if (String.valueOf(response.statusCode()).startsWith("2")) {
            String body = response.body();
            assertNotNull(body);

            Object actualBody = new JSONTokener(body).nextValue();
            
            if(actualBody instanceof JSONObject object) {
                System.out.println(object.toString(2));
            } else if(actualBody instanceof JSONArray array) {
                System.out.println(array.toString(2));
            } else if(actualBody instanceof String str) {
                System.out.println(str);
            }

            Object expectedBody = loadJSON(templFilename.replace(".json", "-expected.json"));

            JSONCompare.assertMatches(expectedBody.toString(), actualBody.toString(), Set.of(CompareMode.JSON_ARRAY_NON_EXTENSIBLE, CompareMode.JSON_OBJECT_NON_EXTENSIBLE));

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
