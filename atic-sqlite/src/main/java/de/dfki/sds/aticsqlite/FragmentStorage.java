package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.Group;
import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.helper.JSONUtils;
import de.dfki.sds.atic.jenatic.AticDatasetGraph;
import de.dfki.sds.atic.jenatic.Fragment;
import de.dfki.sds.atic.jenatic.FragmentSettings;
import de.dfki.sds.atic.jenatic.FragmentSettings.FragmentProperty;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
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
        // Remove duplicate resource IDs while preserving order.
        long[] uniqueResourceIds = Arrays.stream(resourceIds)
                .distinct()
                .toArray();

        List<Node> nodes;
        try {
            nodes = AticGraphUtils.resolveResourceIds(uniqueResourceIds, db);
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }

        return buildFragments(nodes, ctx);
    }

    public List<Fragment> buildFragments(List<Node> nodes, InvocationContext ctx) {
        FragmentSettings settings = suggestSettings(ctx);

        List<Fragment> fragments = new ArrayList<>(nodes.size());

        for (Node node : nodes) {
            Set<Node> visited = new HashSet<>();
            fragments.add(buildFragment(node, settings, ctx, visited));
        }

        return fragments;
    }

    private Fragment buildFragment(Node node, FragmentSettings settings, InvocationContext ctx, Set<Node> visited) {
        return buildFragment(parent, node, settings, ctx, visited);
    }
    
    //FragmentStorage independent implementation, used by ResultSetJsonMapper
    public static Fragment buildFragment(AticDatasetGraph datasetGraph, Node node, FragmentSettings settings, InvocationContext ctx, Set<Node> visited) {
        Fragment.Builder builder = Fragment.builder(node).settings(settings);

        // Prevent cycles in the current traversal.
        if (!visited.add(node)) {
            return builder.build();
        }

        for (FragmentProperty property : settings.getProperties()) {
            if (property.hasTemplateRanking()) {
                //TODO also implement for prop.hasTemplate()
            }
            if (property.hasPropertyRanking()) {
                for (Node predicate : property.getPropertyRanking()) {
                    ExtendedIterator<Quad> it = (ExtendedIterator<Quad>) datasetGraph.find(
                            Node.ANY,
                            node,
                            predicate,
                            Node.ANY,
                            ctx
                    );

                    boolean found = false;

                    try {
                        while (it.hasNext()) {
                            found = true;
                            Quad q = it.next();
                            Node object = q.getObject();

                            if (object.isURI()) {
                                Fragment nested = buildFragment(datasetGraph, object, settings, ctx, visited);
                                builder.add(q.getPredicate(), nested);
                            } else {
                                builder.add(q.getPredicate(), object);
                            }
                        }
                    } finally {
                        it.close();
                    }

                    // If inheritance is enabled and no direct value was found, search ancestors.
                    if (!found && property.isPropertyInheritance()) {
                        for (Node value : findAncestorValue(datasetGraph, node, predicate, settings, ctx)) {
                            if (value.isURI()) {
                                Fragment nested = buildFragment(datasetGraph, value, settings, ctx, visited);
                                builder.add(predicate, nested);
                            } else {
                                builder.add(predicate, value);
                            }
                        }
                    }
                }
            }
        }

        return builder.build();
    }

    /**
     * Search for values of a predicate by walking the rdfs:subClassOf hierarchy.
     * Starts from the node's rdf:type classes and walks up the class hierarchy.
     * Stops at the first ancestor class that has the predicate.
     * If the node itself has no types but is a class (has rdfs:subClassOf),
     * the search starts from the node itself.
     *
     * @param datasetGraph the dataset graph
     * @param node         the subject node
     * @param predicate    the predicate to search for
     * @param settings     fragment settings (for nested fragment building)
     * @param ctx          invocation context
     * @return set of object nodes found via inheritance
     */
    private static Set<Node> findAncestorValue(AticDatasetGraph datasetGraph, Node node,
                                                Node predicate, FragmentSettings settings,
                                                InvocationContext ctx) {
        Set<Node> results = new LinkedHashSet<>();
        Set<Node> ancestorClasses = new LinkedHashSet<>();
        Set<Node> currentLevel = new LinkedHashSet<>();

        // Collect initial classes from rdf:type.
        ExtendedIterator<Quad> typeIt = (ExtendedIterator<Quad>) datasetGraph.find(
                Node.ANY, node, RDF.type.asNode(), Node.ANY, ctx
        );
        try {
            while (typeIt.hasNext()) {
                Node t = typeIt.next().getObject();
                if (t.isURI()) {
                    currentLevel.add(t);
                }
            }
        } finally {
            typeIt.close();
        }

        // If no types found, check if the node itself is a class with rdfs:subClassOf.
        if (currentLevel.isEmpty()) {
            // Check if the node has outgoing rdfs:subClassOf (meaning it IS a class).
            ExtendedIterator<Quad> scoCheck = (ExtendedIterator<Quad>) datasetGraph.find(
                    Node.ANY, node, RDFS.subClassOf.asNode(), Node.ANY, ctx
            );
            try {
                if (scoCheck.hasNext()) {
                    // Node itself is a class — start from it.
                    currentLevel.add(node);
                }
            } finally {
                scoCheck.close();
            }
        }

        // If still no classes found, nothing to inherit from.
        if (currentLevel.isEmpty()) {
            return results;
        }

        // Walk the subClassOf hierarchy level by level.
        while (!currentLevel.isEmpty()) {
            Set<Node> nextLevel = new LinkedHashSet<>();

            for (Node clazz : currentLevel) {
                if (!ancestorClasses.add(clazz)) {
                    continue;
                }

                // Check if this ancestor has the predicate.
                ExtendedIterator<Quad> qIt = (ExtendedIterator<Quad>) datasetGraph.find(
                        Node.ANY, clazz, predicate, Node.ANY, ctx
                );
                try {
                    while (qIt.hasNext()) {
                        results.add(qIt.next().getObject());
                    }
                } finally {
                    qIt.close();
                }

                // If we found a value, stop searching further ancestors.
                if (!results.isEmpty()) {
                    break;
                }

                // Find subClassOf of this class for the next level.
                ExtendedIterator<Quad> scoIt = (ExtendedIterator<Quad>) datasetGraph.find(
                        Node.ANY, clazz, RDFS.subClassOf.asNode(), Node.ANY, ctx
                );
                try {
                    while (scoIt.hasNext()) {
                        Node parent = scoIt.next().getObject();
                        if (parent.isURI() && !ancestorClasses.contains(parent)) {
                            nextLevel.add(parent);
                        }
                    }
                } finally {
                    scoIt.close();
                }
            }

            if (!results.isEmpty()) {
                break;
            }

            currentLevel = nextLevel;
        }

        return results;
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
