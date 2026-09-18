package de.dfki.sds.aticsqlite.bridge;

/**
 *
 */
import java.util.ArrayList;
import java.util.List;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.vocabulary.FOAF;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
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

        public Builder fromJson(JSONObject json) {
            JSONArray propertiesArray = json.getJSONArray("properties");
            this.properties = new ArrayList<>();

            for (int i = 0; i < propertiesArray.length(); i++) {
                JSONObject propertyJson = propertiesArray.getJSONObject(i);
                this.properties.add(new FragmentProperty(
                        propertyJson.getString("key"),
                        NodeFactory.createURI(propertyJson.getJSONObject("property").getString("@id")),
                        propertyJson.getBoolean("languageAware")
                ));
            }

            return this;
        }

        public FragmentSettings build() {
            return new FragmentSettings(this);
        }
    }

    //language aware does only select best language but still shows the language
    //TODO we need another setting like hideLanguage, so it is just a string
    //actually: we have to make sure to interpret strings always the same way
    
    public static class FragmentProperty {

        private final String key;
        private final Node property;
        private final boolean languageAware;

        public FragmentProperty(String key, Node property, boolean languageAware) {
            this.key = key;
            this.property = property;
            this.languageAware = languageAware;
        }

        public JSONObject toJson() {
            return new JSONObject()
                    .put("key", key)
                    .put("property", new JSONObject().put("@id", property.getURI()))
                    .put("languageAware", languageAware);
        }

        public String getKey() {
            return key;
        }

        public Node getProperty() {
            return property;
        }

        public boolean isLanguageAware() {
            return languageAware;
        }
    }

    public static FragmentSettings defaultSettings() {
        return FragmentSettings.builder()
                .addProperty(new FragmentProperty("@type", RDF.type.asNode(), false))
                .addProperty(new FragmentProperty("label", RDFS.label.asNode(), true))
                .addProperty(new FragmentProperty("comment", RDFS.comment.asNode(), true))
                .addProperty(new FragmentProperty("icon", FOAF.img.asNode(), false))
                .build();
    }

}
