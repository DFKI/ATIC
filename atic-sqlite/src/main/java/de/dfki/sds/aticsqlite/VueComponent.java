package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.helper.JSONUtils;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 *
 */
public class VueComponent {

    private String path;
    private String name;

    private JSONArray bridges = new JSONArray();

    private String script;
    private String template;
    private String style;

    public VueComponent(String path, String name) {
        this.path = path;
        this.name = name;
    }

    public JSONObject toJson() {
        JSONObject json = JSONUtils.createJSONObject();
        json.put("path", path);
        json.put("name", name);
        json.put("bridges", bridges);
        json.put("script", script == null ? JSONObject.NULL : script);
        json.put("template", template == null ? JSONObject.NULL : template);
        json.put("style", style == null ? JSONObject.NULL : style);
        return json;
    }

    public static VueComponent fromJson(JSONObject json) {
        VueComponent component = new VueComponent(
                json.getString("path"),
                json.getString("name")
        );

        component.setBridges(json.optJSONArray("bridges") != null
                ? json.getJSONArray("bridges")
                : new JSONArray());
        component.setScript(json.isNull("script") ? null : json.optString("script", null));
        component.setTemplate(json.isNull("template") ? null : json.optString("template", null));
        component.setStyle(json.isNull("style") ? null : json.optString("style", null));

        return component;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public JSONArray getBridges() {
        return bridges;
    }

    public void setBridges(JSONArray bridges) {
        this.bridges = bridges;
    }

    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
    }

    public String getTemplate() {
        return template;
    }

    public void setTemplate(String template) {
        this.template = template;
    }

    public String getStyle() {
        return style;
    }

    public void setStyle(String style) {
        this.style = style;
    }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 53 * hash + Objects.hashCode(this.path);
        hash = 53 * hash + Objects.hashCode(this.name);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final VueComponent other = (VueComponent) obj;
        if (!Objects.equals(this.path, other.path)) {
            return false;
        }
        return Objects.equals(this.name, other.name);
    }

    
}
