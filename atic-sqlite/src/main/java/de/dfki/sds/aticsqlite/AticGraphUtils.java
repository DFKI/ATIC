

package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.Permission;
import de.dfki.sds.atic.ac.PermissionDeniedException;
import de.dfki.sds.atic.api.IdAndUri;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.shared.AddDeniedException;
import org.apache.jena.shared.DeleteDeniedException;

/**
 *
 */
/*package*/ class AticGraphUtils {

    //TODO bulkResolveResources can be optimized for performance
    public static void bulkResolveResources(
            Set<Node> nodes,
            InvocationContext ctx,
            Database db,
            boolean createIfMissing,
            boolean withPermission,
            Map<Node, Long> resourceCache,
            Map<Node, Long> predicateCache,
            Map<Node, Permission> permissionCache,
            SqliteAticDatasetGraph datasetGraph
    ) throws SQLException {

        nodes = nodes.stream()
                .filter(n -> !n.equals(Node.ANY))
                .collect(Collectors.toSet());

        Map<Node, String> bnode2uri = datasetGraph.getBnode2uri();

        for (Node n : nodes) {
            if (n.isBlank() && !bnode2uri.containsKey(n)) {
                bnode2uri.put(n, datasetGraph.createURN("blanknode"));
            }
        }

        List<Node> missing = nodes.stream()
                .filter(n -> !resourceCache.containsKey(n))
                .toList();

        if (missing.isEmpty()) {
            return;
        }

        List<Node> uriNodes = new ArrayList<>();
        List<Node> tripleNodes = new ArrayList<>();

        for (Node n : missing) {
            if (n.isTripleTerm()) {
                tripleNodes.add(n);
            } else if (n.isURI() || n.isBlank()) {
                uriNodes.add(n);
            }
        }

        // ---------------- URI / BLANK ----------------
        if (!uriNodes.isEmpty()) {

            Map<Node, String> nodeToKey = new HashMap<>();
            for (Node n : uriNodes) {
                String uri = n.isBlank() ? bnode2uri.get(n) : n.getURI();
                String key = sha256("U:" + uri);
                nodeToKey.put(n, key);
            }

            List<String> keys = new ArrayList<>(nodeToKey.values());
            String placeholders = keys.stream().map(k -> "?").collect(Collectors.joining(","));

            Map<String, Long> found = db.read(
                    "SELECT id, unique_key FROM resource WHERE unique_key IN (" + placeholders + ")",
                    rs -> {
                        Map<String, Long> map = new HashMap<>();
                        while (rs.next()) {
                            map.put(rs.getString("unique_key"), rs.getLong("id"));
                        }
                        return map;
                    },
                    keys.toArray()
            );

            for (Map.Entry<Node, String> e : nodeToKey.entrySet()) {
                if (found.containsKey(e.getValue())) {
                    resourceCache.put(e.getKey(), found.get(e.getValue()));
                }
            }

            List<Node> toInsert = uriNodes.stream()
                    .filter(n -> !resourceCache.containsKey(n))
                    .toList();

            if (createIfMissing && !toInsert.isEmpty()) {

                //write into resource to get generated ids
                //we use unique_key to deduplicate
                List<Object[]> batch = new ArrayList<>();
                for (Node n : toInsert) {
                    String uri = n.isBlank() ? bnode2uri.get(n) : n.getURI();
                    String key = sha256("U:" + uri);

                    batch.add(new Object[]{
                        key,
                        ctx.getUserId()
                    });
                }
                db.writeBatch("""
                INSERT OR IGNORE INTO resource (unique_key, creator)
                VALUES (?, ?)
            """, batch, 1000);

                //per key get the id
                List<String> insertKeys = toInsert.stream()
                        .map(n -> {
                            String uri = n.isBlank() ? bnode2uri.get(n) : n.getURI();
                            return sha256("U:" + uri);
                        })
                        .toList();
                String ph = insertKeys.stream().map(k -> "?").collect(Collectors.joining(","));
                Map<String, Long> inserted = db.read(
                        "SELECT id, unique_key FROM resource WHERE unique_key IN (" + ph + ")",
                        rs -> {
                            Map<String, Long> map = new HashMap<>();
                            while (rs.next()) {
                                map.put(rs.getString("unique_key"), rs.getLong("id"));
                            }
                            return map;
                        },
                        insertKeys.toArray()
                );

                //add resource_uri entries with the ids
                List<Object[]> uriBatch = new ArrayList<>();
                for (Node n : toInsert) {
                    String uri = n.isBlank() ? bnode2uri.get(n) : n.getURI();
                    String key = sha256("U:" + uri);
                    Long id = inserted.get(key);

                    resourceCache.put(n, id);

                    uriBatch.add(new Object[]{
                        id,
                        uri,
                        n.isBlank() ? 1 : 0
                    });
                }
                db.writeBatch("""
                INSERT OR IGNORE INTO resource_uri (id, uri, is_blank)
                VALUES (?, ?, ?)
            """, uriBatch, 1000);

                // ---------------------------------------
                // INSERT ACL
                // ---------------------------------------
                List<Object[]> aclBatch = new ArrayList<>();
                for (Node n : toInsert) {
                    Long id = resourceCache.get(n);
                    if (id != null) {
                        aclBatch.add(new Object[]{
                            id,
                            ctx.getPrimaryGroupId(),
                            Permission.ADMIN.getCode(),
                            ctx.getPrimaryGroupId()
                        });
                    }
                }

                db.writeBatch("""
                INSERT OR IGNORE INTO resource_acl
                (resource_id, group_id, permission, granted_by_group_id)
                VALUES (?, ?, ?, ?)
                """, aclBatch, 1000);
            }
        }

        // ---------------- TRIPLE TERMS ----------------
        if (!tripleNodes.isEmpty()) {

            Set<Node> resourceNodes = new HashSet<>();
            Set<Node> predicateNodes = new HashSet<>();

            for (Node n : tripleNodes) {
                Triple t = n.getTriple();

                resourceNodes.add(t.getSubject());
                if (!t.getObject().isLiteral()) {
                    resourceNodes.add(t.getObject());
                }

                if (t.getPredicate().isBlank()) {
                    throw new IllegalArgumentException("Predicate cannot be blank: " + t);
                }

                predicateNodes.add(t.getPredicate());
            }

            bulkResolveResources(resourceNodes, ctx, db, createIfMissing, withPermission, resourceCache, predicateCache, permissionCache, datasetGraph);
            bulkResolvePredicates(predicateNodes, ctx, db, predicateCache, datasetGraph);

            List<Node> spoNodes = new ArrayList<>();
            List<Node> splNodes = new ArrayList<>();

            for (Node n : tripleNodes) {
                if (n.getTriple().getObject().isLiteral()) {
                    splNodes.add(n);
                } else {
                    spoNodes.add(n);
                }
            }

            // ---------- SPO ----------
            if (!spoNodes.isEmpty()) {

                Map<Node, String> keyMap = new HashMap<>();

                for (Node n : spoNodes) {
                    Triple t = n.getTriple();

                    String key = sha256("T:"
                            + resourceCache.get(t.getSubject()) + "|"
                            + predicateCache.get(t.getPredicate()) + "|"
                            + resourceCache.get(t.getObject()));

                    keyMap.put(n, key);
                }

                List<String> keys = new ArrayList<>(keyMap.values());
                String placeholders = keys.stream().map(k -> "?").collect(Collectors.joining(","));

                Map<String, Long> found = db.read(
                        "SELECT id, unique_key FROM resource WHERE unique_key IN (" + placeholders + ")",
                        rs -> {
                            Map<String, Long> map = new HashMap<>();
                            while (rs.next()) {
                                map.put(rs.getString("unique_key"), rs.getLong("id"));
                            }
                            return map;
                        },
                        keys.toArray()
                );

                List<Node> toInsert = spoNodes.stream()
                        .filter(n -> !found.containsKey(keyMap.get(n)))
                        .toList();

                if (createIfMissing && !toInsert.isEmpty()) {

                    List<Object[]> batch = new ArrayList<>();

                    for (Node n : toInsert) {
                        batch.add(new Object[]{
                            keyMap.get(n),
                            ctx.getUserId()
                        });
                    }

                    db.writeBatch("""
                    INSERT OR IGNORE INTO resource (unique_key, creator)
                    VALUES (?, ?)
                """, batch, 1000);
                }

                Map<String, Long> inserted = db.read(
                        "SELECT id, unique_key FROM resource WHERE unique_key IN (" + placeholders + ")",
                        rs -> {
                            Map<String, Long> map = new HashMap<>();
                            while (rs.next()) {
                                map.put(rs.getString("unique_key"), rs.getLong("id"));
                            }
                            return map;
                        },
                        keys.toArray()
                );

                List<Object[]> spoBatch = new ArrayList<>();

                for (Node n : spoNodes) {
                    Triple t = n.getTriple();

                    Long id = inserted.get(keyMap.get(n));
                    resourceCache.put(n, id);

                    spoBatch.add(new Object[]{
                        id,
                        resourceCache.get(t.getSubject()),
                        predicateCache.get(t.getPredicate()),
                        resourceCache.get(t.getObject())
                    });
                }

                db.writeBatch("""
                INSERT OR IGNORE INTO resource_spo (id, s, p, o)
                VALUES (?, ?, ?, ?)
            """, spoBatch, 1000);
            }

            // ---------- SPL ----------
            if (!splNodes.isEmpty()) {

                Map<Node, String> keyMap = new HashMap<>();

                for (Node n : splNodes) {
                    Triple t = n.getTriple();
                    Node o = t.getObject();

                    String key = sha256("TL:"
                            + resourceCache.get(t.getSubject()) + "|"
                            + predicateCache.get(t.getPredicate()) + "|"
                            + o.getLiteralLexicalForm() + "|"
                            + o.getLiteralLanguage() + "|"
                            + o.getLiteralDatatypeURI());

                    keyMap.put(n, key);
                }

                List<String> keys = new ArrayList<>(keyMap.values());
                String placeholders = keys.stream().map(k -> "?").collect(Collectors.joining(","));

                Map<String, Long> found = db.read(
                        "SELECT id, unique_key FROM resource WHERE unique_key IN (" + placeholders + ")",
                        rs -> {
                            Map<String, Long> map = new HashMap<>();
                            while (rs.next()) {
                                map.put(rs.getString("unique_key"), rs.getLong("id"));
                            }
                            return map;
                        },
                        keys.toArray()
                );

                List<Node> toInsert = splNodes.stream()
                        .filter(n -> !found.containsKey(keyMap.get(n)))
                        .toList();

                if (createIfMissing && !toInsert.isEmpty()) {

                    List<Object[]> batch = new ArrayList<>();

                    for (Node n : toInsert) {
                        batch.add(new Object[]{
                            keyMap.get(n),
                            ctx.getUserId()
                        });
                    }

                    db.writeBatch("""
                    INSERT OR IGNORE INTO resource (unique_key, creator)
                    VALUES (?, ?)
                """, batch, 1000);
                }

                Map<String, Long> inserted = db.read(
                        "SELECT id, unique_key FROM resource WHERE unique_key IN (" + placeholders + ")",
                        rs -> {
                            Map<String, Long> map = new HashMap<>();
                            while (rs.next()) {
                                map.put(rs.getString("unique_key"), rs.getLong("id"));
                            }
                            return map;
                        },
                        keys.toArray()
                );

                List<Object[]> splBatch = new ArrayList<>();

                for (Node n : splNodes) {
                    Triple t = n.getTriple();
                    Node o = t.getObject();

                    Long id = inserted.get(keyMap.get(n));
                    resourceCache.put(n, id);

                    splBatch.add(new Object[]{
                        id,
                        resourceCache.get(t.getSubject()),
                        predicateCache.get(t.getPredicate()),
                        o.getLiteralLexicalForm(),
                        o.getLiteralLanguage(),
                        o.getLiteralDatatypeURI()
                    });
                }

                db.writeBatch("""
                INSERT OR IGNORE INTO resource_spl (id, s, p, lex, lang, dt)
                VALUES (?, ?, ?, ?, ?, ?)
            """, splBatch, 1000);
            }
        }

        if (withPermission && !resourceCache.isEmpty()) {

            Set<Integer> groups = ctx.getGroupIds();

            Map<Long, Node> idToNode = resourceCache.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

            String idPlaceholders = idToNode.keySet().stream()
                    .map(id -> "?")
                    .collect(Collectors.joining(","));

            String groupPlaceholders = groups.stream()
                    .map(g -> "?")
                    .collect(Collectors.joining(","));

            String permSql = """
        WITH acl AS (
            SELECT resource_id, MAX(permission) AS perm
            FROM resource_acl
            WHERE group_id IN ( %s )
            GROUP BY resource_id
        )
        SELECT r.id,
               CASE
                   -- normal resource
                   WHEN rspo.id IS NULL AND rspl.id IS NULL THEN base.perm

                   -- SPO triple → MIN(s, o)
                   WHEN rspo.id IS NOT NULL THEN
                       MIN(
                           COALESCE(s_perm.perm, 0),
                           COALESCE(o_perm.perm, 0)
                       )

                   -- SPL triple → only s
                   WHEN rspl.id IS NOT NULL THEN
                       COALESCE(spl_perm.perm, 0)
               END AS effective_perm

        FROM resource r

        -- detect triple type
        LEFT JOIN resource_spo rspo ON rspo.id = r.id
        LEFT JOIN resource_spl rspl ON rspl.id = r.id

        -- base permission (normal resource)
        LEFT JOIN acl base ON base.resource_id = r.id

        -- inner permissions for SPO
        LEFT JOIN acl s_perm ON s_perm.resource_id = rspo.s
        LEFT JOIN acl o_perm ON o_perm.resource_id = rspo.o

        -- inner permission for SPL
        LEFT JOIN acl spl_perm ON spl_perm.resource_id = rspl.s

        WHERE r.id IN ( %s )
        GROUP BY r.id
        """.formatted(groupPlaceholders, idPlaceholders);

            List<Object> params = new ArrayList<>();
            params.addAll(groups);          // for acl CTE
            params.addAll(idToNode.keySet()); // for WHERE r.id IN (...)

            Map<Long, Permission> perms = db.read(permSql, rs -> {
                Map<Long, Permission> map = new HashMap<>();
                while (rs.next()) {
                    map.put(
                            rs.getLong("id"),
                            Permission.fromCode(rs.getInt("effective_perm"))
                    );
                }
                return map;
            }, params.toArray());

            for (Map.Entry<Long, Node> e : idToNode.entrySet()) {
                Permission p = perms.get(e.getKey());
                if (p != null) {
                    permissionCache.put(e.getValue(), p);
                }
            }
        }
    }

    public static void bulkResolvePredicates(
            Set<Node> nodes,
            InvocationContext ctx,
            Database db,
            Map<Node, Long> cache, 
            SqliteAticDatasetGraph datasetGraph) throws SQLException {

        Map<Node, String> bnode2uri = datasetGraph.getBnode2uri();

        // Assign URNs to blank nodes just like resources
        for (Node n : nodes) {
            if (n.isBlank() && !bnode2uri.containsKey(n)) {
                bnode2uri.put(n, datasetGraph.createURN("blanknode"));
            }
        }

        // Which predicates still need resolution?
        List<Node> missing = nodes.stream()
                .filter(u -> !cache.containsKey(u))
                .toList();

        if (missing.isEmpty()) {
            return;
        }

        // Build list of URIs to SELECT (skolemized for blank nodes)
        List<String> urisToSelect = missing.stream()
                .map(n -> n.isBlank() ? bnode2uri.get(n) : n.getURI())
                .toList();

        String sql = "SELECT id, uri FROM property WHERE uri IN ("
                + urisToSelect.stream().map(u -> "?").collect(Collectors.joining(","))
                + ")";

        Map<String, Long> found = db.read(sql, rs -> {
            Map<String, Long> map = new HashMap<>();
            while (rs.next()) {
                map.put(rs.getString("uri"), rs.getLong("id"));
            }
            return map;
        }, urisToSelect.toArray());

        // Populate Node→ID cache
        for (Node n : missing) {
            String uri = n.isBlank() ? bnode2uri.get(n) : n.getURI();
            if (found.containsKey(uri)) {
                cache.put(n, found.get(uri));
            }
        }

        // Determine which nodes still need insertion
        Set<Node> toBeResolved = new HashSet<>();
        List<Object[]> insertBatch = new ArrayList<>();

        for (Node node : missing) {
            if (!cache.containsKey(node)) {
                String uri = node.isBlank() ? bnode2uri.get(node) : node.getURI();
                toBeResolved.add(node);
                insertBatch.add(new Object[]{uri, ctx.getUserId()});
            }
        }

        // Insert missing predicates & recurse
        if (!insertBatch.isEmpty()) {
            db.writeBatch("""
            INSERT OR IGNORE INTO property
            (uri, creator)
            VALUES (?, ?)
        """, insertBatch, 1000);

            // After insert, ensure they really got resolved
            bulkResolvePredicates(toBeResolved, ctx, db, cache, datasetGraph);
        }
    }

    
    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String s = Integer.toHexString(0xff & b);
                if (s.length() == 1) {
                    hex.append('0'); // zero-pad
                }
                hex.append(s);
            }
            return hex.toString();

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
    
    
    public static long graphMustBeUniquelyIdentified(List<IdAndUri> idAndUris, boolean isAdd) {
        // the graph must be uniquely identified
        if (idAndUris == null || idAndUris.size() != 1) {

            String msg = "Unable to determine target graph";
            if (isAdd) {
                throw new AddDeniedException(msg);
            } else {
                throw new DeleteDeniedException(msg);
            }
        }
        return idAndUris.get(0).getId();
    }

    public static void checkGraphPermission(
            List<IdAndUri> idAndUris,
            Permission required,
            Database db,
            InvocationContext ctx,
            SqliteAticDatasetGraph datasetGraph
    ) throws SQLException {

        if (ctx.isEmpty()) {
            throw new IllegalArgumentException("Empty Invocation Context");
        }

        //for admin user we need no graph permission check
        if (datasetGraph.isAdmin(ctx)) {
            return;
        }

        Set<Integer> grpIds = ctx.getGroupIds();

        for (IdAndUri graphInfo : idAndUris) {

            long graphId = graphInfo.getId();

            StringBuilder sqlPerm = new StringBuilder()
                    .append("SELECT MAX(permission) FROM graph_acl WHERE graph_id = ?");

            if (!grpIds.isEmpty()) {
                sqlPerm.append(" AND group_id IN (")
                        .append(grpIds.stream().map(g -> "?").collect(Collectors.joining(",")))
                        .append(")");
            }

            List<Object> permParams = new ArrayList<>();
            permParams.add(graphId);
            permParams.addAll(grpIds.stream().map(g -> (Object) g).toList());

            Integer maxPermCode = db.read(
                    sqlPerm.toString(),
                    rs -> rs.next() ? rs.getInt(1) : null,
                    permParams.toArray()
            );

            Permission effective = (maxPermCode == null)
                    ? null
                    : Permission.fromCode(maxPermCode);

            if (effective == null || effective.getCode() < required.getCode()) {
                throw new PermissionDeniedException(
                        "graph",
                        graphId,
                        graphInfo.getUri(),
                        required,
                        (effective == null) ? Set.of() : Set.of(effective)
                );
            }
        }
    }

    public static void valid(Triple t) {

        Node s = t.getSubject();
        Node p = t.getPredicate();
        Node o = t.getObject();

        valid(s, p, o);
    }

    public static void valid(Node s, Node p, Node o) {

        // -------------------------
        // SUBJECT (allow ANY)
        // -------------------------
        if (!s.equals(Node.ANY)
                && !(s.isURI() || s.isBlank() || s.isTripleTerm())) {
            throw new IllegalArgumentException("Invalid subject: " + s);
        }

        // -------------------------
        // PREDICATE (allow ANY)
        // -------------------------
        if (!p.equals(Node.ANY) && !p.isURI()) {
            throw new IllegalArgumentException("Predicate must be URI: " + p);
        }

        // -------------------------
        // OBJECT (allow ANY)
        // -------------------------
        if (!o.equals(Node.ANY)
                && !(o.isURI() || o.isBlank() || o.isLiteral() || o.isTripleTerm())) {
            throw new IllegalArgumentException("Invalid object: " + o);
        }

        // =========================
        // RDF-STAR SUBJECT
        // =========================
        if (!s.equals(Node.ANY) && s.isTripleTerm()) {
            validateTripleTerm(s.getTriple(), "subject");
        }

        // =========================
        // RDF-STAR OBJECT
        // =========================
        if (!o.equals(Node.ANY) && o.isTripleTerm()) {
            validateTripleTerm(o.getTriple(), "object");
        }
    }

    public static void validateTripleTerm(Triple inner, String position) {

        Node s = inner.getSubject();
        Node p = inner.getPredicate();
        Node o = inner.getObject();

        // -------------------------
        // SUBJECT (allow ANY)
        // -------------------------
        if (!s.equals(Node.ANY) && !s.isURI()) {
            throw new IllegalArgumentException(
                    "TripleTerm at " + position + " position, subject must be URI or ANY: " + inner);
        }

        // -------------------------
        // PREDICATE (allow ANY)
        // -------------------------
        if (!p.equals(Node.ANY) && !p.isURI()) {
            throw new IllegalArgumentException(
                    "TripleTerm at " + position + " position, predicate must be URI or ANY: " + inner);
        }

        // -------------------------
        // OBJECT (allow ANY)
        // -------------------------
        if (!o.equals(Node.ANY)
                && !(o.isURI() || o.isLiteral())) {
            throw new IllegalArgumentException(
                    "TripleTerm at " + position + " position, object must be URI, literal or ANY: " + inner);
        }

        // -------------------------
        // FORBID NESTED TRIPLES
        // -------------------------
        if ((s.isTripleTerm() && !s.equals(Node.ANY))
                || (p.isTripleTerm() && !p.equals(Node.ANY))
                || (o.isTripleTerm() && !o.equals(Node.ANY))) {

            throw new IllegalArgumentException(
                    "Nested triple terms not allowed in " + position + " position: " + inner);
        }
    }

    public static Node ifNullToNodeANY(Node n) {
        if (n == null) {
            return Node.ANY;
        }
        return n;
    }
    
}
