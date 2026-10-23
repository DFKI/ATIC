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
            this.properties = new ArrayList<>();

            if (json.has("properties")) {
                JSONArray propertiesArray = json.getJSONArray("properties");

                for (int i = 0; i < propertiesArray.length(); i++) {
                    JSONObject propertyJson = propertiesArray.getJSONObject(i);
                    String key = propertyJson.getString("key");
                    boolean languageAware = propertyJson.getBoolean("languageAware");
                    JSONArray rankingArray = propertyJson.getJSONArray("properties");

                    List<Node> propertyRanking = new ArrayList<>();
                    for (int j = 0; j < rankingArray.length(); j++) {
                        JSONObject rankingJson = rankingArray.getJSONObject(j);
                        propertyRanking.add(NodeFactory.createURI(rankingJson.getString("@id")));
                    }

                    this.properties.add(new FragmentProperty(key, propertyRanking, languageAware));
                }
            } else if (json.has("templates")) {
                JSONArray templatesArray = json.getJSONArray("templates");

                for (int i = 0; i < templatesArray.length(); i++) {
                    JSONObject templateJson = templatesArray.getJSONObject(i);
                    String key = templateJson.getString("key");
                    boolean languageAware = templateJson.getBoolean("languageAware");
                    JSONArray rankingArray = templateJson.getJSONArray("templates");

                    List<Template> templateRanking = new ArrayList<>();
                    for (int j = 0; j < rankingArray.length(); j++) {
                        JSONObject rankingJson = rankingArray.getJSONObject(j);
                        templateRanking.add(new Template(
                                rankingJson.getString("pattern"),
                                rankingJson.optBoolean("trim", true),
                                rankingJson.optBoolean("normalizeWhitespace", true),
                                rankingJson.optBoolean("titleCase", false)
                        ));
                    }

                    this.properties.add(new FragmentProperty(key, languageAware, templateRanking));
                }
            } else {
                throw new IllegalArgumentException(
                        "JSON must contain either 'properties' or 'templates': " + json
                );
            }

            return this;
        }

        public FragmentSettings build() {
            return new FragmentSettings(this);
        }
    }

    public static class FragmentProperty {

        private final String key;
        private final List<Node> propertyRanking;
        private final List<Template> templateRanking;
        private final boolean languageAware;

        public FragmentProperty(String key, Node property, boolean languageAware) {
            this(key, List.of(property), languageAware);
        }

        public FragmentProperty(String key, List<Node> propertyRanking, boolean languageAware) {
            this.key = key;
            this.propertyRanking = propertyRanking;
            this.templateRanking = null;
            this.languageAware = languageAware;
        }

        public FragmentProperty(String key, boolean languageAware, Template template) {
            this(key, languageAware, List.of(template));
        }

        public FragmentProperty(String key, boolean languageAware, List<Template> templateRanking) {
            this.key = key;
            this.propertyRanking = null;
            this.templateRanking = templateRanking;
            this.languageAware = languageAware;
        }

        public JSONObject toJson() {
            JSONArray rankingJson = new JSONArray();

            if (hasTemplateRanking()) {
                for (Template template : templateRanking) {
                    rankingJson.put(new JSONObject()
                            .put("pattern", template.getPattern())
                            .put("trim", template.isTrim())
                            .put("normalizeWhitespace", template.isNormalizeWhitespace())
                            .put("titleCase", template.isTitleCase()));
                }
            } else {
                for (Node property : propertyRanking) {
                    rankingJson.put(new JSONObject()
                            .put("@id", property.getURI()));
                }
            }

            return new JSONObject()
                    .put("key", key)
                    .put(hasTemplateRanking() ? "templates" : "properties", rankingJson)
                    .put("languageAware", languageAware);
        }

        public String getKey() {
            return key;
        }

        public List<Node> getPropertyRanking() {
            return propertyRanking;
        }

        public List<Template> getTemplateRanking() {
            return templateRanking;
        }

        public boolean hasPropertyRanking() {
            return propertyRanking != null;
        }

        public boolean hasTemplateRanking() {
            return templateRanking != null;
        }

        public boolean isLanguageAware() {
            return languageAware;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("FragmentProperty{");
            sb.append("key=").append(key);
            sb.append(", propertyRanking=").append(propertyRanking);
            sb.append(", templateRanking=").append(templateRanking);
            sb.append(", languageAware=").append(languageAware);
            sb.append('}');
            return sb.toString();
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
