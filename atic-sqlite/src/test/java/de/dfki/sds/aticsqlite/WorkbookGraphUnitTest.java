package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.helper.JSONUtils;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.s16n.ColumnConfig;
import de.dfki.sds.aticsqlite.s16n.ColumnType;
import de.dfki.sds.aticsqlite.s16n.Direction;
import de.dfki.sds.aticsqlite.s16n.SheetConfig;
import de.dfki.sds.aticsqlite.s16n.WorkbookConfig;
import de.dfki.sds.aticsqlite.s16n.WorkbookGraph;
import io.json.compare.CompareMode;
import io.json.compare.JSONCompare;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
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

}
