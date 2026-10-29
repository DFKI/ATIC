package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.jenatic.InvocationContext;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.commons.text.StringEscapeUtils;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 *
 */
public class VueComponentStorage {

    private SqliteAticDatasetGraph parent;
    private Database db;

    private static final String BRIDGE_ENDPOINT = "/bridge";

    public VueComponentStorage(SqliteAticDatasetGraph parent) {
        this.parent = parent;
        this.db = parent.getDatabase();
    }

    /*package*/ void bootstrap() throws SQLException {
        db.writeQuery("create_table_vue_components.sql");
    }

    public VueComponent load(String path, String name) {
        path = normalizePath(path);
        try {
            return db.read(
                    "SELECT path, name, bridges, script, template, style "
                    + "FROM vue_components WHERE path = ? AND name = ?",
                    rs -> {
                        if (!rs.next()) {
                            return null;
                        }

                        VueComponent component = new VueComponent(
                                rs.getString("path"),
                                rs.getString("name")
                        );

                        component.setBridges(
                                new JSONArray(rs.getString("bridges"))
                        );
                        component.setScript(rs.getString("script"));
                        component.setTemplate(rs.getString("template"));
                        component.setStyle(rs.getString("style"));

                        return component;
                    },
                    path, name
            );
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    public List<VueComponent> loadAll() {
        try {
            return db.read(
                    "SELECT path, name, bridges, script, template, style "
                    + "FROM vue_components ORDER BY path, name",
                    rs -> {
                        List<VueComponent> components = new ArrayList<>();

                        while (rs.next()) {
                            VueComponent component = new VueComponent(
                                    rs.getString("path"),
                                    rs.getString("name")
                            );

                            component.setBridges(
                                    new JSONArray(rs.getString("bridges"))
                            );
                            component.setScript(rs.getString("script"));
                            component.setTemplate(rs.getString("template"));
                            component.setStyle(rs.getString("style"));

                            components.add(component);
                        }

                        return components;
                    }
            );
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    public List<VueComponent> loadByPath(String path) {
        path = normalizePath(path);

        try {
            return db.read(
                    "SELECT path, name, bridges, script, template, style "
                    + "FROM vue_components WHERE path = ? ORDER BY name",
                    rs -> {
                        List<VueComponent> components = new ArrayList<>();

                        while (rs.next()) {
                            VueComponent component = new VueComponent(
                                    rs.getString("path"),
                                    rs.getString("name")
                            );

                            component.setBridges(
                                    new JSONArray(rs.getString("bridges"))
                            );
                            component.setScript(rs.getString("script"));
                            component.setTemplate(rs.getString("template"));
                            component.setStyle(rs.getString("style"));

                            components.add(component);
                        }

                        return components;
                    },
                    path
            );
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    public void save(VueComponent component, InvocationContext ctx) {
        int creator = ctx.getUserId();
        try {
            db.write(
                    "INSERT INTO vue_components (path, name, bridges, script, template, style, creator) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT(path, name) DO UPDATE SET "
                    + "bridges = excluded.bridges, "
                    + "script = excluded.script, "
                    + "template = excluded.template, "
                    + "style = excluded.style, "
                    + "creator = excluded.creator, "
                    + "created_at = unixepoch()",
                    normalizePath(component.getPath()),
                    component.getName(),
                    component.getBridges().toString(),
                    component.getScript(),
                    component.getTemplate(),
                    component.getStyle(),
                    creator
            );
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    public void delete(String path, String name) {
        path = normalizePath(path);

        try {
            db.write(
                    "DELETE FROM vue_components WHERE path = ? AND name = ?",
                    path,
                    name
            );
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    //---------------------------
    public String loadAs(String path, String name, String assetsPath, String componentsPath, String format) {
        if (!"html".equalsIgnoreCase(format) && !"js".equalsIgnoreCase(format)) {
            throw new IllegalArgumentException("Unsupported format: " + format);
        }

        path = normalizePath(path);

        VueComponent component = load(path, name);
        if (component == null) {
            throw new IllegalArgumentException("Component not found: " + path + "/" + name);
        }

        if ("html".equalsIgnoreCase(format)) {
            return asHtml(component, componentsPath, assetsPath);
        } else if ("js".equalsIgnoreCase(format)) {
            return asJs(component, componentsPath, assetsPath);
        }

        throw new RuntimeException("Not implemented: " + format);
    }

    private String asJs(VueComponent component, String componentsPath, String assetsPath) {

        StringBuilder sb = new StringBuilder();

        sb.append("const ").append(component.getName()).append(" = ");
        sb.append(includeBridges(component.getScript(), BRIDGE_ENDPOINT, component.getBridges()));
        sb.append(";");

        //html as js block string
        int b = sb.indexOf("{");
        sb.insert(b + 1, "\n\ntemplate: `\n" + component.getTemplate() + "\n`,\n\n");

        //TODO do style later, there is no style: property in options API
        return sb.toString();
    }

    private String asHtml(VueComponent component, String componentsPath, String assetsPath) {
        String template = component.getTemplate() == null ? "" : component.getTemplate();
        String script = component.getScript() == null ? "" : component.getScript();
        String style = component.getStyle() == null ? "" : component.getStyle();

        StringBuilder componentScripts = new StringBuilder();
        StringBuilder componentRegistrations = new StringBuilder();

        for (VueComponent other : loadAll()) {
            if (other.equals(component)) {
                continue;
            }

            String fullpath = buildPath(componentsPath, other.getPath(), other.getName());

            componentScripts.append("    <script src=\"")
                    .append(fullpath)
                    .append(".js\"></script>\n");

            componentRegistrations.append("        app.component('")
                    .append(other.getName())
                    .append("', ")
                    .append(other.getName())
                    .append(");\n");
        }

        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">

            <link href="%scss/bootstrap.min.css" rel="stylesheet">
            <link href="%scss/all.min.css" rel="stylesheet">

            <script src="%sjs/bootstrap.bundle.min.js"></script>
            <script src="%sjs/vue.global.prod.js"></script>
            <script src="%sjs/axios.min.js"></script>
        %s
            <link rel="shortcut icon" href="%sfavicon.ico">
            <title>%s Demo</title>
            <style>
        %s
            </style>
        </head>
        <body>
            <div class="container-fluid py-4" id="app">
        %s
            </div>

            <script>
                const { createApp } = Vue;
                const app = createApp(
        %s
                );

        %s
                app.mount('#app');
            </script>
        </body>
        </html>
        """.formatted(
                assetsPath,
                assetsPath,
                assetsPath,
                assetsPath,
                assetsPath,
                componentScripts,
                assetsPath,
                StringEscapeUtils.escapeHtml4(component.getName()),
                style,
                template,
                includeBridges(script, BRIDGE_ENDPOINT, component.getBridges()),
                componentRegistrations
        );
    }

    private String buildPath(String... parts) {
        return Arrays.stream(parts)
                .filter(Objects::nonNull)
                .flatMap(part -> Arrays.stream(part.split("/")))
                .filter(part -> !part.isBlank())
                .collect(Collectors.joining("/", "/", ""));
    }

    private String normalizePath(String path) {
        String npath = path == null ? "" : path.replaceAll("/+$", "");
        if (npath.isBlank()) {
            npath = "/";
        }
        return npath;
    }

    //------------------------------
    //include bridges
    private String includeBridges(String script, String baseUrl, JSONArray bridges) {
        script = includeBridgeTemplates(script, bridges);
        script = includeBridgeFunctions(script, baseUrl, bridges);
        return script;
    }

    private String includeBridgeTemplates(String script, JSONArray bridges) {

        if (bridges.isEmpty()) {
            return script;
        }

        int dataIndex = script.indexOf("data()");
        if (dataIndex < 0) {
            throw new IllegalArgumentException("Component script does not contain data()");
        }

        int returnIndex = script.indexOf("return", dataIndex);
        if (returnIndex < 0) {
            throw new IllegalArgumentException("Could not locate return in data()");
        }

        int braceIndex = script.indexOf('{', returnIndex + "return".length());
        if (braceIndex < 0) {
            throw new IllegalArgumentException("Could not locate data return object");
        }

        StringBuilder entries = new StringBuilder();

        for (int i = 0; i < bridges.length(); i++) {
            JSONObject bridgeEntry = bridges.getJSONObject(i);
            String name = bridgeEntry.optString("$name", "");

            if (name.isBlank()) {
                continue;
            }

            entries.append("        template")
                    .append(name)
                    .append(": ")
                    .append(bridgeEntry.toString(4))
                    .append(",\n");

            for (String method : List.of("query", "post", "put", "patch", "delete")) {
                entries.append("        ")
                        .append(method)
                        .append("State")
                        .append(name)
                        .append(": { loading: false, success: false, error: null, result: null },\n");
            }
        }

        int next = braceIndex + 1;
        while (next < script.length()) {
            char c = script.charAt(next);
            if (c == '}') {
                break;
            }
            if (!Character.isWhitespace(c)) {
                break;
            }
            next++;
        }

        String separator = next < script.length() && script.charAt(next) == '}' ? "\n" : ",\n";

        return script.substring(0, braceIndex + 1)
                + "\n"
                + entries.toString().replaceAll(",\\s*$", "")
                + separator
                + script.substring(braceIndex + 1);
    }

    private String includeBridgeFunctions(String script, String baseUrl, JSONArray bridges) {
        int methodsIndex = script.indexOf("methods:");
        if (methodsIndex < 0) {
            throw new IllegalArgumentException("Component script does not contain methods:");
        }

        int braceIndex = script.indexOf('{', methodsIndex);
        if (braceIndex < 0) {
            throw new IllegalArgumentException("Could not locate methods block");
        }

        int next = braceIndex + 1;
        while (next < script.length()) {
            char c = script.charAt(next);
            if (c == '}') {
                break;
            }
            if (!Character.isWhitespace(c)) {
                break;
            }
            next++;
        }

        String separator = next < script.length() && script.charAt(next) == '}' ? "\n" : ",\n";

        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < bridges.length(); i++) {
            JSONObject bridgeEntry = bridges.getJSONObject(i);
            String name = bridgeEntry.optString("$name", "");

            if (name.isBlank()) {
                continue;
            }

            entries.append(getQueryFunctionTemplate(name)).append(",\n");
            entries.append(getMutationFunctionTemplate("post", name)).append(",\n");
            entries.append(getMutationFunctionTemplate("put", name)).append(",\n");
            entries.append(getMutationFunctionTemplate("patch", name)).append(",\n");
            entries.append(getMutationFunctionTemplate("delete", name)).append(",\n");
        }
        entries.append("\n");

        String bridgeMethods = entries.toString() + """
        async query(template, params = {}) {
            const response = await this.request('QUERY', template, params);
            return response.data;
        },

        async post(template, data = null, params = {}, dryRun = false) {
            return this.mutate('POST', template, data, params, dryRun);
        },

        async put(template, data = null, params = {}, dryRun = false) {
            return this.mutate('PUT', template, data, params, dryRun);
        },

        async patch(template, data = null, params = {}, dryRun = false) {
            return this.mutate('PATCH', template, data, params, dryRun);
        },

        async delete(template, data = null, params = {}, dryRun = false) {
            return this.mutate('DELETE', template, data, params, dryRun);
        },

        async mutate(method, template, data, params, dryRun = false) {
            const response = await this.request(
                method,
                { template, data },
                params,
                dryRun
            );
            return response.data;
        },

        async request(method, data, params = {}, dryRun = false) {
            const headers = {
                'Content-Type': 'application/json'
            };

            if (dryRun) {
                headers['Atic-Dry-Run'] = 'true';
            }

            try {
                return await axios.request({
                    method,
                    url: "$baseUrl",
                    params,
                    data,
                    withCredentials: true,
                    headers,
                    responseType: method === 'QUERY' ? 'json' : 'text'
                });
            } catch (error) {
                if (error.response) {
                    throw new BridgeError(
                        error.response.data || error.message,
                        error.response.status,
                        error.response
                    );
                }
                throw new BridgeError(error.message);
            }
        }
        """.replace("$baseUrl", baseUrl);

        return script.substring(0, braceIndex + 1)
                + "\n"
                + bridgeMethods
                + separator
                + script.substring(braceIndex + 1);
    }

    private String getQueryFunctionTemplate(String templateName) {
        return """
            async query$templateName(params = {}) {
                this.queryState$templateName.loading = true;
                this.queryState$templateName.success = false;
                this.queryState$templateName.error = null;

                try {
                    this.queryState$templateName.result = await this.query(this.template$templateName, params);
                    this.queryState$templateName.success = true;
                    return this.queryState$templateName.result;
                } catch (error) {
                    this.queryState$templateName.error = error.message || String(error);
                    return null;
                } finally {
                    this.queryState$templateName.loading = false;
                }
            }
            """
                .replace("$templateName", templateName);
    }

    private String getMutationFunctionTemplate(String method, String templateName) {
        return """
            async $method$templateName(data = null, params = {}) {
                this.$methodState$templateName.loading = true;
                this.$methodState$templateName.success = false;
                this.$methodState$templateName.error = null;

                try {
                    this.$methodState$templateName.result = await this.$method(this.template$templateName, data, params);
                    this.$methodState$templateName.success = true;
                    return this.$methodState$templateName.result;
                } catch (error) {
                    this.$methodState$templateName.error = error.message || String(error);
                    return null;
                } finally {
                    this.$methodState$templateName.loading = false;
                }
            }
            """
                .replace("$method", method)
                .replace("$templateName", templateName);
    }

}
