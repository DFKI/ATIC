package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.helper.JSONUtils;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.s16n.ColumnConfig;
import de.dfki.sds.aticsqlite.s16n.ColumnType;
import de.dfki.sds.aticsqlite.s16n.Direction;
import de.dfki.sds.aticsqlite.s16n.SheetConfig;
import de.dfki.sds.aticsqlite.s16n.Window;
import de.dfki.sds.aticsqlite.s16n.WorkbookConfig;
import de.dfki.sds.aticsqlite.s16n.WorkbookGraph;
import io.json.compare.CompareMode;
import io.json.compare.JSONCompare;
import java.awt.Rectangle;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.apache.commons.io.IOUtils;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class WorkbookGraphUnitTest {

    private SqliteAticDatasetGraph dataset;

    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);
    }

    @Test
    public void initTest() {
        //set to admin
        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });
        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        WorkbookGraph wg = dataset.getWorkbookGraph();

        int size = dataset.calculateRead(() -> {
            return wg.size(ctx);
        });

        Assertions.assertEquals(0, size);
    }

    @Test
    public void addWorkbookTest() {
        User adminUser = dataset.calculateRead(() -> dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY));
        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        WorkbookGraph wg = dataset.getWorkbookGraph();

        WorkbookConfig config = WorkbookConfig.builder().name("Test Workbook").build();

        List<Node> workbooks = dataset.calculateWrite(() -> wg.addWorkbooks(List.of(config), ctx));

        JSONObject expected = new JSONObject().put("name", "Test Workbook");
        JSONObject actual = dataset.calculateRead(() -> wg.getJson(workbooks.get(0), ctx));

        JSONCompare.assertMatches(expected.toString(), actual.toString(),
                Set.of(
                        CompareMode.JSON_ARRAY_NON_EXTENSIBLE,
                        CompareMode.JSON_OBJECT_NON_EXTENSIBLE
                )
        );
    }

    @Test
    public void addSheetsTest() {
        User adminUser = dataset.calculateRead(() -> dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY));
        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        WorkbookGraph wg = dataset.getWorkbookGraph();

        WorkbookConfig workbookConfig = WorkbookConfig.builder().name("Test Workbook").build();
        Node workbook = dataset.calculateWrite(() -> wg.addWorkbooks(List.of(workbookConfig), ctx).get(0));

        SheetConfig sheetConfig = SheetConfig.builder().name("Test Sheet").rowQuery("SELECT * WHERE { ?s ?p ?o }").build();
        List<Node> sheets = dataset.calculateWrite(() -> wg.addSheets(workbook, List.of(sheetConfig), ctx));

        JSONObject expectedSheet = JSONUtils.createJSONObject()
                .put("@id", sheets.get(0).getURI())
                .put("name", "Test Sheet")
                .put("rowQuery", "SELECT * WHERE { ?s ?p ?o }");

        JSONObject expected = JSONUtils.createJSONObject()
                .put("name", "Test Workbook")
                .put("sheets", new JSONArray().put(expectedSheet));

        JSONObject actual = dataset.calculateRead(() -> wg.getJson(workbook, ctx));

        JSONCompare.assertMatches(expected.toString(), actual.toString(),
                Set.of(
                        CompareMode.JSON_ARRAY_NON_EXTENSIBLE,
                        CompareMode.JSON_OBJECT_NON_EXTENSIBLE
                )
        );
    }

    @Test
    public void addColumnsTest() {
        User adminUser = dataset.calculateRead(() -> dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY));
        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        WorkbookGraph wg = dataset.getWorkbookGraph();

        WorkbookConfig workbookConfig = WorkbookConfig.builder().name("Test Workbook").build();
        Node workbook = dataset.calculateWrite(() -> wg.addWorkbooks(List.of(workbookConfig), ctx).get(0));

        SheetConfig sheetConfig = SheetConfig.builder().name("Test Sheet").rowQuery("SELECT * WHERE { ?s ?p ?o }").build();
        Node sheet = dataset.calculateWrite(() -> wg.addSheets(workbook, List.of(sheetConfig), ctx).get(0));

        ColumnConfig columnConfig = ColumnConfig.builder()
                .name("Title")
                .property(NodeFactory.createURI("http://example.org/title"))
                .type(ColumnType.Literal)
                .direction(Direction.Outgoing)
                .build();

        List<Node> columns = dataset.calculateWrite(() -> wg.addColumns(workbook, sheet, List.of(columnConfig), ctx));

        JSONObject expectedColumn = JSONUtils.createJSONObject()
                .put("@id", columns.get(0).getURI())
                .put("name", "Title")
                .put("property", new JSONObject().put("@id", "http://example.org/title"))
                .put("type", ColumnType.Literal.toString())
                .put("direction", Direction.Outgoing.toString());

        JSONObject expectedSheet = JSONUtils.createJSONObject()
                .put("@id", sheet.getURI())
                .put("name", "Test Sheet")
                .put("rowQuery", "SELECT * WHERE { ?s ?p ?o }")
                .put("columns", new JSONArray().put(expectedColumn));

        JSONObject expected = JSONUtils.createJSONObject()
                .put("name", "Test Workbook")
                .put("sheets", new JSONArray().put(expectedSheet));

        JSONObject actual = dataset.calculateRead(() -> wg.getJson(workbook, ctx));

        JSONCompare.assertMatches(expected.toString(), actual.toString(),
                Set.of(
                        CompareMode.JSON_ARRAY_NON_EXTENSIBLE,
                        CompareMode.JSON_OBJECT_NON_EXTENSIBLE
                )
        );
    }

    @Test
    public void removeColumnsAndSheetsTest() {
        User adminUser = dataset.calculateRead(() -> dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY));
        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        WorkbookGraph wg = dataset.getWorkbookGraph();

        WorkbookConfig workbookConfig = WorkbookConfig.builder().name("Test Workbook").build();
        Node workbook = dataset.calculateWrite(() -> wg.addWorkbooks(List.of(workbookConfig), ctx).get(0));

        SheetConfig sheetConfig = SheetConfig.builder().name("Test Sheet").rowQuery("SELECT * WHERE { ?s ?p ?o }").build();
        Node sheet = dataset.calculateWrite(() -> wg.addSheets(workbook, List.of(sheetConfig), ctx).get(0));

        ColumnConfig titleConfig = ColumnConfig.builder().name("Title").property(NodeFactory.createURI("http://example.org/title")).type(ColumnType.Literal).direction(Direction.Outgoing).build();
        ColumnConfig descriptionConfig = ColumnConfig.builder().name("Description").property(NodeFactory.createURI("http://example.org/description")).type(ColumnType.Literal).direction(Direction.Outgoing).build();
        ColumnConfig dateConfig = ColumnConfig.builder().name("Date").property(NodeFactory.createURI("http://example.org/date")).type(ColumnType.Literal).direction(Direction.Outgoing).build();

        List<Node> columns = dataset.calculateWrite(() -> wg.addColumns(workbook, sheet, List.of(titleConfig, descriptionConfig, dateConfig), ctx));

        JSONObject titleColumn = JSONUtils.createJSONObject()
                .put("@id", columns.get(0).getURI())
                .put("name", "Title")
                .put("property", new JSONObject().put("@id", "http://example.org/title"))
                .put("type", ColumnType.Literal.toString())
                .put("direction", Direction.Outgoing.toString());

        JSONObject descriptionColumn = JSONUtils.createJSONObject()
                .put("@id", columns.get(1).getURI())
                .put("name", "Description")
                .put("property", new JSONObject().put("@id", "http://example.org/description"))
                .put("type", ColumnType.Literal.toString())
                .put("direction", Direction.Outgoing.toString());

        JSONObject dateColumn = JSONUtils.createJSONObject()
                .put("@id", columns.get(2).getURI())
                .put("name", "Date")
                .put("property", new JSONObject().put("@id", "http://example.org/date"))
                .put("type", ColumnType.Literal.toString())
                .put("direction", Direction.Outgoing.toString());

        JSONObject expectedSheet = JSONUtils.createJSONObject()
                .put("@id", sheet.getURI())
                .put("name", "Test Sheet")
                .put("rowQuery", "SELECT * WHERE { ?s ?p ?o }")
                .put("columns", new JSONArray().put(titleColumn).put(descriptionColumn).put(dateColumn));

        JSONObject expected = JSONUtils.createJSONObject()
                .put("name", "Test Workbook")
                .put("sheets", new JSONArray().put(expectedSheet));

        JSONObject actual = dataset.calculateRead(() -> wg.getJson(workbook, ctx));
        JSONCompare.assertMatches(expected.toString(), actual.toString(), Set.of(CompareMode.JSON_ARRAY_NON_EXTENSIBLE, CompareMode.JSON_OBJECT_NON_EXTENSIBLE));

        dataset.executeWrite(() -> wg.removeColumns(workbook, sheet, Set.of(columns.get(1)), ctx));

        expectedSheet.put("columns", new JSONArray().put(titleColumn).put(dateColumn));
        expected = JSONUtils.createJSONObject()
                .put("name", "Test Workbook")
                .put("sheets", new JSONArray().put(expectedSheet));

        actual = dataset.calculateRead(() -> wg.getJson(workbook, ctx));
        JSONCompare.assertMatches(expected.toString(), actual.toString(), Set.of(CompareMode.JSON_ARRAY_NON_EXTENSIBLE, CompareMode.JSON_OBJECT_NON_EXTENSIBLE));

        dataset.executeWrite(() -> wg.removeSheets(workbook, Set.of(sheet), ctx));

        expected = JSONUtils.createJSONObject()
                .put("name", "Test Workbook")
                .put("sheets", new JSONArray());

        actual = dataset.calculateRead(() -> wg.getJson(workbook, ctx));
        JSONCompare.assertMatches(expected.toString(), actual.toString(), Set.of(CompareMode.JSON_ARRAY_NON_EXTENSIBLE, CompareMode.JSON_OBJECT_NON_EXTENSIBLE));
    }

    @Test
    public void testWindow() throws IOException {
        loadData("data_01_bridge_persons.ttl");

        User adminUser = dataset.calculateRead(() -> dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY));
        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        WorkbookGraph wg = dataset.getWorkbookGraph();

        WorkbookConfig workbookConfig = WorkbookConfig.builder().name("Person Workbook").build();
        Node workbook = dataset.calculateWrite(() -> wg.addWorkbooks(List.of(workbookConfig), ctx).get(0));

        SheetConfig sheetConfig = SheetConfig.builder().name("Person Sheet").rowQuery(WorkbookGraph.ROW_ENTITY_VAR + " a <https://schema.org/Person>").build();
        Node sheet = dataset.calculateWrite(() -> wg.addSheets(workbook, List.of(sheetConfig), ctx).get(0));

        ColumnConfig nameConfig = ColumnConfig.builder().name("Name").property(NodeFactory.createURI("http://xmlns.com/foaf/0.1/name")).type(ColumnType.Literal).direction(Direction.Outgoing).build();
        ColumnConfig givenNameConfig = ColumnConfig.builder().name("Given Name").property(NodeFactory.createURI("https://schema.org/givenName")).type(ColumnType.Literal).direction(Direction.Outgoing).build();
        ColumnConfig familyNameConfig = ColumnConfig.builder().name("Family Name").property(NodeFactory.createURI("https://schema.org/familyName")).type(ColumnType.Literal).direction(Direction.Outgoing).build();
        ColumnConfig emailConfig = ColumnConfig.builder().name("Email").property(NodeFactory.createURI("https://schema.org/email")).type(ColumnType.Literal).direction(Direction.Outgoing).build();
        ColumnConfig birthDateConfig = ColumnConfig.builder().name("Birth Date").property(NodeFactory.createURI("https://schema.org/birthDate")).type(ColumnType.Literal).direction(Direction.Outgoing).datatype(NodeFactory.createURI("http://www.w3.org/2001/XMLSchema#date")).build();
        ColumnConfig genderConfig = ColumnConfig.builder().name("Gender").property(NodeFactory.createURI("https://schema.org/gender")).type(ColumnType.Literal).direction(Direction.Outgoing).build();
        ColumnConfig nationalityConfig = ColumnConfig.builder().name("Nationality").property(NodeFactory.createURI("https://schema.org/nationality")).type(ColumnType.Literal).direction(Direction.Outgoing).build();
        ColumnConfig activatedConfig = ColumnConfig.builder().name("Activated").property(NodeFactory.createURI("https://schema.org/activated")).type(ColumnType.Literal).direction(Direction.Outgoing).build();
        ColumnConfig descriptionConfig = ColumnConfig.builder().name("Description").property(NodeFactory.createURI("https://schema.org/description")).type(ColumnType.Literal).direction(Direction.Outgoing).build();
        ColumnConfig knowsConfig = ColumnConfig.builder().name("Knows").property(NodeFactory.createURI("https://schema.org/knows")).type(ColumnType.Literal).direction(Direction.Outgoing).build();
        ColumnConfig createdConfig = ColumnConfig.builder().name("Created").property(NodeFactory.createURI("http://purl.org/dc/terms/created")).type(ColumnType.Literal).direction(Direction.Outgoing).datatype(NodeFactory.createURI("http://www.w3.org/2001/XMLSchema#dateTime")).build();
        ColumnConfig modifiedConfig = ColumnConfig.builder().name("Modified").property(NodeFactory.createURI("http://purl.org/dc/terms/modified")).type(ColumnType.Literal).direction(Direction.Outgoing).datatype(NodeFactory.createURI("http://www.w3.org/2001/XMLSchema#dateTime")).build();

        List<Node> columns = dataset.calculateWrite(() -> wg.addColumns(workbook, sheet, List.of(nameConfig, givenNameConfig, familyNameConfig, emailConfig, birthDateConfig, genderConfig, nationalityConfig, activatedConfig, descriptionConfig, knowsConfig, createdConfig, modifiedConfig), ctx));

        //dataset.executeRead(() -> {
        //   System.out.println(wg.getJson(workbook, ctx).toString(4));
        //});
        
        Window window = dataset.calculateRead(() -> wg.get(workbook, sheet, new Rectangle(0, 0, 5, 5), ctx));
        
        System.out.println(window.toJson().toString(4));
    }

    private void loadData(String filename) throws IOException {
        InputStream is = RdfJsonBridgeUnitTest.class.getResourceAsStream("/de/dfki/sds/aticsqlite/bridge/" + filename);
        if (is == null) {
            throw new RuntimeException(filename + " not found");
        }
        String ttl = IOUtils.toString(is, StandardCharsets.UTF_8);

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ictx = new InvocationContext.Builder().fromUser(adminUser).build();

        ictx.transferContext(dataset.getContext());

        // Read TTL into graph
        dataset.executeWrite(() -> {
            RDFDataMgr.read(
                    dataset,
                    new StringReader(ttl),
                    null,
                    Lang.TURTLE
            );
        });
    }
}
