package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.bridge.RdfJsonBridge;
import de.dfki.sds.aticsqlite.bridge.ResultSetJsonMapper;
import io.json.compare.CompareMode;
import io.json.compare.JSONCompare;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.commons.io.IOUtils;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class RdfJsonBridgeUnitTest {

    private RdfJsonBridge rdfJsonBridge;
    private SqliteAticDatasetGraph dataset;

    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);
        rdfJsonBridge = new RdfJsonBridge();
    }

    @Test
    public void personNameList() throws Exception {
        runTest("01_personNameList");
    }
    
    @Test
    public void personNameListBound() throws Exception {
        runTest("02_personNameListBound");
    }
    
    @Test
    public void personList() throws Exception {
        runTest("03_personList");
    }
    
    @Test
    public void personBooleanList() throws Exception {
        runTest("04_personBooleanList");
    }
    
    @Test
    public void personDateModifier() throws Exception {
        runTest("05_personDateModifier");
    }
    
    @Test
    public void personDescriptionLang() throws Exception {
        runTest("06_personDescriptionLang");
    }
    
    @Test
    public void personDescriptionLangToString() throws Exception {
        runTest("07_personDescriptionLangToString");
    }
    
    @Test
    public void personListNested() throws Exception {
        runTest("08_personListNested");
    }
    
    @Test
    public void personListValueAsProperty() throws Exception {
        runTest("09_personListValueAsProperty");
    }
    
    @Test
    public void personListFragment() throws Exception {
        runTest("10_personListFragment");
    }
    
    @Test
    public void defaultBindings() throws Exception {
        runTest("11_defaultBindings");
    }

    //Helper =============================================
    
    
    public void runTest(String testName) throws Exception {

        JSONObject test = (JSONObject) loadJSON("bridge_" + testName + ".json");
        JSONObject template = test.getJSONObject("template");
        Object expected = test.get("expected");
        Map<String, List<String>> queryParams = toMap(test.getJSONObject("params"));
        loadData(test.getString("data"));
        
        if(test.has("fragmentSettings")) {
            List<ResultSetJsonMapper.FragmentProperty> fragmentSetting = rdfJsonBridge.getFragmentSetting();
            fragmentSetting.clear();
            JSONObject fragmentSettings = test.getJSONObject("fragmentSettings");
            for(String key : fragmentSettings.keySet()) {
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

}
