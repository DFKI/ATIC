package org.json;

import de.dfki.sds.atic.helper.JSONUtils;
import java.io.InputStream;
import java.io.Reader;

/**
 * When called nextValue: puts LinkedHashMap into JSONObject and continues the parsing.
 */
public class OrderedJSONTokener extends JSONTokener {

    public OrderedJSONTokener(Reader reader) {
        super(reader);
    }

    public OrderedJSONTokener(Reader reader, JSONParserConfiguration jsonParserConfiguration) {
        super(reader, jsonParserConfiguration);
    }

    public OrderedJSONTokener(InputStream inputStream) {
        super(inputStream);
    }

    public OrderedJSONTokener(InputStream inputStream, JSONParserConfiguration jsonParserConfiguration) {
        super(inputStream, jsonParserConfiguration);
    }

    public OrderedJSONTokener(String source) {
        super(source);
    }

    public OrderedJSONTokener(String source, JSONParserConfiguration jsonParserConfiguration) {
        super(source, jsonParserConfiguration);
    }

    @Override
    public Object nextValue() throws JSONException {
        char c = this.nextClean();
        switch (c) {
            case '{':
                this.back();
                try {
                    JSONObject obj = JSONUtils.createJSONObject();
                    JSONUtils.continueParsing(obj, this, getJsonParserConfiguration());
                    return obj;
                } catch (StackOverflowError e) {
                    throw new JSONException("JSON Array or Object depth too large to process.", e);
                }
            case '[':
                this.back();
                try {
                    return new JSONArray(this, getJsonParserConfiguration());
                } catch (StackOverflowError e) {
                    throw new JSONException("JSON Array or Object depth too large to process.", e);
                }
        }
        return super.nextSimpleValue(c);
    }

}
