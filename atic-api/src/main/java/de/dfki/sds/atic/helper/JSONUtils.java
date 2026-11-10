package de.dfki.sds.atic.helper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONParserConfiguration;
import org.json.JSONTokener;
import org.json.OrderedJSONTokener;

/**
 * Utility code for JSON.
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

    public static JSONObject parseOrdered(String source) {
        
        JSONTokener tokener = new OrderedJSONTokener(source);
        
        Object val = tokener.nextValue();
        
        if(val instanceof JSONObject obj) {
            return obj;
        }
        
        throw new RuntimeException("" + val.getClass());
    }
    
    public static JSONArray parseOrderedArray(String source) {
        
        JSONTokener tokener = new OrderedJSONTokener(source);
        
        Object val = tokener.nextValue();
        
        if(val instanceof JSONArray obj) {
            return obj;
        }
        
        throw new RuntimeException("" + val.getClass());
    }

    public static void continueParsing(JSONObject obj, JSONTokener tokener, JSONParserConfiguration config) {
        boolean isInitial;
        try {
            Method getPreviousMethod = JSONTokener.class.getDeclaredMethod(
                    "getPrevious"
            );
            getPreviousMethod.setAccessible(true);

            Character prev = (Character) getPreviousMethod.invoke(tokener);

            isInitial = prev == 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        if (tokener.nextClean() != '{') {
            throw tokener.syntaxError("A JSONObject text must begin with '{'");
        }

        try {

            Method parseJSONObjectMethod = JSONObject.class.getDeclaredMethod(
                    "parseJSONObject",
                    JSONTokener.class,
                    JSONParserConfiguration.class,
                    boolean.class
            );

            parseJSONObjectMethod.setAccessible(true);

            for (;;) {
                boolean done = (boolean) parseJSONObjectMethod.invoke(
                        obj, tokener, config, isInitial
                );

                if (done) {
                    return;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
