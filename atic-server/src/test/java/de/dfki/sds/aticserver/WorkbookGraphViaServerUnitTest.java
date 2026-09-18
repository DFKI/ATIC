package de.dfki.sds.aticserver;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.conf.ConfigLoader;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import de.dfki.sds.aticsqlite.s16n.Cell;
import de.dfki.sds.aticsqlite.s16n.ColumnConfig;
import de.dfki.sds.aticsqlite.s16n.ColumnType;
import de.dfki.sds.aticsqlite.s16n.Direction;
import de.dfki.sds.aticsqlite.s16n.SheetConfig;
import de.dfki.sds.aticsqlite.s16n.WorkbookConfig;
import de.dfki.sds.aticsqlite.s16n.WorkbookGraph;
import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;
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
import java.util.StringJoiner;
import org.apache.commons.io.FileUtils;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class WorkbookGraphViaServerUnitTest {

    private static Path tempDir;
    private static AticConfig appConfig;
    private static AticServer server;
    private static SqliteAticDatasetGraph datasetGraph;

    private static final boolean PRINT = false;

    @BeforeEach
    public void setUp() throws Exception {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");

        // Create temp directory
        tempDir = Files.createTempDirectory("workbook-test-");

        //System.out.println(tempDir);
        // Set as working directory
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());

        String[] args = new String[]{
            "--home", tempDir.toAbsolutePath().toString(),
            "--no-print.log"
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
    public void createWorkbookAndGetItsJson() throws IOException, InterruptedException {
        String token = loginAsAdmin();
        HttpClient client = HttpClient.newHttpClient();

        JSONObject workbookJson = WorkbookConfig.builder().name("My Workbook").build().toJson();
        JSONArray workbooks = new JSONArray().put(workbookJson);

        HttpRequest postRequest = HttpRequest.newBuilder()
                .uri(createUri(null, null, false, null))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(workbooks.toString()))
                .build();

        HttpResponse<String> postResponse = client.send(postRequest, HttpResponse.BodyHandlers.ofString());

        Assertions.assertEquals(200, postResponse.statusCode());

        JSONArray createdWorkbooks = new JSONArray(postResponse.body());
        Assertions.assertEquals(1, createdWorkbooks.length());

        String workbookUri = createdWorkbooks.getString(0);

        HttpRequest getRequest = HttpRequest.newBuilder()
                .uri(createUri(workbookUri, null, false, null))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> getResponse = client.send(getRequest, HttpResponse.BodyHandlers.ofString());

        Assertions.assertEquals(200, getResponse.statusCode());

        JSONObject actual = new JSONObject(getResponse.body());

        Assertions.assertEquals(workbookJson.toString(), actual.toString());
    }

    @Test
    public void createWorkbookAndGetCells() throws IOException, InterruptedException {
        String token = loginAsAdmin();
        HttpClient client = HttpClient.newHttpClient();

        loadData("""
            @prefix foaf: <http://xmlns.com/foaf/0.1/> .

            <http://example.org/alice> a foaf:Person ;
                foaf:name "Alice" .

            <http://example.org/bob> a foaf:Person ;
                foaf:name "Bob" .
            """);

        JSONObject workbookJson = WorkbookConfig.builder().name("People").build().toJson();
        HttpRequest postWorkbookRequest = HttpRequest.newBuilder()
                .uri(createUri(null, null, false, null))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new JSONArray().put(workbookJson).toString()))
                .build();

        HttpResponse<String> postWorkbookResponse = client.send(postWorkbookRequest, HttpResponse.BodyHandlers.ofString());

        Assertions.assertEquals(200, postWorkbookResponse.statusCode());

        String workbookUri = new JSONArray(postWorkbookResponse.body()).getString(0);

        SheetConfig sheetConfig = SheetConfig.builder()
                .name("People")
                .rowQuery(WorkbookGraph.ROW_ENTITY_VAR + " a <http://xmlns.com/foaf/0.1/Person>")
                .build();

        HttpRequest postSheetRequest = HttpRequest.newBuilder()
                .uri(createUri(workbookUri, "", false, null))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new JSONArray().put(sheetConfig.toJson()).toString()))
                .build();

        HttpResponse<String> postSheetResponse = client.send(postSheetRequest, HttpResponse.BodyHandlers.ofString());

        Assertions.assertEquals(200, postSheetResponse.statusCode());

        String sheetUri = new JSONArray(postSheetResponse.body()).getString(0);

        ColumnConfig columnConfig = ColumnConfig.builder()
                .name("Name")
                .property(NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"))
                .type(ColumnType.Literal)
                .direction(Direction.Outgoing)
                .build();

        HttpRequest postColumnRequest = HttpRequest.newBuilder()
                .uri(createUri(workbookUri, sheetUri, false, null))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new JSONArray().put(columnConfig.toJson()).toString()))
                .build();

        HttpResponse<String> postColumnResponse = client.send(postColumnRequest, HttpResponse.BodyHandlers.ofString());

        Assertions.assertEquals(200, postColumnResponse.statusCode());

        JSONArray columns = new JSONArray(postColumnResponse.body());
        Assertions.assertEquals(1, columns.length());

        HttpRequest getCellsRequest = HttpRequest.newBuilder()
                .uri(createUri(workbookUri, sheetUri, true, new Rectangle(0, 0, 1, 2)))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> getCellsResponse = client.send(getCellsRequest, HttpResponse.BodyHandlers.ofString());

        Assertions.assertEquals(200, getCellsResponse.statusCode());

        JSONArray rows = new JSONArray(getCellsResponse.body());

        Assertions.assertEquals(2, rows.length());
        Assertions.assertEquals(1, rows.getJSONArray(0).length());
        Assertions.assertEquals(1, rows.getJSONArray(1).length());

        Cell firstCell = Cell.fromJson(rows.getJSONArray(0).getJSONObject(0));
        Cell secondCell = Cell.fromJson(rows.getJSONArray(1).getJSONObject(0));

        Assertions.assertEquals("Alice", firstCell.getNodes().get(0).getLiteralLexicalForm());
        Assertions.assertEquals("Bob", secondCell.getNodes().get(0).getLiteralLexicalForm());
    }

    @Test
    public void createWorkbookAndSetCells() throws IOException, InterruptedException {
        String token = loginAsAdmin();
        HttpClient client = HttpClient.newHttpClient();

        loadData("""
        @prefix foaf: <http://xmlns.com/foaf/0.1/> .

        <http://example.org/alice> a foaf:Person ;
            foaf:name "Alice" .

        <http://example.org/bob> a foaf:Person ;
            foaf:name "Bob" .
        """);

        JSONObject workbookJson = WorkbookConfig.builder().name("People").build().toJson();
        HttpRequest postWorkbookRequest = HttpRequest.newBuilder()
                .uri(createUri(null, null, false, null))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new JSONArray().put(workbookJson).toString()))
                .build();

        HttpResponse<String> postWorkbookResponse = client.send(postWorkbookRequest, HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, postWorkbookResponse.statusCode());

        String workbookUri = new JSONArray(postWorkbookResponse.body()).getString(0);

        SheetConfig sheetConfig = SheetConfig.builder()
                .name("People")
                .rowQuery(WorkbookGraph.ROW_ENTITY_VAR + " a <http://xmlns.com/foaf/0.1/Person>")
                .build();

        HttpRequest postSheetRequest = HttpRequest.newBuilder()
                .uri(createUri(workbookUri, "", false, null))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new JSONArray().put(sheetConfig.toJson()).toString()))
                .build();

        HttpResponse<String> postSheetResponse = client.send(postSheetRequest, HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, postSheetResponse.statusCode());

        String sheetUri = new JSONArray(postSheetResponse.body()).getString(0);

        ColumnConfig columnConfig = ColumnConfig.builder()
                .name("Name")
                .property(NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"))
                .type(ColumnType.Literal)
                .direction(Direction.Outgoing)
                .build();

        HttpRequest postColumnRequest = HttpRequest.newBuilder()
                .uri(createUri(workbookUri, sheetUri, false, null))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new JSONArray().put(columnConfig.toJson()).toString()))
                .build();

        HttpResponse<String> postColumnResponse = client.send(postColumnRequest, HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, postColumnResponse.statusCode());

        Assertions.assertEquals(1, new JSONArray(postColumnResponse.body()).length());

        Cell cell = Cell.builder()
                .nodes(List.of(NodeFactory.createLiteralString("Same Name")))
                .build();

        HttpRequest setRequest = HttpRequest.newBuilder()
                .uri(createUri(workbookUri, sheetUri, true, new Rectangle(0, 0, 1, 2)))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(cell.toJson().toString()))
                .build();

        HttpResponse<String> setResponse = client.send(setRequest, HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(204, setResponse.statusCode());

        HttpRequest getCellsRequest = HttpRequest.newBuilder()
                .uri(createUri(workbookUri, sheetUri, true, new Rectangle(0, 0, 1, 2)))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> getCellsResponse = client.send(getCellsRequest, HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, getCellsResponse.statusCode());

        JSONArray rows = new JSONArray(getCellsResponse.body());
        Assertions.assertEquals(2, rows.length());
        Assertions.assertEquals(1, rows.getJSONArray(0).length());
        Assertions.assertEquals(1, rows.getJSONArray(1).length());

        Cell firstCell = Cell.fromJson(rows.getJSONArray(0).getJSONObject(0));
        Cell secondCell = Cell.fromJson(rows.getJSONArray(1).getJSONObject(0));

        Assertions.assertEquals("Same Name", firstCell.getNodes().get(0).getLiteralLexicalForm());
        Assertions.assertEquals("Same Name", secondCell.getNodes().get(0).getLiteralLexicalForm());
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

    private URI createUri(String workbookUri, String sheetUri, boolean isCells, Rectangle window) {
        String host = appConfig.getHost();
        int port = appConfig.getPort();

        StringBuilder path = new StringBuilder("/workbooks");

        if (workbookUri != null) {
            if (!workbookUri.isEmpty()) {
                path.append("/").append(URLEncoder.encode(workbookUri, StandardCharsets.UTF_8));
            }
            if (sheetUri != null) {
                path.append("/sheets");
                if (!sheetUri.isEmpty()) {
                    path.append("/").append(URLEncoder.encode(sheetUri, StandardCharsets.UTF_8));
                }
                if (!sheetUri.isEmpty()) {
                    path.append(isCells ? "/cells" : "/columns");
                }
            }
        }

        StringJoiner joiner = new StringJoiner("&");
        if (window != null) {
            joiner.add("window=" + window.x + "," + window.y + "," + window.width + "," + window.height);
        }

        return URI.create("http://" + host + ":" + port + path + (joiner.length() == 0 ? "" : "?" + joiner));
    }
}
