

package de.dfki.sds.aticsqlite;

import org.json.JSONArray;

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
    
    
    
}
