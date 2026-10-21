package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.Group;
import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.helper.JSONUtils;
import de.dfki.sds.atic.jenatic.FragmentSettings;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.jena.graph.Node;
import org.json.JSONObject;

/**
 * For perstisting fragment settings and for a fragment cache.
 */
public class FragmentStorage {

    private final String EMPTY_CTX = "";
    private final int EMPTY_GROUP = -1;

    private SqliteAticDatasetGraph parent;
    private Database db;

    public FragmentStorage(SqliteAticDatasetGraph parent) {
        this.parent = parent;
        this.db = parent.getDatabase();
    }

    /*package*/ void bootstrap() throws SQLException {
        db.writeQuery("create_table_fragment_settings.sql");
        db.writeQuery("create_table_fragment_cache.sql");
    }

    public FragmentSettings load(Node context, String groupname, InvocationContext ctx) {
        String contextUri = context == null ? EMPTY_CTX : context.getURI();
        Integer groupId = groupname == null ? EMPTY_GROUP : resolveGroupId(groupname, ctx);

        try {
            return db.read(
                    "SELECT settings FROM fragment_settings WHERE context_uri = ? AND group_id = ?",
                    rs -> {
                        if (!rs.next()) {
                            return null;
                        }

                        return FragmentSettings.fromJson(
                                new JSONObject(rs.getString("settings"))
                        );
                    },
                    contextUri, groupId
            );
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    public List<FragmentSettingsEntry> loadAll(InvocationContext ctx) {
        try {
            return db.read(
                    "SELECT context_uri, group_id, settings, creator, created_at "
                    + "FROM fragment_settings ORDER BY id",
                    rs -> {
                        List<FragmentSettingsEntry> result = new ArrayList<>();

                        while (rs.next()) {
                            String contextUri = rs.getString("context_uri");
                            if (EMPTY_CTX.equals(contextUri)) {
                                contextUri = null;
                            }

                            int groupIdValue = rs.getInt("group_id");
                            Integer groupId = rs.wasNull() ? null : groupIdValue;

                            if (groupId != null && groupId == EMPTY_GROUP) {
                                groupId = null;
                            }

                            Group group = groupId == null
                                    ? null
                                    : parent.getGroup(groupId, ctx);

                            int creatorId = rs.getInt("creator");
                            User creator = parent.getUser(creatorId, ctx);

                            FragmentSettings settings = FragmentSettings.fromJson(
                                    new JSONObject(rs.getString("settings"))
                            );

                            Instant createdAt = Instant.ofEpochSecond(
                                    rs.getLong("created_at")
                            );

                            result.add(new FragmentSettingsEntry(
                                    contextUri,
                                    group,
                                    settings,
                                    creator,
                                    createdAt
                            ));
                        }

                        return result;
                    }
            );
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    public void save(Node context, String groupname, FragmentSettings settings, InvocationContext ctx) {
        String contextUri = context == null ? EMPTY_CTX : context.getURI();
        Integer groupId = groupname == null ? EMPTY_GROUP : resolveGroupId(groupname, ctx);
        int creator = ctx.getUserId();

        try {
            db.write(
                    "INSERT INTO fragment_settings (context_uri, group_id, settings, creator) "
                    + "VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT(context_uri, group_id) DO UPDATE SET "
                    + "settings = excluded.settings, "
                    + "creator = excluded.creator, "
                    + "created_at = unixepoch()",
                    contextUri,
                    groupId,
                    settings.toJson().toString(),
                    creator
            );
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    public void delete(Node context, String groupname, InvocationContext ctx) {
        String contextUri = context == null ? EMPTY_CTX : context.getURI();
        Integer groupId = groupname == null ? EMPTY_GROUP : resolveGroupId(groupname, ctx);

        try {
            db.write(
                    "DELETE FROM fragment_settings WHERE context_uri = ? AND group_id = ?",
                    contextUri, groupId
            );
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    //=================================
    //helper
    public void save(FragmentSettings settings, InvocationContext ctx) {
        save(null, null, settings, ctx);
    }

    public void save(Node context, FragmentSettings settings, InvocationContext ctx) {
        save(context, null, settings, ctx);
    }

    public void save(String groupname, FragmentSettings settings, InvocationContext ctx) {
        save(null, groupname, settings, ctx);
    }

    public FragmentSettings load(InvocationContext ctx) {
        return load(null, null, ctx);
    }

    public FragmentSettings load(Node context, InvocationContext ctx) {
        return load(context, null, ctx);
    }

    public FragmentSettings load(String groupname, InvocationContext ctx) {
        return load(null, groupname, ctx);
    }

    public void delete(InvocationContext ctx) {
        delete(null, null, ctx);
    }

    public void delete(Node context, InvocationContext ctx) {
        delete(context, null, ctx);
    }

    public void delete(String groupname, InvocationContext ctx) {
        delete(null, groupname, ctx);
    }

    private Integer resolveGroupId(String groupname, InvocationContext ctx) {
        Group group = parent.getGroup(groupname, ctx);
        return group.getId();
    }

    //================================
    //records
    
    public record FragmentSettingsEntry (
            String contextUri,
            Group group,
            FragmentSettings settings,
            User creator,
            Instant createdAt
            ) {

        public JSONObject toJson() {
            return JSONUtils.createJSONObject()
                    .put("contextUri", contextUri == null ? JSONObject.NULL : contextUri)
                    .put("groupname", group == null ? JSONObject.NULL : group.getGroupname())
                    .put("creator", creator.getUsername())
                    .put("createdAt", createdAt.toString())
                    .put("settings", settings.toJson());
        }

    }

}
