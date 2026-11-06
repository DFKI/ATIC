package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.helper.JSONUtils;
import java.util.LinkedHashMap;
import org.json.JSONObject;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class JSONUtilsUnitTest {

    @Test
    void testParseOrderedPreservesAlphabeticalKeyOrder() {
        // Build JSON input with keys a through z
        StringBuilder json = new StringBuilder("{");

        for (char key = 'a'; key <= 'z'; key++) {
            if (key > 'a') {
                json.append(",");
            }

            json.append("\"")
                    .append(key)
                    .append("\":")
                    .append("\"value-")
                    .append(key)
                    .append("\"");
        }

        json.append("}");

        // Parse using parseOrdered
        JSONObject obj = JSONUtils.parseOrdered(json.toString());

        // Expected pretty-printed JSON
        StringBuilder expected = new StringBuilder("{\n");

        for (char key = 'a'; key <= 'z'; key++) {
            expected.append("  \"")
                    .append(key)
                    .append("\": \"value-")
                    .append(key)
                    .append("\"");

            if (key < 'z') {
                expected.append(",");
            }

            expected.append("\n");
        }

        expected.append("}");

        // Verify ordering and formatting
        assertEquals(expected.toString(), obj.toString(2));

        // Additionally verify iteration order
        String actual = obj.toString(2);
        int previousIndex = -1;

        for (char key = 'a'; key <= 'z'; key++) {
            int currentIndex = actual.indexOf("\"" + key + "\"");

            assertTrue(
                    currentIndex > previousIndex,
                    "Key '" + key + "' is not in the expected order"
            );

            previousIndex = currentIndex;
        }
    }

    @Test
    void testParseOrderedPreservesNestedKeyOrder() {
        // Build nested JSON input
        StringBuilder json = new StringBuilder("{\"obj\":{");

        for (char key = 'a'; key <= 'z'; key++) {
            if (key > 'a') {
                json.append(",");
            }

            json.append("\"")
                    .append(key)
                    .append("\":")
                    .append("\"value-")
                    .append(key)
                    .append("\"");
        }

        json.append("}}");

        // Parse using parseOrdered
        JSONObject parsed = JSONUtils.parseOrdered(json.toString());

        JSONObject nested = parsed.getJSONObject("obj");

        // Build expected pretty-printed JSON
        StringBuilder expected = new StringBuilder("{\n");
        expected.append("  \"obj\": {\n");

        for (char key = 'a'; key <= 'z'; key++) {
            expected.append("    \"")
                    .append(key)
                    .append("\": \"value-")
                    .append(key)
                    .append("\"");

            if (key < 'z') {
                expected.append(",");
            }

            expected.append("\n");
        }

        expected.append("  }\n");
        expected.append("}");

        // Verify nested key order independently
        String actual = nested.toString(2);
        int previousIndex = -1;

        for (char key = 'a'; key <= 'z'; key++) {
            int currentIndex = actual.indexOf("\"" + key + "\"");

            assertTrue(
                    currentIndex > previousIndex,
                    "Nested key '" + key + "' is not in the expected order"
            );

            previousIndex = currentIndex;
        }
    }

    @Test
    void testParseOrderedPreservesKeyOrderAtAllNestedLevels() {
        // Build JSON with three levels of nested objects.
        StringBuilder json = new StringBuilder("{");

        for (char outer = 'a'; outer <= 'g'; outer++) {
            if (outer > 'a') {
                json.append(",");
            }

            json.append("\"").append(outer).append("\":{");

            for (char middle = 'a'; middle <= 'g'; middle++) {
                if (middle > 'a') {
                    json.append(",");
                }

                json.append("\"").append(middle).append("\":{");

                for (char inner = 'a'; inner <= 'g'; inner++) {
                    if (inner > 'a') {
                        json.append(",");
                    }

                    json.append("\"")
                            .append(inner)
                            .append("\":\"value-")
                            .append(inner)
                            .append("\"");
                }

                json.append("}");
            }

            json.append("}");
        }

        json.append("}");

        // Parse using parseOrdered
        JSONObject parsed = JSONUtils.parseOrdered(json.toString());

        // Verify ordering at every object, including root.
        assertKeysOrdered(parsed, "root");

        for (char outer = 'a'; outer <= 'g'; outer++) {
            JSONObject level1 = parsed.getJSONObject(String.valueOf(outer));

            assertKeysOrdered(level1, "root." + outer);

            for (char middle = 'a'; middle <= 'g'; middle++) {
                JSONObject level2
                        = level1.getJSONObject(String.valueOf(middle));

                assertKeysOrdered(
                        level2,
                        "root." + outer + "." + middle
                );
            }
        }
    }

    private void assertKeysOrdered(JSONObject obj, String path) {
        String actual = obj.toString(2);

        int previousIndex = -1;

        for (char key = 'a'; key <= 'g'; key++) {
            int currentIndex = actual.indexOf("\"" + key + "\"");

            assertTrue(
                    currentIndex > previousIndex,
                    "Key '" + key + "' is not ordered correctly at " + path
            );

            previousIndex = currentIndex;
        }
    }

    @Test
    void testParseOrderedUsesLinkedHashMapAtAllNestedLevels() {
        // Build nested JSON with three levels.
        StringBuilder json = new StringBuilder("{");

        for (char outer = 'a'; outer <= 'g'; outer++) {
            if (outer > 'a') {
                json.append(",");
            }

            json.append("\"").append(outer).append("\":{");

            for (char middle = 'a'; middle <= 'g'; middle++) {
                if (middle > 'a') {
                    json.append(",");
                }

                json.append("\"").append(middle).append("\":{");

                for (char inner = 'a'; inner <= 'g'; inner++) {
                    if (inner > 'a') {
                        json.append(",");
                    }

                    json.append("\"")
                            .append(inner)
                            .append("\":\"value-")
                            .append(inner)
                            .append("\"");
                }

                json.append("}");
            }

            json.append("}");
        }

        json.append("}");

        // Parse using parseOrdered
        JSONObject parsed = JSONUtils.parseOrdered(json.toString());

        // Check root and every nested JSONObject
        assertLinkedHashMapRecursively(parsed, "root");
    }

    private void assertLinkedHashMapRecursively(JSONObject obj, String path) {

        assertEquals(
                LinkedHashMap.class,
                obj.getMapType(),
                "Expected LinkedHashMap at " + path
        );

        for (String key : obj.keySet()) {
            Object value = obj.get(key);

            if (value instanceof JSONObject nested) {
                assertLinkedHashMapRecursively(
                        nested,
                        path + "." + key
                );
            }
        }
    }

}
