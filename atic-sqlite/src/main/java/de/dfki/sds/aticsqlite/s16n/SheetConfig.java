
package de.dfki.sds.aticsqlite.s16n;

import org.json.JSONObject;

/**
 *
 */
public class SheetConfig {

    private final String name;
    private final String rowQuery;

    private SheetConfig(Builder builder) {
        this.name = builder.name;
        this.rowQuery = builder.rowQuery;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static SheetConfig fromJson(JSONObject json) {
        return builder()
                .fromJson(json)
                .build();
    }

    public JSONObject toJson() {
        return new JSONObject()
                .put("name", name)
                .put("rowQuery", rowQuery);
    }

    public String getName() {
        return name;
    }

    public String getRowQuery() {
        return rowQuery;
    }

    public static class Builder {

        private String name;
        private String rowQuery;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder rowQuery(String rowQuery) {
            this.rowQuery = rowQuery;
            return this;
        }

        public Builder fromJson(JSONObject json) {
            this.name = json.optString("name", null);
            this.rowQuery = json.optString("rowQuery", null);
            return this;
        }

        public SheetConfig build() {
            return new SheetConfig(this);
        }
    }
}
