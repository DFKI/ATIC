

package de.dfki.sds.rdfpatchsqlite;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import org.json.JSONObject;

/**
 *
 */
public class JSONUtils {
    
    public static void forceLinkedHashMap(JSONObject json) {
        try {
            Field map = json.getClass().getDeclaredField("map");
            map.setAccessible(true);
            map.set(json, new LinkedHashMap<>());
            map.setAccessible(false);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static JSONObject createJSONObject() {
        JSONObject obj = new JSONObject();
        forceLinkedHashMap(obj);
        return obj;
    }
    
}

