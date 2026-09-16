

package de.dfki.sds.aticsqlite.s16n;

/**
 *
 */
import org.json.JSONObject;

public class WorkbookConfig {

    private final String name;

    private WorkbookConfig(Builder builder) {
        this.name = builder.name;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static WorkbookConfig fromJson(JSONObject json) {
        return builder()
                .fromJson(json)
                .build();
    }

    public JSONObject toJson() {
        return new JSONObject()
                .put("name", name);
    }

    public String getName() {
        return name;
    }

    public static class Builder {

        private String name;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder fromJson(JSONObject json) {
            this.name = json.optString("name", null);
            return this;
        }

        public WorkbookConfig build() {
            return new WorkbookConfig(this);
        }
    }
}
