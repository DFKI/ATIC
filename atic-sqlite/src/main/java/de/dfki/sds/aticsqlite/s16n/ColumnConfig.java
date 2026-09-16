

package de.dfki.sds.aticsqlite.s16n;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.json.JSONObject;

/**
 *
 */
public class ColumnConfig {

    private final String name;

    private final Node property;
    private final ColumnType type;
    private final Direction direction;

    private final Node datatype;
    private final String language;

    private ColumnConfig(Builder builder) {
        this.name = builder.name;
        this.property = builder.property;
        this.type = builder.type;
        this.direction = builder.direction;
        this.datatype = builder.datatype;
        this.language = builder.language;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ColumnConfig fromJson(JSONObject json) {
        return builder()
                .fromJson(json)
                .build();
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject()
                .put("name", name)
                .put("property", new JSONObject()
                        .put("@id", property.getURI()))
                .put("type", type.name())
                .put("direction", direction.name());

        if (datatype != null) {
            json.put("datatype", new JSONObject()
                    .put("@id", datatype.getURI()));
        }

        if (language != null) {
            json.put("language", language);
        }

        return json;
    }

    public String getName() {
        return name;
    }

    public Node getProperty() {
        return property;
    }

    public ColumnType getType() {
        return type;
    }

    public Direction getDirection() {
        return direction;
    }

    public Node getDatatype() {
        return datatype;
    }

    public String getLanguage() {
        return language;
    }

    public static class Builder {

        private String name;

        private Node property;
        private ColumnType type;
        private Direction direction;

        private Node datatype;
        private String language;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder property(Node property) {
            this.property = property;
            return this;
        }

        public Builder type(ColumnType type) {
            this.type = type;
            return this;
        }

        public Builder direction(Direction direction) {
            this.direction = direction;
            return this;
        }

        public Builder datatype(Node datatype) {
            this.datatype = datatype;
            return this;
        }

        public Builder language(String language) {
            this.language = language;
            return this;
        }

        public Builder fromJson(JSONObject json) {
            try {
                this.name = json.getString("name");

                JSONObject propertyJson = json.getJSONObject("property");
                this.property = NodeFactory.createURI(
                        propertyJson.getString("@id")
                );

                this.type = ColumnType.valueOf(
                        json.getString("type")
                );

                this.direction = Direction.valueOf(
                        json.getString("direction")
                );

                if (json.has("datatype")) {
                    JSONObject datatypeJson = json.getJSONObject("datatype");

                    this.datatype = NodeFactory.createURI(
                            datatypeJson.getString("@id")
                    );
                }

                if (json.has("language")) {
                    this.language = json.getString("language");
                }

                return this;
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Invalid ColumnConfig JSON",
                        e
                );
            }
        }

        public ColumnConfig build() {
            return new ColumnConfig(this);
        }
    }
}
