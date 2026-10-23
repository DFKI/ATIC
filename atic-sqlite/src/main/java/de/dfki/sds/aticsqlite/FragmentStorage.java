package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.Group;
import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.helper.JSONUtils;
import de.dfki.sds.atic.jenatic.Fragment;
import de.dfki.sds.atic.jenatic.FragmentSettings;
import de.dfki.sds.atic.jenatic.FragmentSettings.FragmentProperty;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.util.iterator.ExtendedIterator;
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

    //==================================
    //fragment
    public List<Fragment> buildFragments(long[] resourceIds, InvocationContext ctx) {
        //via InvocationContext we get
        //* userId who, groupIds in which roles
        //* locale (language)
        //* context URI(s): pointing to something that might change this, it could be a graph, it could be an application or contextspace
        //resourceIds is for low-level, like invex results, long[] foundResourceIDs

        //based on InvocationContext we pick the best suggested FragmentSettings
        FragmentSettings settings = suggestSettings(ctx);

        //TODO resourceIds might contain duplicates
        List<Node> nodes;
        try {
            nodes = AticGraphUtils.resolveResourceIds(resourceIds, db);
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }

        List<Fragment> fragments = new ArrayList<>(nodes.size());
        for (Node node : nodes) {

            Fragment.Builder builder = Fragment.builder(node).settings(settings);

            //based on FragmentSettings we fill the fragments subgraph
            for (FragmentProperty property : settings.getProperties()) {

                
                if(property.hasTemplateRanking()) {
                    //TODO also implement for prop.hasTemplate()
                }
                
                if (property.hasPropertyRanking()) {
                    
                    for(Node prop : property.getPropertyRanking()) {

                        //fill with data based on settings
                        ExtendedIterator<Quad> it = (ExtendedIterator<Quad>) parent.find(
                                Node.ANY,
                                node,
                                prop,
                                Node.ANY,
                                ctx
                        );
                        while (it.hasNext()) {
                            Quad q = it.next();
                            builder.add(q.getPredicate(), q.getObject());
                        }
                        it.close();
                    
                    }

                }

            }

            fragments.add(builder.build());
        }

        return fragments;
    }

    //===================================
    //fragment settings
    public FragmentSettings suggestSettings(InvocationContext ctx) {
        Set<Integer> groupIds = new LinkedHashSet<>(ctx.getGroupIds());
        List<Node> contextNodes = new ArrayList<>(ctx.getContextualNodes());

        groupIds.add(null);
        contextNodes.add(null);

        for (Node context : contextNodes) {
            for (Integer groupId : groupIds) {
                //TODO later do this as bulk for one SQL query?
                FragmentSettings settings = load(context, groupId);
                if (settings != null) {
                    return settings;
                }
            }
        }

        return FragmentSettings.defaultSettings();
    }

    //might be deprecated
    @Deprecated
    public FragmentSettings suggestSettings(Node context, String groupname, InvocationContext ctx) {
        FragmentSettings settings;

        if (context != null && groupname != null) {
            settings = load(context, groupname, ctx);
            if (settings != null) {
                return settings;
            }
        }

        if (context != null) {
            settings = load(context, null, ctx);
            if (settings != null) {
                return settings;
            }
        }

        if (groupname != null) {
            settings = load(null, groupname, ctx);
            if (settings != null) {
                return settings;
            }
        }

        settings = load(null, null, ctx);
        return settings != null ? settings : FragmentSettings.defaultSettings();
    }

    public FragmentSettings load(Node context, Integer groupId) {
        String contextUri = context == null ? EMPTY_CTX : context.getURI();
        Integer innerGroupId = groupId == null ? EMPTY_GROUP : groupId;

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
                    contextUri, innerGroupId
            );
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
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
    public record FragmentSettingsEntry(
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
