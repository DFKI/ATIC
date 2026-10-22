package de.dfki.sds.atic.jenatic;

/**
 *
 */
import java.util.ArrayList;
import java.util.List;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SchemaDO;
import org.json.JSONArray;
import org.json.JSONObject;

public class FragmentSettings {

    private final List<FragmentProperty> properties;

    private FragmentSettings(Builder builder) {
        this.properties = builder.properties;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static FragmentSettings fromJson(JSONObject json) {
        return builder().fromJson(json).build();
    }

    public JSONObject toJson() {
        JSONArray properties = new JSONArray();

        for (FragmentProperty property : this.properties) {
            properties.put(property.toJson());
        }

        return new JSONObject().put("properties", properties);
    }

    public List<FragmentProperty> getProperties() {
        return properties;
    }

    public static class Builder {

        private List<FragmentProperty> properties = new ArrayList<>();

        public Builder properties(List<FragmentProperty> properties) {
            this.properties = properties;
            return this;
        }

        public Builder addProperty(FragmentProperty property) {
            this.properties.add(property);
            return this;
        }

        public Builder addProperty(String key, Node property, boolean languageAware) {
            this.properties.add(new FragmentProperty(key, property, languageAware));
            return this;
        }

        public Builder fromJson(JSONObject json) {
            JSONArray propertiesArray = json.getJSONArray("properties");
            this.properties = new ArrayList<>();

            for (int i = 0; i < propertiesArray.length(); i++) {
                JSONObject propertyJson = propertiesArray.getJSONObject(i);
                JSONObject property = propertyJson.getJSONObject("property");

                String key = propertyJson.getString("key");
                boolean languageAware = propertyJson.getBoolean("languageAware");

                if (property.has("@id")) {
                    this.properties.add(new FragmentProperty(
                            key,
                            NodeFactory.createURI(property.getString("@id")),
                            languageAware
                    ));
                } else if (property.has("pattern")) {
                    Template template = new Template(
                            property.getString("pattern"),
                            property.optBoolean("trim", true),
                            property.optBoolean("normalizeWhitespace", true),
                            property.optBoolean("titleCase", false)
                    );

                    this.properties.add(new FragmentProperty(
                            key,
                            template,
                            languageAware
                    ));
                } else {
                    throw new IllegalArgumentException(
                            "Property must contain either '@id' or 'pattern': " + property
                    );
                }
            }

            return this;
        }

        public FragmentSettings build() {
            return new FragmentSettings(this);
        }
    }

    public static class FragmentProperty {

        private final String key;
        private final Node property;
        private final boolean languageAware;
        private final Template template;

        public FragmentProperty(String key, Node property, boolean languageAware) {
            this.key = key;
            this.property = property;
            this.template = null;
            this.languageAware = languageAware;
        }

        public FragmentProperty(String key, Template template, boolean languageAware) {
            this.key = key;
            this.property = null;
            this.template = template;
            this.languageAware = languageAware;
        }

        public JSONObject toJson() {
            JSONObject propertyJson;

            if (hasTemplate()) {
                propertyJson = new JSONObject()
                        .put("pattern", template.getPattern())
                        .put("trim", template.isTrim())
                        .put("normalizeWhitespace", template.isNormalizeWhitespace())
                        .put("titleCase", template.isTitleCase());
            } else {
                propertyJson = new JSONObject()
                        .put("@id", property.getURI());
            }

            return new JSONObject()
                    .put("key", key)
                    .put("property", propertyJson)
                    .put("languageAware", languageAware);
        }

        public String getKey() {
            return key;
        }

        public Node getProperty() {
            return property;
        }
        
        public boolean hasProperty() {
            return property != null;
        }

        public Template getTemplate() {
            return template;
        }

        public boolean hasTemplate() {
            return template != null;
        }

        public boolean isLanguageAware() {
            return languageAware;
        }
    }

    /**
     * Represents a template for generating a string from RDF properties, with optional whitespace normalization, trimming, and title casing.
     */
    public static class Template {

        private final String pattern;
        private final boolean trim;
        private final boolean normalizeWhitespace;
        private final boolean titleCase;

        public Template(String pattern, boolean trim, boolean normalizeWhitespace, boolean titleCase) {
            this.pattern = pattern;
            this.trim = trim;
            this.normalizeWhitespace = normalizeWhitespace;
            this.titleCase = titleCase;
        }

        /**
         * Creates a template with the given pattern, trimming leading and trailing whitespace and normalizing consecutive whitespace characters to a single
         * space, without applying title case.
         *
         * @param pattern the template pattern containing RDF property placeholders in TTL syntax
         */
        public Template(String pattern) {
            this(pattern, true, true, false);
        }

        public String getPattern() {
            return pattern;
        }

        public boolean isTrim() {
            return trim;
        }

        public boolean isNormalizeWhitespace() {
            return normalizeWhitespace;
        }

        public boolean isTitleCase() {
            return titleCase;
        }
    }

    public static FragmentSettings defaultSettings() {
        return FragmentSettings.builder()
                .addProperty(new FragmentProperty("type", RDF.type.asNode(), false)) //if we would use @type, fragment recursion is not possible
                .addProperty(new FragmentProperty("label", RDFS.label.asNode(), true))
                .addProperty(new FragmentProperty("comment", RDFS.comment.asNode(), true))
                .addProperty(new FragmentProperty("icon", SchemaDO.image.asNode(), false))
                .build();
    }

}
