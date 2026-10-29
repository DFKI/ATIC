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

/**
 *
 */
public class VueComponentStorage {

    private SqliteAticDatasetGraph parent;
    private Database db;

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
        sb.append(component.getScript());
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
                script,
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
        if(npath.isBlank()) {
            npath = "/";
        }
        return npath;
    }

}
