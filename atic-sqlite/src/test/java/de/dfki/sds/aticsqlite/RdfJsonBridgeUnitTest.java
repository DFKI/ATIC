package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.bridge.RdfJsonBridge;
import de.dfki.sds.aticsqlite.bridge.ResultSetJsonMapper;
import io.json.compare.CompareMode;
import io.json.compare.JSONCompare;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.commons.io.IOUtils;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.RDFPatchOps;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class RdfJsonBridgeUnitTest {

    private RdfJsonBridge rdfJsonBridge;
    private SqliteAticDatasetGraph dataset;
    
    private static final boolean PRINT_MODIF = true;

    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);
        rdfJsonBridge = new RdfJsonBridge();
    }

    //=========================
    //query tests
    @Test
    public void personNameList() throws Exception {
        runQueryTest("01_personNameList");
    }

    @Test
    public void personNameListBound() throws Exception {
        runQueryTest("02_personNameListBound");
    }

    @Test
    public void personList() throws Exception {
        runQueryTest("03_personList");
    }

    @Test
    public void personBooleanList() throws Exception {
        runQueryTest("04_personBooleanList");
    }

    @Test
    public void personDateModifier() throws Exception {
        runQueryTest("05_personDateModifier");
    }

    @Test
    public void personDescriptionLang() throws Exception {
        runQueryTest("06_personDescriptionLang");
    }

    @Test
    public void personDescriptionLangToString() throws Exception {
        runQueryTest("07_personDescriptionLangToString");
    }

    @Test
    public void personListNested() throws Exception {
        runQueryTest("08_personListNested");
    }

    @Test
    public void personListValueAsProperty() throws Exception {
        runQueryTest("09_personListValueAsProperty");
    }

    @Test
    public void personListFragment() throws Exception {
        runQueryTest("10_personListFragment");
    }

    @Test
    public void defaultBindings() throws Exception {
        runQueryTest("11_defaultBindings");
    }

    //=========================
    //modification tests
    
    /*
    @Disabled //more difficult
    @Test
    public void personNameListModif() throws Exception {
        runModifTest("01_personNameListDelete");
    }
    */

    @Test
    public void personListPostModif() throws Exception {
        runModifTest("02_personListPost");
    }

    @Test
    public void personListPostWithoutIdModif() throws Exception {
        runModifTest("03_personListPostWithoutId");
    }

    @Test
    public void personListDeleteModif() throws Exception {
        runModifTest("04_personListDelete");
    }

    @Test
    public void personListPatchModif() throws Exception {
        runModifTest("05_personListPatch");
    }
    
    @Test
    public void personListPutModif() throws Exception {
        runModifTest("06_personListPut");
    }
    
    
    //Helper =============================================
    public void runQueryTest(String testName) throws Exception {

        JSONObject test = (JSONObject) loadJSON("query_" + testName + ".json");
        JSONObject template = test.getJSONObject("template");
        Object expected = test.get("expected");
        Map<String, List<String>> queryParams = toMap(test.getJSONObject("params"));
        loadData(test.getString("data"));

        if (test.has("fragmentSettings")) {
            List<ResultSetJsonMapper.FragmentProperty> fragmentSetting = rdfJsonBridge.getFragmentSetting();
            fragmentSetting.clear();
            JSONObject fragmentSettings = test.getJSONObject("fragmentSettings");
            for (String key : fragmentSettings.keySet()) {
                JSONObject setting = fragmentSettings.getJSONObject(key);
                fragmentSetting.add(
                        new ResultSetJsonMapper.FragmentProperty(
                                key,
                                NodeFactory.createURI(setting.getString("uri")),
                                setting.getBoolean("languageAware"))
                );
            }
        }

        User user = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(user).build();

        Object actual = dataset.calculateRead(() -> {
            return rdfJsonBridge.toJson(queryParams, template, dataset, ctx);
        });

        JSONCompare.assertMatches(expected.toString(), actual.toString(),
                Set.of(
                        CompareMode.JSON_ARRAY_NON_EXTENSIBLE,
                        CompareMode.JSON_OBJECT_NON_EXTENSIBLE
                )
        );
    }

    public void runModifTest(String testName) throws Exception {
        JSONObject test = (JSONObject) loadJSON("modif_" + testName + ".json");
        JSONObject template = test.getJSONObject("template");
        JSONArray expected = test.getJSONArray("expected");
        String method = test.getString("method");
        Object payload = test.get("payload");
        Map<String, List<String>> queryParams = toMap(test.getJSONObject("params"));
        loadData(test.getString("data"));

        if (test.has("fragmentSettings")) {
            List<ResultSetJsonMapper.FragmentProperty> fragmentSetting = rdfJsonBridge.getFragmentSetting();
            fragmentSetting.clear();
            JSONObject fragmentSettings = test.getJSONObject("fragmentSettings");
            for (String key : fragmentSettings.keySet()) {
                JSONObject setting = fragmentSettings.getJSONObject(key);
                fragmentSetting.add(
                        new ResultSetJsonMapper.FragmentProperty(
                                key,
                                NodeFactory.createURI(setting.getString("uri")),
                                setting.getBoolean("languageAware"))
                );
            }
        }

        User user = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(user).build();

        AtomicLong uriGenId = new AtomicLong(1);

        Supplier<String> uriSupplier = () -> {
            String uri = "urn:gen-id:" + uriGenId.longValue();
            uriGenId.incrementAndGet();
            return uri;
        };

        RDFPatch actual = dataset.calculateRead(() -> {
            return rdfJsonBridge.toPatch(method, queryParams, payload, template, uriSupplier, dataset, ctx);
        });

        if (PRINT_MODIF) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            RDFPatchOps.write(out, actual);
            String[] lines = out.toString(StandardCharsets.UTF_8)
                    .split("\\R", -1);
            String result = Arrays.stream(lines)
                    .filter(line -> !line.isEmpty())
                    .map(line -> "\"" + line.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                    .collect(Collectors.joining(",\n"));
            System.out.println(result);
        }

        assertEqualPatch(expected, actual);
    }

    private void loadData(String filename) throws IOException {
        InputStream is = RdfJsonBridgeUnitTest.class.getResourceAsStream("/de/dfki/sds/aticsqlite/bridge/" + filename);
        if (is == null) {
            throw new RuntimeException("01_molecule_testdata.ttl not found");
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

    public static Map<String, List<String>> toMap(JSONObject json) {
        return json.keySet().stream()
                .collect(Collectors.toMap(
                        key -> key,
                        key -> {
                            Object value = json.get(key);

                            if (value instanceof JSONArray array) {
                                return IntStream.range(0, array.length())
                                        .mapToObj(array::getString)
                                        .toList();
                            }

                            return List.of(value.toString());
                        }
                ));
    }

    private Object loadJSON(String filename) throws IOException {
        try (InputStream is = RdfJsonBridgeUnitTest.class
                .getResourceAsStream("/de/dfki/sds/aticsqlite/bridge/" + filename)) {

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

    private void assertEqualPatch(JSONArray expected, RDFPatch actual) throws IOException {
        Set<String> expectedSet = IntStream.range(0, expected.length())
                .mapToObj(expected::getString)
                .collect(Collectors.toSet());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RDFPatchOps.write(out, actual);

        Set<String> actualSet = new BufferedReader(
                new InputStreamReader(
                        new ByteArrayInputStream(out.toByteArray()),
                        StandardCharsets.UTF_8))
                .lines()
                .map(String::trim)
                .filter(line -> line.startsWith("A ") || line.startsWith("D "))
                .collect(Collectors.toSet());

        Set<String> missing = new HashSet<>(expectedSet);
        missing.removeAll(actualSet);

        Set<String> extra = new HashSet<>(actualSet);
        extra.removeAll(expectedSet);

        assertTrue(
                missing.isEmpty() && extra.isEmpty(),
                () -> "RDF patch differs:"
                + "\n  Missing: " + missing
                + "\n  Extra:   " + extra
        );
    }
}
