

package de.dfki.sds.aticserver.bridge;

import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;
public class TemplateParser {

    public static JSONObject parse(JSONObject json) {
        Objects.requireNonNull(json, "template");

        validate(json);

        return json;
    }

    private static void validate(Object value) {

        if (value instanceof JSONObject obj) {

            if (obj.has("$where")) {

                if (!obj.has("$map")) {
                    throw new IllegalArgumentException(
                            "Query object requires '$map'"
                    );
                }
            }

            for (String key : obj.keySet()) {
                validate(obj.get(key));
            }
        }

        else if (value instanceof JSONArray arr) {

            for (Object item : arr) {
                validate(item);
            }
        }
    }
}