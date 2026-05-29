package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.Permission;
import de.dfki.sds.atic.ac.PermissionDeniedException;
import de.dfki.sds.atic.api.IdAndUri;
import de.dfki.sds.atic.api.IdAndUriOrLiteral;
import de.dfki.sds.atic.api.IdAndUriQuad;
import de.dfki.sds.atic.api.IdAndUriTriple;
import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.AticTriple;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.GraphEventManager;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.TransactionHandler;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.impl.GraphMatcher;
import org.apache.jena.graph.impl.LiteralLabel;
import org.apache.jena.graph.impl.SimpleEventManager;
import org.apache.jena.graph.impl.TransactionHandlerBase;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.shared.AddDeniedException;
import org.apache.jena.shared.DeleteDeniedException;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.graph.PrefixMappingAdapter;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.util.iterator.WrappedIterator;

/**
 *
 */
public class SqliteAticGraph implements AticGraph {

    //the parent
    private SqliteAticDatasetGraph datasetGraph;

    private List<IdAndUri> idAndUris;

    private TransactionHandler transactionHandler;
    private GraphEventManager graphEventManager;

    private Map<QueryKey, String> queryCache;

    //used in find ResourceResolver
    private Map<String, Long> resourceUriIdCache;
    private Map<String, Long> propertyUriIdCache;

    //TODO later better ontology vocabulary
    public static final Node ATIC_CONFIDENCE = NodeFactory.createURI("urn:atic:confidence");

    //TODO later solve with logger
    public static boolean PRINT_FIND = false;

    //we use static so it is easier to manipulate it instance-independent
    private static int defaultBufferSize = 100_000;
    private static int defaultBatchSize = 100_000;

    //used for LRU resolve cache (uri -> id)
    //TODO should later be settings coming from SqliteAticDatasetGraph
    private static int defaultResolveCacheInitialCapacity = 16_384;
    private static float defaultResolveCacheLoadFactor = 0.75f;
    private static int defaultResolveCacheSize = 100_000;

    private Map<Integer, TransactionData> user2trans;

    public SqliteAticGraph(List<IdAndUri> idAndUris, SqliteAticDatasetGraph datasetGraph) {
        if (idAndUris == null || idAndUris.isEmpty()) {
            throw new IllegalArgumentException("idAndUris must have at least one graph");
        }
        this.idAndUris = idAndUris;
        this.datasetGraph = datasetGraph;

        SqliteAticGraph thisGraph = this;
        transactionHandler = new TransactionHandlerBase() {

            @Override
            public boolean transactionsSupported() {
                return true;
            }

            @Override
            public void begin() {
                datasetGraph.begin();
                thisGraph.begin();
            }

            @Override
            public void abort() {
                datasetGraph.abort();
                thisGraph.abort();
            }

            @Override
            public void commit() {
                datasetGraph.commit();
                thisGraph.commit();
            }
        };

        graphEventManager = new SimpleEventManager();

        user2trans = new HashMap<>();
        queryCache = new HashMap<>();

        //so it is bound and memory is free
        resourceUriIdCache = new LinkedHashMap<String, Long>(defaultResolveCacheInitialCapacity, defaultResolveCacheLoadFactor, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > defaultResolveCacheSize;
            }
        };
        propertyUriIdCache = new LinkedHashMap<String, Long>(defaultResolveCacheInitialCapacity, defaultResolveCacheLoadFactor, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > defaultResolveCacheSize;
            }
        };
    }

    //======================================
    //CR(U)D
    private class TransactionData {

        int bufferSize;
        int batchSize;

        Map<Node, Long> resourceCache;
        Map<Node, Permission> permissionCache;
        Map<Node, Long> predicateCache;

        List<Triple> buffer;

        long graphId;

        boolean enableAC;
        InvocationContext ctx;
        
        boolean graphPermissionChecked = false;

        public TransactionData(InvocationContext ctx) {
            this.ctx = ctx;

            graphId = graphMustBeUniquelyIdentified(true);

            this.bufferSize = defaultBufferSize;
            this.batchSize = defaultBatchSize;

            resourceCache = new HashMap<>(bufferSize * 2);
            permissionCache = new HashMap<>(bufferSize * 2);
            predicateCache = new HashMap<>(bufferSize);

            buffer = new ArrayList<>(bufferSize);

            enableAC = !datasetGraph.isAdmin(ctx);
        }

        public void flush() {
            //check once because of performance
            if(!graphPermissionChecked) {
                try {
                    checkGraphPermission(Permission.EDIT, datasetGraph.getDatabase(), ctx);
                } catch (SQLException ex) {
                    throw new RuntimeException("DB error", ex);
                }
                graphPermissionChecked = true;
            }

            try {
                processBuffer(
                        this.buffer,
                        this.ctx,
                        datasetGraph.getDatabase(),
                        this.resourceCache,
                        this.permissionCache,
                        this.predicateCache,
                        this.graphId,
                        this.batchSize,
                        this.enableAC);
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
            this.buffer.clear();
        }

    }

    /*package*/ void begin() {
        user2trans.clear();
    }

    /*package*/ void abort() {
        user2trans.clear();
    }

    /*package*/ void commit() {
        for (TransactionData td : user2trans.values()) {
            td.flush();
        }
        user2trans.clear();
    }

    @Override
    public void add(Triple t, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, datasetGraph.getContext());

        valid(t);

        //per user transaction data
        InvocationContext finalCtx = ctx;
        //note: we use user id as key which is not so clean if user get group change during transaction
        TransactionData transactionData = user2trans.computeIfAbsent(ctx.getUserId(), uid -> new TransactionData(finalCtx));

        transactionData.buffer.add(t);

        if (transactionData.buffer.size() >= transactionData.bufferSize) {
            transactionData.flush();
        }
    }

    //optimized for a lot of triples
    //TODO parallelism is not used, we get around 150_000 triples per second
    public void add(Iterator<Triple> iter,
            InvocationContext ctx,
            int bufferSize,
            int batchSize,
            int parallelism) {

        ctx = InvocationContext.fromContextIfEmpty(ctx, datasetGraph.getContext());
        long graphId = graphMustBeUniquelyIdentified(true);

        try {
            checkGraphPermission(Permission.EDIT, datasetGraph.getDatabase(), ctx);
        } catch (SQLException ex) {
            throw new RuntimeException("DB error", ex);
        }

        Database db = datasetGraph.getDatabase();

        Map<Node, Long> resourceCache = new HashMap<>(bufferSize * 2);
        Map<Node, Permission> permissionCache = new HashMap<>(bufferSize * 2);
        Map<Node, Long> predicateCache = new HashMap<>(bufferSize);

        List<Triple> buffer = new ArrayList<>(bufferSize);

        boolean enableAC = !datasetGraph.isAdmin(ctx);

        while (iter.hasNext()) {
            buffer.clear();

            // ---------------------------
            // fill buffer
            // ---------------------------
            for (int i = 0; i < bufferSize && iter.hasNext(); i++) {
                Triple t = iter.next();

                valid(t);
                if (!t.isConcrete()) {
                    throw new IllegalArgumentException("Triple has to be concrete: " + t);
                }

                buffer.add(t);
            }

            try {
                processBuffer(buffer, ctx, db, resourceCache, permissionCache, predicateCache, graphId, batchSize, enableAC);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void processBuffer(List<Triple> triples,
            InvocationContext ctx,
            Database db,
            Map<Node, Long> resourceCache,
            Map<Node, Permission> permissionCache,
            Map<Node, Long> predicateCache,
            long graphId,
            int batchSize,
            boolean enableAC) throws SQLException {

        // ---------------------------------------
        // 1. Collect all URIs (subjects + objects + predicates)
        // ---------------------------------------
        Set<Node> resourceNodes = new HashSet<>();
        Set<Node> predicateNodes = new HashSet<>();

        for (Triple t : triples) {
            //check valid triple and would throw exception if invalid
            valid(t);

            // Collect subjects and objects, including blank nodes
            resourceNodes.add(t.getSubject());

            if (!t.getObject().isLiteral()) {
                resourceNodes.add(t.getObject());
            }

            predicateNodes.add(t.getPredicate());
        }

        // ---------------------------------------
        // 2. Resolve resources in bulk
        // ---------------------------------------
        bulkResolveResources(resourceNodes, ctx, db, true, enableAC, resourceCache, predicateCache, permissionCache);

        // ---------------------------------------
        // 3. Resolve predicates in bulk
        // ---------------------------------------
        bulkResolvePredicates(predicateNodes, ctx, db, predicateCache);

        // ---------------------------------------
        // 4. Prepare batch inserts
        // ---------------------------------------
        List<Object[]> splgBatch = new ArrayList<>();
        List<Object[]> spogBatch = new ArrayList<>();

        for (Triple t : triples) {

            long s = resourceCache.get(t.getSubject());
            long p = predicateCache.get(t.getPredicate());

            double confidence = 1.0;
            if (t instanceof AticTriple) {
                confidence = ((AticTriple) t).getConfidence();
            }

            Node obj = t.getObject();

            if (obj.isLiteral()) {

                if (enableAC) {
                    //permission check
                    Permission sPerm = permissionCache.get(t.getSubject());
                    //no permission at all case
                    if (sPerm == null) {
                        throw new PermissionDeniedException("resource", s, String.valueOf(t.getSubject()), Permission.EDIT, Set.of());
                    }
                    if (sPerm.getCode() < Permission.EDIT.getCode()) {
                        throw new PermissionDeniedException("resource", s, String.valueOf(t.getSubject()), Permission.EDIT, Set.of(sPerm));
                    }
                }

                LiteralLabel lit = obj.getLiteral();

                splgBatch.add(new Object[]{
                    s,
                    p,
                    graphId,
                    lit.getLexicalForm(),
                    lit.language(),
                    lit.getDatatypeURI(),
                    confidence,
                    ctx.getUserId()
                });

                //TODO RDFPatchEmitter emits also triple term
                if (datasetGraph.getRDFPatchEmitter().hasListeners() && !t.getSubject().isTripleTerm()) {

                    Map<Node, String> bnode2uri = datasetGraph.getBnode2uri();

                    Node subject = t.getSubject();
                    if (bnode2uri.containsKey(subject)) {
                        subject = NodeFactory.createURI(bnode2uri.get(subject));
                    }
                    //obj is literal, no need for bnode check

                    IdAndUriTriple idAndUriTriple = IdAndUriTriple.create(
                            IdAndUri.create(s, subject),
                            IdAndUri.create(p, t.getPredicate()),
                            IdAndUriOrLiteral.create(obj)
                    );
                    IdAndUriQuad idAndUriQuad = IdAndUriQuad.create(idAndUris.get(0), idAndUriTriple);
                    idAndUriQuad.getTriple().setConfidence(confidence);

                    datasetGraph.getRDFPatchEmitter().add(idAndUriQuad, ctx);
                }

            } else {
                long o = resourceCache.get(obj);

                if (enableAC) {
                    //permission check
                    Permission sPerm = permissionCache.get(t.getSubject());
                    Permission oPerm = permissionCache.get(t.getObject());

                    //no permission at all case
                    if (sPerm == null) {
                        throw new PermissionDeniedException("resource", s, String.valueOf(t.getSubject()), Permission.EDIT, Set.of());
                    }
                    if (oPerm == null) {
                        throw new PermissionDeniedException("resource", o, String.valueOf(t.getObject()), Permission.EDIT, Set.of());
                    }

                    if (sPerm.getCode() < Permission.EDIT.getCode() && oPerm.getCode() < Permission.EDIT.getCode()) {
                        throw new PermissionDeniedException("resource", s, String.valueOf(t.getSubject()), Permission.EDIT, Set.of(sPerm));
                    }
                }

                spogBatch.add(new Object[]{
                    s,
                    p,
                    o,
                    graphId,
                    confidence,
                    ctx.getUserId()
                });

                //TODO RDFPatchEmitter emits also triple term
                if (datasetGraph.getRDFPatchEmitter().hasListeners() && !t.getSubject().isTripleTerm() && !t.getObject().isTripleTerm()) {
                    Map<Node, String> bnode2uri = datasetGraph.getBnode2uri();

                    Node subject = t.getSubject();
                    if (bnode2uri.containsKey(subject)) {
                        subject = NodeFactory.createURI(bnode2uri.get(subject));
                    }
                    if (bnode2uri.containsKey(obj)) {
                        obj = NodeFactory.createURI(bnode2uri.get(obj));
                    }

                    IdAndUriTriple idAndUriTriple = IdAndUriTriple.create(
                            IdAndUri.create(s, subject),
                            IdAndUri.create(p, t.getPredicate()),
                            IdAndUriOrLiteral.create(o, obj)
                    );
                    IdAndUriQuad idAndUriQuad = IdAndUriQuad.create(idAndUris.get(0), idAndUriTriple);
                    idAndUriQuad.getTriple().setConfidence(confidence);

                    datasetGraph.getRDFPatchEmitter().add(idAndUriQuad, ctx);
                }
            }
        }

        // ---------------------------------------
        // 5. Execute batch writes
        // ---------------------------------------
        db.writeBatch("""
        INSERT OR IGNORE INTO splg
        (s, p, g, lex, lang, dt, confidence, creator)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, splgBatch, batchSize);

        db.writeBatch("""
        INSERT OR IGNORE INTO spog
        (s, p, o, g, confidence, creator)
        VALUES (?, ?, ?, ?, ?, ?)
        """, spogBatch, batchSize);
    }

    //TODO bulkResolveResources can be optimized for performance
    private void bulkResolveResources(
            Set<Node> nodes,
            InvocationContext ctx,
            Database db,
            boolean createIfMissing,
            boolean withPermission,
            Map<Node, Long> resourceCache,
            Map<Node, Long> predicateCache,
            Map<Node, Permission> permissionCache
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

            bulkResolveResources(resourceNodes, ctx, db, createIfMissing, withPermission, resourceCache, predicateCache, permissionCache);
            bulkResolvePredicates(predicateNodes, ctx, db, predicateCache);

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

    private void bulkResolvePredicates(Set<Node> nodes,
            InvocationContext ctx,
            Database db,
            Map<Node, Long> cache) throws SQLException {

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
            bulkResolvePredicates(toBeResolved, ctx, db, cache);
        }
    }

    public StreamRDF asStreamRDF(InvocationContext ctx,
            int bufferSize,
            int batchSize,
            int parallelism) {
        return new StreamRDF() {

            private InvocationContext _ctx;
            private Map<Node, Long> resourceCache;
            private Map<Node, Permission> permissionCache;
            private Map<Node, Long> predicateCache;
            private List<Triple> buffer;

            private Database db;

            private long graphId;

            private boolean enableAC;

            @Override
            public void start() {
                _ctx = InvocationContext.fromContextIfEmpty(ctx, datasetGraph.getContext());
                graphId = graphMustBeUniquelyIdentified(true);

                try {
                    checkGraphPermission(Permission.EDIT, datasetGraph.getDatabase(), _ctx);
                } catch (SQLException ex) {
                    throw new RuntimeException("DB error", ex);
                }

                db = datasetGraph.getDatabase();

                // transaction MUST already be open outside
                resourceCache = new HashMap<>(bufferSize * 2);
                permissionCache = new HashMap<>(bufferSize * 2);
                predicateCache = new HashMap<>(bufferSize);

                buffer = new ArrayList<>(bufferSize);

                enableAC = !datasetGraph.isAdmin(ctx);
            }

            @Override
            public void triple(Triple triple) {
                buffer.add(triple);
                flush(true);
            }

            private void flush(boolean ifBufferIsFull) {
                if (ifBufferIsFull && buffer.size() < bufferSize) {
                    return;
                }

                try {
                    processBuffer(buffer, _ctx, db, resourceCache, permissionCache, predicateCache, graphId, batchSize, enableAC);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }

                buffer.clear();
            }

            @Override
            public void quad(Quad quad) {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public void base(String base) {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public void prefix(String prefix, String iri) {
                //TODO for now ignored so that RDFParser works
            }

            @Override
            public void finish() {
                //write remaining
                flush(false);

                resourceCache.clear();
                predicateCache.clear();
                buffer.clear();
            }
        };
    }

    //delete reuses remove
    @Override
    public void delete(Triple t, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, datasetGraph.getContext());

        if (!t.isConcrete()) {
            throw new IllegalArgumentException("Triple has to be concrete: " + t);
        }

        graphMustBeUniquelyIdentified(false);

        //add extra check that graphs in idAndUris are at least edit permission
        try {
            checkGraphPermission(Permission.EDIT, datasetGraph.getDatabase(), ctx);
        } catch (SQLException ex) {
            throw new RuntimeException("DB error", ex);
        }

        //we reuse remove, so only one method to maintain
        remove(t.getSubject(), t.getPredicate(), t.getObject(), ctx);
    }

    //discussion: will remove spog/splg but not resource/property
    @Override
    public void remove(Node s, Node p, Node o, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, datasetGraph.getContext());

        s = ifNullToNodeANY(s);
        p = ifNullToNodeANY(p);
        o = ifNullToNodeANY(o);
        valid(s, p, o);

        graphMustBeUniquelyIdentified(false);

        //check permission
        Permission required = Permission.EDIT;
        //for Node.ANY,Node.ANY,Node.ANY also require ADMIN permission (see clear method idea)
        if (Node.ANY.equals(s) && Node.ANY.equals(p) && Node.ANY.equals(o)) {
            required = Permission.ADMIN;
        }
        //add extra check that graphs in idAndUris are at least edit permission
        try {
            checkGraphPermission(required, datasetGraph.getDatabase(), ctx);
        } catch (SQLException ex) {
            throw new RuntimeException("DB error", ex);
        }

        removeSPO(s, p, o, ctx);
        removeSPL(s, p, o, ctx);
    }

    private void removeSPO(Node s, Node p, Node o, InvocationContext ctx) {
        // literals are never stored in the SPO table
        if (o.isLiteral()) {
            return;
        }

        boolean enableAC = !datasetGraph.isAdmin(ctx);

        //to have the check like in delete method
        if (enableAC && s.isConcrete() && o.isConcrete()) {
            Database db = datasetGraph.getDatabase();

            Map<Node, Long> resourceCache = new HashMap<>();
            Map<Node, Long> predicateCache = new HashMap<>();
            Map<Node, Permission> permissionCache = new HashMap<>();
            try {
                bulkResolveResources(Set.of(s, o), ctx, db, false, true, resourceCache, predicateCache, permissionCache);
            } catch (SQLException ex) {
                throw new RuntimeException("bulkResolveResources error", ex);
            }

            Long subjId = resourceCache.get(s);
            Permission sPerm = permissionCache.get(s);
            Permission oPerm = permissionCache.get(o);

            if (sPerm.getCode() <= Permission.REFER.getCode()
                    && oPerm.getCode() <= Permission.REFER.getCode()) {
                throw new PermissionDeniedException("resource", subjId, s.getURI(), Permission.EDIT, Set.of(sPerm));
            }
        }

        String prefixQuery = """
                             WITH eligible AS (
                             SELECT spog.id
                             """;

        String postfixQuery = """
                              )
                              DELETE FROM spog
                              WHERE id IN (
                                  SELECT id
                                  FROM eligible
                              )
                              """;

        QueryWithParams queryWithParams = buildQuery(
                "remove",
                false,
                prefixQuery, postfixQuery,
                s, p, o,
                null, null, false,
                true,
                ctx);

        if (queryWithParams == null) {
            return;
        }

        //we need to know what will be removed, so we find it
        ExtendedIterator<Triple> iter = findSPO(s, p, o, null, null, true, (dbTriple) -> {
            //we emit that this will be the deleted triples
            if (datasetGraph.getRDFPatchEmitter().hasListeners()) {
                datasetGraph.getRDFPatchEmitter().delete(IdAndUriQuad.create(idAndUris.get(0), dbTriple), ctx);
            }
        }, ctx);
        //we need to iterate to invoke the dbTriple consumer
        while (iter.hasNext()) {
            iter.next();
        }

        try {
            Database db = datasetGraph.getDatabase();
            db.write(queryWithParams.sql, queryWithParams.params.toArray());

        } catch (SQLException ex) {
            //System.out.println(queryWithParams.sql);
            //System.out.println(queryWithParams.params);
            throw new RuntimeException("Database error while removing SPO triple(s)", ex);
        }
    }

    private void removeSPL(Node s, Node p, Node o, InvocationContext ctx) {
        // literals are the only objects stored in the SPL table
        if (o.isURI()) {
            return;
        }

        boolean enableAC = !datasetGraph.isAdmin(ctx);

        //to have the check like in delete method
        if (enableAC && s.isConcrete()) {
            Database db = datasetGraph.getDatabase();

            Map<Node, Long> resourceCache = new HashMap<>();
            Map<Node, Long> predicateCache = new HashMap<>();
            Map<Node, Permission> permissionCache = new HashMap<>();
            try {
                bulkResolveResources(Set.of(s), ctx, db, false, true, resourceCache, predicateCache, permissionCache);
            } catch (SQLException ex) {
                throw new RuntimeException("bulkResolveResources", ex);
            }

            Permission sPerm = permissionCache.get(s);

            if (sPerm.getCode() <= Permission.REFER.getCode()) {
                Long subjId = resourceCache.get(s);
                throw new PermissionDeniedException("resource", subjId, s.getURI(), Permission.EDIT, Set.of(sPerm));
            }
        }

        String prefixQuery = """
                             WITH eligible AS (
                             SELECT splg.id
                             """;

        String postfixQuery = """
                              )
                              DELETE FROM splg
                              WHERE id IN (
                                  SELECT id
                                  FROM eligible
                              )
                              """;

        QueryWithParams queryWithParams = buildQuery(
                "remove",
                true,
                prefixQuery, postfixQuery,
                s, p, o,
                null, null, false,
                true,
                ctx);

        if (queryWithParams == null) {
            return;
        }

        //we need to know what will be removed, so we find it
        ExtendedIterator<Triple> iter = findSPL(s, p, o, null, null, true, (dbTriple) -> {
            //we emit that this will be the deleted triples
            if (datasetGraph.getRDFPatchEmitter().hasListeners()) {
                datasetGraph.getRDFPatchEmitter().delete(IdAndUriQuad.create(idAndUris.get(0), dbTriple), ctx);
            }
        }, ctx);
        //we need to iterate to invoke the dbTriple consumer
        while (iter.hasNext()) {
            iter.next();
        }

        try {
            Database db = datasetGraph.getDatabase();
            db.write(queryWithParams.sql, queryWithParams.params.toArray());

        } catch (SQLException ex) {
            //System.out.println(queryWithParams.sql);
            //System.out.println(queryWithParams.params);
            throw new RuntimeException("Database error while removing SPL triple(s)", ex);
        }
    }

    //clear reuses remove with Node.ANY, Node.ANY, Node.ANY
    @Override
    public void clear(InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, datasetGraph.getContext());

        //user needs to have admin permission to do that, just for safty reason
        //note: the ADMIN check is done in remove when Node.ANY, Node.ANY, Node.ANY
        //maybe removes splg/spog but not resource/property (see discussion on delete method)
        remove(Node.ANY, Node.ANY, Node.ANY, ctx);
    }

    private class GraphFilter {

        List<Long> graphIds;
        String graphClause;

        public GraphFilter(boolean isSPL) {

            String tableName = isSPL ? "splg" : "spog";

            // graph filter
            graphIds = idAndUris.stream()
                    .map(IdAndUri::getId)
                    .collect(Collectors.toList());

            StringBuilder graphClauseSB = new StringBuilder();
            if (graphIds.size() == 1) {
                graphClauseSB.append(tableName).append(".g = ?");
            } else {
                String placeholders = graphIds.stream()
                        .map(id -> "?")
                        .collect(Collectors.joining(","));
                graphClauseSB.append(tableName).append(".g IN (").append(placeholders).append(")");
            }
            graphClause = graphClauseSB.toString();
        }

    }

    private class GroupFilter {

        Set<Integer> groups;
        String groupPlaceholders;

        public GroupFilter(InvocationContext ctx) {
            groups = ctx.getGroupIds();
            groupPlaceholders = groups.stream()
                    .map(g -> "?")
                    .collect(Collectors.joining(","));
        }

        public void appendClause(StringBuilder sql) {
            sql.append("""
                       WITH acl AS (
                           SELECT resource_id, MAX(permission) AS perm
                           FROM resource_acl
                           WHERE group_id IN ( %s )
                           GROUP BY resource_id
                       )
                       """.formatted(groupPlaceholders));
        }

        public void addParams(List<Object> params) {
            groups.forEach(params::add);
        }

    }

    private class Joiner {

        boolean isSPL;

        public Joiner(boolean isSPL) {
            this.isSPL = isSPL;
        }

        public void addPermColumns(StringBuilder sql) {
            sql.append("""
             -- ================= SUBJECT PERMISSION =================
            , CASE
                -- normal resource
                WHEN rspo_s.id IS NULL AND rspl_s.id IS NULL THEN s_acl.perm

                -- SPO triple → MIN(s, o)
                WHEN rspo_s.id IS NOT NULL THEN 
                    MIN(s_inner_s.perm, s_inner_o.perm)

                -- SPL triple → only s
                WHEN rspl_s.id IS NOT NULL THEN 
                    s_inner_spl.perm
            END AS s_perm
            """);

            if (!isSPL) {
                sql.append("""
                -- ================= OBJECT PERMISSION =================
                , CASE
                    WHEN rspo_o.id IS NULL AND rspl_o.id IS NULL THEN o_acl.perm

                    WHEN rspo_o.id IS NOT NULL THEN 
                        MIN(o_inner_s.perm, o_inner_o.perm)

                    WHEN rspl_o.id IS NOT NULL THEN 
                        o_inner_spl.perm
                END AS o_perm
                """);
            }
        }

        public void addJoinClause(StringBuilder sql) {
            String tableName = isSPL ? "splg" : "spog";

            sql.append("JOIN resource rs ON ").append(tableName).append(".s = rs.id\n");
            sql.append("JOIN property pp ON ").append(tableName).append(".p = pp.id\n");

            if (!isSPL) {
                sql.append("JOIN resource ro ON ").append(tableName).append(".o = ro.id\n");
            }
            sql.append("""
                    -- ================= SUBJECT =================
                    LEFT JOIN resource_uri ruri_s ON ruri_s.id = rs.id
                    LEFT JOIN resource_spo rspo_s ON rspo_s.id = rs.id
                    LEFT JOIN resource_spl rspl_s ON rspl_s.id = rs.id

                    -- expand SPO (subject)
                    LEFT JOIN resource_uri ruri_ss ON ruri_ss.id = rspo_s.s
                    LEFT JOIN resource_uri ruri_so ON ruri_so.id = rspo_s.o
                    LEFT JOIN property pps        ON pps.id = rspo_s.p

                    -- expand SPL (subject)
                    LEFT JOIN resource_uri ruri_spls ON ruri_spls.id = rspl_s.s
                    LEFT JOIN property ppls          ON ppls.id = rspl_s.p
            """);
            if (!isSPL) {
                sql.append("""
                    -- ================= OBJECT =================
                    LEFT JOIN resource_uri ruri_o ON ruri_o.id = ro.id
                    LEFT JOIN resource_spo rspo_o ON rspo_o.id = ro.id
                    LEFT JOIN resource_spl rspl_o ON rspl_o.id = ro.id

                    -- expand SPO (object)
                    LEFT JOIN resource_uri ruri_os ON ruri_os.id = rspo_o.s
                    LEFT JOIN resource_uri ruri_oo ON ruri_oo.id = rspo_o.o
                    LEFT JOIN property ppo         ON ppo.id = rspo_o.p

                    -- expand SPL (object)
                    LEFT JOIN resource_uri ruri_opls ON ruri_opls.id = rspl_o.s
                    LEFT JOIN property pplo          ON pplo.id = rspl_o.p
                       
            """);
            }
        }

        public void addACLClause(StringBuilder sql) {
            sql.append("""
            -- ================= BASE PERMISSIONS =================
            LEFT JOIN acl s_acl ON s_acl.resource_id = rs.id
            """);
            if (!isSPL) {
                sql.append("""
                LEFT JOIN acl o_acl ON o_acl.resource_id = ro.id
                """);
            }

            sql.append("""
            -- ================= SUBJECT INNER =================
            LEFT JOIN acl s_inner_s ON s_inner_s.resource_id = rspo_s.s
            LEFT JOIN acl s_inner_o ON s_inner_o.resource_id = rspo_s.o
            LEFT JOIN acl s_inner_spl ON s_inner_spl.resource_id = rspl_s.s
            """);

            if (!isSPL) {
                sql.append("""
                -- ================= OBJECT INNER =================
                LEFT JOIN acl o_inner_s ON o_inner_s.resource_id = rspo_o.s
                LEFT JOIN acl o_inner_o ON o_inner_o.resource_id = rspo_o.o
                LEFT JOIN acl o_inner_spl ON o_inner_spl.resource_id = rspl_o.s
                """);
            }
        }
    }

    private class ResourceResolver {

        Long subjId = null;
        Long subjTripleS = null;
        Long subjTripleP = null;
        Long subjTripleO = null;
        String subjTripleLex = null;
        String subjTripleLang = null;
        String subjTripleDt = null;
        boolean subjIsTriple = false;

        Long predId = null;

        Long objId = null;
        Long objTripleS = null;
        Long objTripleP = null;
        Long objTripleO = null;
        String objTripleLex = null;
        String objTripleLang = null;
        String objTripleDt = null;
        boolean objIsTriple = false;

        String litLex = null;
        String litLang = null;
        String litDt = null;

        Map<Node, String> bnode2uri;
        boolean isSPL;
        Map<String, Long> resourceUriIdCache;
        Map<String, Long> propertyUriIdCache;

        public ResourceResolver(boolean isSPL, Map<Node, String> bnode2uri, Map<String, Long> resourceUriIdCache, Map<String, Long> propertyUriIdCache) {
            this.isSPL = isSPL;
            this.bnode2uri = bnode2uri;
            this.resourceUriIdCache = resourceUriIdCache;
            this.propertyUriIdCache = propertyUriIdCache;
        }

        private boolean resolve(Node s, Node p, Node o) {
            subjIsTriple = s.isTripleTerm();
            objIsTriple = o.isTripleTerm();

            java.util.function.Function<Node, Long> resolveResource = node -> {
                String uri = node.isBlank()
                        ? bnode2uri.getOrDefault(node, node.getBlankNodeLabel())
                        : node.getURI();

                return resourceUriIdCache.computeIfAbsent(uri, u -> {
                    try {
                        return datasetGraph.getDatabase().read(
                                "SELECT id FROM resource_uri WHERE uri = ?",
                                rs -> rs.next() ? rs.getLong(1) : null,
                                u);
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
            };
            java.util.function.Function<String, Long> resolveProperty = uri
                    -> propertyUriIdCache.computeIfAbsent(uri, u -> {
                        try {
                            return datasetGraph.getDatabase().read(
                                    "SELECT id FROM property WHERE uri = ?",
                                    rs -> rs.next() ? rs.getLong(1) : null,
                                    u);
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    });

            if (!Node.ANY.equals(s)) {
                if (s.isTripleTerm()) {
                    Triple t = s.getTriple();

                    if (!Node.ANY.equals(t.getSubject())) {
                        subjTripleS = resolveResource.apply(t.getSubject());

                        if (subjTripleS == null) {
                            return false;
                        }
                    }

                    if (!Node.ANY.equals(t.getPredicate())) {
                        subjTripleP = resolveProperty.apply(t.getPredicate().getURI());

                        if (subjTripleP == null) {
                            return false;
                        }
                    }

                    if (!Node.ANY.equals(t.getObject())) {
                        if (t.getObject().isLiteral()) {

                            LiteralLabel ll = t.getObject().getLiteral();
                            subjTripleLex = ll.getLexicalForm();
                            subjTripleLang = ll.language();
                            subjTripleDt = ll.getDatatypeURI();

                        } else {
                            subjTripleO = resolveResource.apply(t.getObject());

                            if (subjTripleO == null) {
                                return false;
                            }
                        }
                    }
                } else {
                    subjId = resolveResource.apply(s);

                    if (subjId == null) {
                        return false;
                    }
                }
            }
            if (!Node.ANY.equals(p)) {

                predId = resolveProperty.apply(p.getURI());

                if (predId == null) {
                    return false;
                }
            }
            if (!isSPL) {
                if (!Node.ANY.equals(o)) {
                    if (o.isTripleTerm()) {
                        Triple t = o.getTriple();

                        if (!Node.ANY.equals(t.getSubject())) {
                            objTripleS = resolveResource.apply(t.getSubject());

                            if (objTripleS == null) {
                                return false;
                            }
                        }

                        if (!Node.ANY.equals(t.getPredicate())) {
                            objTripleP = resolveProperty.apply(t.getPredicate().getURI());

                            if (objTripleP == null) {
                                return false;
                            }
                        }

                        if (!Node.ANY.equals(t.getObject())) {
                            if (t.getObject().isLiteral()) {

                                LiteralLabel ll = t.getObject().getLiteral();
                                objTripleLex = ll.getLexicalForm();
                                objTripleLang = ll.language();
                                objTripleDt = ll.getDatatypeURI();

                            } else {
                                objTripleO = resolveResource.apply(t.getObject());

                                if (objTripleO == null) {
                                    return false;
                                }
                            }
                        }
                    } else {
                        objId = resolveResource.apply(o);

                        if (objId == null) {
                            return false;
                        }
                    }
                }
            } else {
                // isSPL
                if (!Node.ANY.equals(o) && o.isLiteral()) {
                    LiteralLabel ll = o.getLiteral();
                    litLex = ll.getLexicalForm();
                    litLang = ll.language();
                    litDt = ll.getDatatypeURI();
                }
            }

            return true;
        }

        private void appendClauses(StringBuilder sql) {
            String tableName = isSPL ? "splg" : "spog";

            if (subjIsTriple) {
                sql.append("AND (rspo_s.id IS NOT NULL OR rspl_s.id IS NOT NULL) ");

                if (subjTripleS != null) {
                    sql.append("AND (rspo_s.s = ? OR rspl_s.s = ?) ");
                }

                if (subjTripleP != null) {
                    sql.append("AND (rspo_s.p = ? OR rspl_s.p = ?) ");
                }

                if (subjTripleO != null) {
                    sql.append("AND rspo_s.o = ? ");
                }

                if (subjTripleLex != null) {
                    sql.append("AND rspl_s.lex = ? ");

                    if (!subjTripleLang.isBlank()) {
                        sql.append("AND rspl_s.lang = ? ");
                    }

                    if (subjTripleDt != null) {
                        sql.append("AND rspl_s.dt = ? ");
                    }
                }

            } else if (subjId != null) {
                sql.append("AND ").append(tableName).append(".s = ? ");
            }

            if (predId != null) {
                sql.append("AND ").append(tableName).append(".p = ? ");
            }

            if (isSPL) {
                if (litLex != null) {
                    sql.append("AND ").append(tableName).append(".lex = ? ");

                    if (!litLang.isBlank()) {
                        sql.append("AND ").append(tableName).append(".lang = ? ");
                    }

                    if (litDt != null) {
                        sql.append("AND ").append(tableName).append(".dt = ? ");
                    }
                }
            } else {

                if (objIsTriple) {
                    sql.append("AND (rspo_o.id IS NOT NULL OR rspl_o.id IS NOT NULL) ");

                    if (objTripleS != null) {
                        sql.append("AND (rspo_o.s = ? OR rspl_o.s = ?) ");
                    }

                    if (objTripleP != null) {
                        sql.append("AND (rspo_o.p = ? OR rspl_o.p = ?) ");
                    }

                    if (objTripleO != null) {
                        sql.append("AND rspo_o.o = ? ");
                    }

                    if (objTripleLex != null) {
                        sql.append("AND rspl_o.lex = ? ");

                        if (!objTripleLang.isBlank()) {
                            sql.append("AND rspl_o.lang = ? ");
                        }

                        if (objTripleDt != null) {
                            sql.append("AND rspl_o.dt = ? ");
                        }
                    }

                } else if (objId != null) {
                    sql.append("AND ").append(tableName).append(".o = ? ");
                }
            }
        }

        private void addParams(List<Object> params) {
            if (subjIsTriple) {
                if (subjTripleS != null) {
                    params.add(subjTripleS);
                    params.add(subjTripleS);
                }
                if (subjTripleP != null) {
                    params.add(subjTripleP);
                    params.add(subjTripleP);
                }
                if (subjTripleO != null) {
                    params.add(subjTripleO);
                }
                if (subjTripleLex != null) {
                    params.add(subjTripleLex);

                    if (!subjTripleLang.isBlank()) {
                        params.add(subjTripleLang);
                    }

                    if (subjTripleDt != null) {
                        params.add(subjTripleDt);
                    }
                }
            } else if (subjId != null) {
                params.add(subjId);
            }

            if (predId != null) {
                params.add(predId);
            }

            if (isSPL) {
                if (litLex != null) {
                    params.add(litLex);

                    if (!litLang.isBlank()) {
                        params.add(litLang);
                    }

                    if (litDt != null) {
                        params.add(litDt);
                    }
                }
            } else {

                if (objIsTriple) {
                    if (objTripleS != null) {
                        params.add(objTripleS);
                        params.add(objTripleS);
                    }
                    if (objTripleP != null) {
                        params.add(objTripleP);
                        params.add(objTripleP);
                    }
                    if (objTripleO != null) {
                        params.add(objTripleO);
                    }
                    if (objTripleLex != null) {
                        params.add(objTripleLex);

                        if (!objTripleLang.isBlank()) {
                            params.add(objTripleLang);
                        }

                        if (objTripleDt != null) {
                            params.add(objTripleDt);
                        }
                    }
                } else if (objId != null) {
                    params.add(objId);
                }
            }
        }

    }

    private class QueryWithParams {

        String sql;
        List<Object> params;

        public QueryWithParams(String sql, List<Object> params) {
            this.sql = sql;
            this.params = params;
        }

    }

    private record QueryKey(
            String method,
            boolean subjId,
            boolean subjTripleS,
            boolean subjTripleP,
            boolean subjTripleO,
            boolean subjTripleLex,
            boolean subjTripleLang,
            boolean subjTripleDt,
            boolean subjIsTriple,
            boolean predId,
            boolean objId,
            boolean objTripleS,
            boolean objTripleP,
            boolean objTripleO,
            boolean objTripleLex,
            boolean objTripleLang,
            boolean objTripleDt,
            boolean objIsTriple,
            boolean litLex,
            boolean litLang,
            boolean litDt,
            boolean isSPL,
            boolean hasLimit,
            boolean hasOffset,
            boolean orderBy,
            boolean edit,
            boolean enableAC,
            int groups,
            int graphs
            ) {

    }

    //used in find, contains and size
    private QueryWithParams buildQuery(String method, boolean isSPL, String prefixQuery, String postfixQuery, Node s, Node p, Node o,
            Integer limit,
            Integer offset,
            boolean orderBy,
            boolean edit,
            InvocationContext ctx) {

        Map<Node, String> bnode2uri = datasetGraph.getBnode2uri();
        ResourceResolver resolver = new ResourceResolver(isSPL, bnode2uri, resourceUriIdCache, propertyUriIdCache);
        //needs to be resolved because of params
        boolean successful = resolver.resolve(s, p, o);
        if (!successful) {
            return null;
        }

        boolean enableAC = !datasetGraph.isAdmin(ctx);
        GraphFilter graphFilter = new GraphFilter(isSPL);
        GroupFilter groupFilter = new GroupFilter(ctx);
        Joiner joiner = new Joiner(isSPL);

        QueryKey queryKey = new QueryKey(
                method,
                resolver.subjId == null,
                resolver.subjTripleS == null,
                resolver.subjTripleP == null,
                resolver.subjTripleO == null,
                resolver.subjTripleLex == null,
                resolver.subjTripleLang != null && resolver.subjTripleLang.isBlank(),
                resolver.subjTripleDt == null,
                resolver.subjIsTriple,
                resolver.predId == null,
                resolver.objId == null,
                resolver.objTripleS == null,
                resolver.objTripleP == null,
                resolver.objTripleO == null,
                resolver.objTripleLex == null,
                resolver.objTripleLang != null && resolver.objTripleLang.isBlank(),
                resolver.objTripleDt == null,
                resolver.objIsTriple,
                resolver.litLex == null,
                resolver.litLang != null && resolver.litLang.isBlank(),
                resolver.litDt == null,
                isSPL,
                limit != null,
                offset != null,
                orderBy,
                edit,
                enableAC,
                ctx.getGroupIds().size(),
                idAndUris.size()
        );
        if (PRINT_FIND) {
            System.out.println(queryKey);
        }

        String sqlString = queryCache.get(queryKey);

        if (sqlString == null) {
            // build SQL
            StringBuilder sql = new StringBuilder();

            if (enableAC) {
                groupFilter.appendClause(sql);

                //special case: it also starts with "WITH" so it it just a comma we need to add
                if (prefixQuery.trim().toLowerCase().startsWith("with")) {
                    sql.append(", ").append(prefixQuery.substring("WITH".length(), prefixQuery.length()));
                } else {
                    sql.append(prefixQuery);
                }

            } else {
                sql.append(prefixQuery);
            }

            if (enableAC) {
                joiner.addPermColumns(sql);
            }

            String tableName = isSPL ? "splg" : "spog";

            sql.append("FROM ").append(tableName).append("\n");

            joiner.addJoinClause(sql);

            if (enableAC) {
                joiner.addACLClause(sql);
            }
            sql.append("WHERE  ").append(graphFilter.graphClause).append(" ");

            resolver.appendClauses(sql);

            if (enableAC) {

                if (isSPL) {
                    sql.append("AND s_perm >= ").append((edit ? Permission.EDIT : Permission.READ).getCode()).append(" ");

                } else {

                    if (edit) {
                        //handles refer logic
                        sql.append("AND s_perm >= ").append(Permission.REFER.getCode()).append(" ");
                        sql.append("AND o_perm >= ").append(Permission.REFER.getCode()).append(" ");
                        sql.append("AND (s_perm >= ").append(Permission.EDIT.getCode())
                                .append(" OR o_perm >= ").append(Permission.EDIT.getCode()).append(") ");

                    } else {
                        sql.append("AND s_perm >= ").append(Permission.READ.getCode()).append(" ");
                        sql.append("AND o_perm >= ").append(Permission.READ.getCode()).append(" ");
                    }
                }
            }

            // deterministic ordering for pagination
            if (orderBy) {
                sql.append("ORDER BY ").append(tableName).append(".id ");
            }

            if (limit != null) {
                sql.append("LIMIT ? ");
            }

            if (offset != null) {
                sql.append("OFFSET ? ");
            }

            sql.append(postfixQuery);

            sqlString = sql.toString();

            queryCache.put(queryKey, sqlString);
        }

        // assemble parameters
        List<Object> params = new ArrayList<>();

        if (enableAC) {
            groupFilter.addParams(params);
        }

        params.addAll(graphFilter.graphIds);

        resolver.addParams(params);

        if (limit != null) {
            params.add(limit + 1);
        }

        if (offset != null) {
            params.add(offset);
        }

        return new QueryWithParams(sqlString, params);
    }

    //just delegates
    @Override
    public ExtendedIterator<Triple> find(Node s, Node p, Node o, InvocationContext ctx) {
        return find(s, p, o, null, null, null, null, ctx);
    }

    //uses findSPO and findSPL
    public ExtendedIterator<Triple> find(Node s, Node p, Node o, Integer spoLimit, Integer spoOffset, Integer splLimit, Integer splOffset, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, datasetGraph.getContext());

        s = ifNullToNodeANY(s);
        p = ifNullToNodeANY(p);
        o = ifNullToNodeANY(o);

        //better just return empty iterator 
        try {
            valid(s, p, o);
        } catch (IllegalArgumentException e) {
            //would be cool to return the reason by it is empty
            return datasetGraph.getDatabase().emptyIterator();
        }

        //add extra check that graphs in idAndUris are at least read permission
        try {
            checkGraphPermission(Permission.READ, datasetGraph.getDatabase(), ctx);
        } catch (SQLException ex) {
            throw new RuntimeException("DB error", ex);
        }

        //TODO this could better use the PagedTripleIterator and return the hasMore for spo and spl
        return findSPO(s, p, o, spoLimit, spoOffset, false, null, ctx)
                .andThen(findSPL(s, p, o, splLimit, splOffset, false, null, ctx));
    }

    public ExtendedIterator<Triple> findSPO(Node s, Node p, Node o,
            Integer limit,
            Integer offset,
            boolean edit,
            Consumer<IdAndUriTriple> dbTripleConsumer,
            InvocationContext ctx) {

        if (o.isLiteral()) {
            return datasetGraph.getDatabase().emptyIterator();
        }

        if (PRINT_FIND) {
            System.out.println();
            System.out.println();
            System.out.println("findSPO(" + s + ", " + p + ", " + o + ", " + idAndUris + ")");
        }

        //a union graph if more than one id
        boolean isUnionGraph = idAndUris.size() > 1;

        //use distinct in case of union graph because there can be same triple in different graphs
        String prefixQuery = "SELECT " + (isUnionGraph ? "DISTINCT " : "") + """
         
            -- ================= SUBJECT =================
            rs.id   AS s_id,                    -- 1
            
            ruri_s.uri AS s_uri,               -- 2
            ruri_s.is_blank AS s_is_blank,     -- 3
            
            rspo_s.s  AS rspo_s_s,             -- 4
            rspo_s.p  AS rspo_s_p,             -- 5
            rspo_s.o  AS rspo_s_o,             -- 6
            
            rspl_s.s  AS rspl_s_s,             -- 7
            rspl_s.p  AS rspl_s_p,             -- 8
            rspl_s.lex  AS rspl_s_lex,         -- 9
            rspl_s.lang AS rspl_s_lang,        -- 10
            rspl_s.dt   AS rspl_s_dt,          -- 11
            
            -- nested SPO subject expansion
            ruri_ss.uri AS rspo_s_s_uri,       -- 12
            ruri_so.uri AS rspo_s_o_uri,       -- 13
            pps.uri     AS rspo_s_p_uri,       -- 14
            
            -- nested SPL subject expansion
            ruri_spls.uri AS rspl_s_s_uri,     -- 15
            ppls.uri      AS rspl_s_p_uri,     -- 16
            
            -- ================= PREDICATE =================
            pp.id  AS p_id,                    -- 17
            pp.uri AS p_uri,                   -- 18
            
            -- ================= OBJECT =================
            ro.id   AS o_id,                   -- 19
            
            ruri_o.uri AS o_uri,               -- 20
            ruri_o.is_blank AS o_is_blank,     -- 21
            
            rspo_o.s  AS rspo_o_s,             -- 22
            rspo_o.p  AS rspo_o_p,             -- 23
            rspo_o.o  AS rspo_o_o,             -- 24
            
            rspl_o.s  AS rspl_o_s,             -- 25
            rspl_o.p  AS rspl_o_p,             -- 26
            rspl_o.lex  AS rspl_o_lex,         -- 27
            rspl_o.lang AS rspl_o_lang,        -- 28
            rspl_o.dt   AS rspl_o_dt,          -- 29
            
            -- nested SPO object expansion
            ruri_os.uri AS rspo_o_s_uri,       -- 30
            ruri_oo.uri AS rspo_o_o_uri,       -- 31
            ppo.uri     AS rspo_o_p_uri,       -- 32
            
            -- nested SPL object expansion
            ruri_opls.uri AS rspl_o_s_uri,     -- 33
            pplo.uri      AS rspl_o_p_uri,     -- 34
            
            spog.confidence                    -- 35
        """;

        QueryWithParams queryWithParams = buildQuery(
                "find",
                false,
                prefixQuery, "",
                s, p, o,
                limit, offset, limit != null || offset != null,
                edit,
                ctx);

        if (queryWithParams == null) {
            return datasetGraph.getDatabase().emptyIterator();
        }

        if (PRINT_FIND) {
            System.out.println(queryWithParams.sql.toString());
            System.out.println(queryWithParams.params);
        }
        TransactionalResultSet txnResultSet;
        try {
            txnResultSet = datasetGraph.getDatabase().read(queryWithParams.sql, queryWithParams.params.toArray());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read SPO triples", e);
        }

        ResultSetTripleMapper spoMapper = rs -> {

            // ================= SUBJECT =================
            long sId = rs.getLong(1);
            Node subj;

            String sUri = rs.getString(2);

            // ---- URI / blank ----
            if (sUri != null) {
                boolean sBlank = rs.getBoolean(3);
                subj = sBlank
                        ? NodeFactory.createBlankNode(sUri)
                        : NodeFactory.createURI(sUri);
            } // ---- SPO triple ----
            else if (rs.getObject(4) != null) {

                Node rspo_s = NodeFactory.createURI(rs.getString(12));
                Node rspo_p = NodeFactory.createURI(rs.getString(14));
                Node rspo_o = NodeFactory.createURI(rs.getString(13));

                subj = NodeFactory.createTripleTerm(rspo_s, rspo_p, rspo_o);
            } // ---- SPL triple ----
            else if (rs.getObject(7) != null) {

                Node rspl_s = NodeFactory.createURI(rs.getString(15));
                Node rspl_p = NodeFactory.createURI(rs.getString(16));

                String lex = rs.getString(9);
                String lang = rs.getString(10);
                String dt = rs.getString(11);

                Node rspl_o;
                if (lang != null && !lang.isEmpty()) {
                    rspl_o = NodeFactory.createLiteralLang(lex, lang);
                } else if (dt != null) {
                    rspl_o = NodeFactory.createLiteralDT(lex, NodeFactory.getType(dt));
                } else {
                    rspl_o = NodeFactory.createLiteralString(lex);
                }

                subj = NodeFactory.createTripleTerm(rspl_s, rspl_p, rspl_o);
            } else {
                throw new IllegalStateException("Unknown subject type id=" + sId);
            }

            // ================= PREDICATE =================
            long pId = rs.getLong(17);
            Node pred = NodeFactory.createURI(rs.getString(18));

            // ================= OBJECT =================
            long oId = rs.getLong(19);
            Node obj;

            String oUri = rs.getString(20);

            // ---- URI / blank ----
            if (oUri != null) {
                boolean oBlank = rs.getBoolean(21);
                obj = oBlank
                        ? NodeFactory.createBlankNode(oUri)
                        : NodeFactory.createURI(oUri);
            } // ---- SPO triple ----
            else if (rs.getObject(22) != null) {

                Node rspo_s = NodeFactory.createURI(rs.getString(30));
                Node rspo_p = NodeFactory.createURI(rs.getString(32));
                Node rspo_o = NodeFactory.createURI(rs.getString(31));

                obj = NodeFactory.createTripleTerm(rspo_s, rspo_p, rspo_o);
            } // ---- SPL triple ----
            else if (rs.getObject(25) != null) {

                Node rspl_s = NodeFactory.createURI(rs.getString(33));
                Node rspl_p = NodeFactory.createURI(rs.getString(34));

                String lex = rs.getString(27);
                String lang = rs.getString(28);
                String dt = rs.getString(29);

                Node rspl_o;
                if (lang != null && !lang.isEmpty()) {
                    rspl_o = NodeFactory.createLiteralLang(lex, lang);
                } else if (dt != null) {
                    rspl_o = NodeFactory.createLiteralDT(lex, NodeFactory.getType(dt));
                } else {
                    rspl_o = NodeFactory.createLiteralString(lex);
                }

                obj = NodeFactory.createTripleTerm(rspl_s, rspl_p, rspl_o);
            } else {
                throw new IllegalStateException("Unknown object type id=" + oId);
            }

            // ================= DEBUG / CONSUMER =================
            //TODO IdAndUri is designed to save URI but if subj or obj is triple term this is not possible
            if (dbTripleConsumer != null && !subj.isTripleTerm() && !obj.isTripleTerm()) {
                dbTripleConsumer.accept(
                        IdAndUriTriple.create(
                                IdAndUri.create(sId, subj),
                                IdAndUri.create(pId, pred),
                                IdAndUriOrLiteral.create(oId, obj),
                                rs.getDouble(35)
                        )
                );
            }

            AticTriple t = AticTriple.create(subj, pred, obj, rs.getDouble(35));

            if (PRINT_FIND) {
                System.out.println("findSPO(" + s + ", " + p + ", " + o + ", " + idAndUris + "): " + t);
            }

            return t;
        };

        return new PagedTripleIterator(txnResultSet, limit, datasetGraph, spoMapper);
    }

    public ExtendedIterator<Triple> findSPL(Node s, Node p, Node o,
            Integer limit,
            Integer offset,
            boolean edit,
            Consumer<IdAndUriTriple> dbTripleConsumer,
            InvocationContext ctx) {

        if (o.isURI()) {
            return datasetGraph.getDatabase().emptyIterator();
        }

        if (PRINT_FIND) {
            System.out.println();
            System.out.println();
            System.out.println("findSPL(" + s + ", " + p + ", " + o + "," + idAndUris + ")");
        }

        if (s.isTripleTerm() && p.equals(ATIC_CONFIDENCE) && o.equals(Node.ANY)) {

            Triple quotedTriple = s.getTriple();

            ExtendedIterator<Triple> iter = find(
                    quotedTriple.getSubject(),
                    quotedTriple.getPredicate(),
                    quotedTriple.getObject(),
                    null, null,
                    null, null,
                    ctx
            );

            if (iter.hasNext()) {
                Triple t = iter.next();
                if (t instanceof AticTriple) {
                    AticTriple aticTriple = (AticTriple) t;

                    double confidence = aticTriple.getConfidence();
                    AticTriple outputTriple = AticTriple.createWithConfidence(Triple.create(s, p, NodeFactory.createLiteralByValue(confidence)), 1);

                    //just one to return
                    return WrappedIterator.create(Arrays.asList((Triple) outputTriple).iterator());
                }
            }

            return datasetGraph.getDatabase().emptyIterator();
        }

        //a union graph if more than one id
        boolean isUnionGraph = idAndUris.size() > 1;

        //use distinct in case of union graph because there can be same triple in different graphs
        String prefixQuery = "SELECT " + (isUnionGraph ? "DISTINCT " : "") + """ 
            -- ================= SUBJECT =================
            rs.id   AS s_id,                    -- 1
            
            ruri_s.uri AS s_uri,               -- 2
            ruri_s.is_blank AS s_is_blank,     -- 3
            
            rspo_s.s  AS rspo_s_s,             -- 4
            rspo_s.p  AS rspo_s_p,             -- 5
            rspo_s.o  AS rspo_s_o,             -- 6
            
            rspl_s.s  AS rspl_s_s,             -- 7
            rspl_s.p  AS rspl_s_p,             -- 8
            rspl_s.lex  AS rspl_s_lex,         -- 9
            rspl_s.lang AS rspl_s_lang,        -- 10
            rspl_s.dt   AS rspl_s_dt,          -- 11
            
            -- -------- SPO expansion (subject) --------
            ruri_ss.uri AS rspo_s_s_uri,       -- 12
            ruri_so.uri AS rspo_s_o_uri,       -- 13
            pps.uri     AS rspo_s_p_uri,       -- 14
            
            -- -------- SPL expansion (subject) --------
            ruri_spls.uri AS rspl_s_s_uri,     -- 15
            ppls.uri      AS rspl_s_p_uri,     -- 16
            
            -- ================= PREDICATE =================
            pp.id  AS p_id,                    -- 17
            pp.uri AS p_uri,                   -- 18
            
            -- ================= OBJECT (literal) =================
            splg.lex,                          -- 19
            splg.lang,                         -- 20
            splg.dt,                           -- 21
            
            splg.confidence                    -- 22
            """;

        QueryWithParams queryWithParams = buildQuery(
                "find",
                true,
                prefixQuery, "",
                s, p, o,
                limit, offset, limit != null || offset != null,
                edit,
                ctx);

        if (queryWithParams == null) {
            return datasetGraph.getDatabase().emptyIterator();
        }

        if (PRINT_FIND) {
            System.out.println(queryWithParams.sql);
            System.out.println(queryWithParams.params);
        }
        TransactionalResultSet txnResultSet;
        try {
            txnResultSet = datasetGraph.getDatabase().read(queryWithParams.sql, queryWithParams.params.toArray());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read triples", e);
        }

        ResultSetTripleMapper splMapper = rs -> {

            long sId = rs.getLong(1);

            Node subj;

            // -------- CASE 1: URI / BLANK --------
            String sUri = rs.getString(2);

            if (sUri != null) {
                boolean sBlank = rs.getBoolean(3);

                subj = sBlank
                        ? NodeFactory.createBlankNode(sUri)
                        : NodeFactory.createURI(sUri);
            } // -------- CASE 2: SPO triple --------
            else if (rs.getObject(4) != null) {

                Node rspo_s = NodeFactory.createURI(rs.getString(12));
                Node rspo_p = NodeFactory.createURI(rs.getString(14));
                Node rspo_o = NodeFactory.createURI(rs.getString(13));

                subj = NodeFactory.createTripleTerm(rspo_s, rspo_p, rspo_o);
            } // -------- CASE 3: SPL triple --------
            else if (rs.getObject(7) != null) {

                Node rspl_s = NodeFactory.createURI(rs.getString(15));
                Node rspl_p = NodeFactory.createURI(rs.getString(16));

                String lex = rs.getString(9);
                String lang = rs.getString(10);
                String dt = rs.getString(11);

                Node rspl_o;
                if (lang != null && !lang.isEmpty()) {
                    rspl_o = NodeFactory.createLiteralLang(lex, lang);
                } else if (dt != null) {
                    rspl_o = NodeFactory.createLiteralDT(lex, NodeFactory.getType(dt));
                } else {
                    rspl_o = NodeFactory.createLiteralString(lex);
                }

                subj = NodeFactory.createTripleTerm(rspl_s, rspl_p, rspl_o);
            } else {
                throw new IllegalStateException("Unknown subject type for id=" + sId);
            }

            // -------- Predicate --------
            long pId = rs.getLong(17);
            Node pred = NodeFactory.createURI(rs.getString(18));

            // -------- Object (literal from splg) --------
            String lex = rs.getString(19);
            String lang = rs.getString(20);
            String dt = rs.getString(21);

            Node obj;

            if (!lang.isEmpty()) {
                obj = NodeFactory.createLiteralLang(lex, lang);
            } else if (dt != null) {
                obj = NodeFactory.createLiteralDT(lex, NodeFactory.getType(dt));
            } else {
                obj = NodeFactory.createLiteralString(lex);
            }

            //TODO IdAndUri is designed to save URI but if subj is triple term this is not possible
            if (dbTripleConsumer != null && !subj.isTripleTerm()) {
                dbTripleConsumer.accept(
                        IdAndUriTriple.create(
                                IdAndUri.create(sId, subj),
                                IdAndUri.create(pId, pred),
                                IdAndUriOrLiteral.create(obj),
                                rs.getDouble(22)
                        )
                );
            }

            AticTriple t = AticTriple.create(subj, pred, obj, rs.getDouble(22));

            if (PRINT_FIND) {
                System.out.println("findSPL(" + s + ", " + p + ", " + o + "," + idAndUris + "): " + t);
            }

            return t;
        };

        return new PagedTripleIterator(txnResultSet, limit, datasetGraph, splMapper);
    }

    @Override
    public boolean contains(Node s, Node p, Node o, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, datasetGraph.getContext());

        s = ifNullToNodeANY(s);
        p = ifNullToNodeANY(p);
        o = ifNullToNodeANY(o);
        valid(s, p, o);

        //add extra check that graphs in idAndUris are at least read permission
        try {
            checkGraphPermission(Permission.READ, datasetGraph.getDatabase(), ctx);
        } catch (SQLException ex) {
            throw new RuntimeException("DB error", ex);
        }

        return containsSPO(s, p, o, ctx) || containsSPL(s, p, o, ctx);
    }

    private boolean containsSPO(Node s, Node p, Node o, InvocationContext ctx) {
        // literals are never stored in the SPO table
        if (o.isLiteral()) {
            return false;
        }

        QueryWithParams queryWithParams = buildQuery(
                "contains",
                false,
                "SELECT EXISTS(SELECT 1\n", "\n)",
                s, p, o,
                null, null, false,
                false,
                ctx);

        if (queryWithParams == null) {
            return false;
        }

        // ----- execute -------------------------------------------------------
        Integer exists;
        try {
            exists = datasetGraph.getDatabase().read(
                    queryWithParams.sql,
                    rs -> rs.next() ? rs.getInt(1) : 0,
                    queryWithParams.params.toArray());
        } catch (SQLException ex) {
            throw new RuntimeException("Failed to execute containment query for SPO", ex);
        }

        return exists != null && exists == 1;
    }

    private boolean containsSPL(Node s, Node p, Node o, InvocationContext ctx) {
        // URI objects are never stored in the SPL table
        if (o.isURI()) {
            return false;
        }

        QueryWithParams queryWithParams = buildQuery(
                "contains",
                true,
                "SELECT EXISTS(SELECT 1\n", "\n)",
                s, p, o,
                null, null, false,
                false,
                ctx);

        if (queryWithParams == null) {
            return false;
        }

        // ----- execute -------------------------------------------------------
        Integer exists;
        try {
            exists = datasetGraph.getDatabase().read(
                    queryWithParams.sql,
                    rs -> rs.next() ? rs.getInt(1) : 0,
                    queryWithParams.params.toArray());
        } catch (SQLException ex) {
            throw new RuntimeException("Failed to execute containment query for SPO", ex);
        }

        return exists != null && exists == 1;
    }

    //
    @Override
    public int size(InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, datasetGraph.getContext());

        //add extra check that graphs in idAndUris are at least read permission
        try {
            checkGraphPermission(Permission.READ, datasetGraph.getDatabase(), ctx);
        } catch (SQLException ex) {
            throw new RuntimeException("DB error", ex);
        }

        long size = sizeSPO(Node.ANY, Node.ANY, Node.ANY, ctx)
                + sizeSPL(Node.ANY, Node.ANY, Node.ANY, ctx);

        return (int) size;
    }

    private long sizeSPO(Node s, Node p, Node o, InvocationContext ctx) {
        // literals are never stored in the SPO table
        if (o.isLiteral()) {
            return 0L;
        }

        //no valid(triple) check is used here because it is only called from size
        //a union graph if more than one id
        boolean isUnionGraph = idAndUris.size() > 1;

        //use distinct in case of union graph because there can be same triple in different graphs
        String prefixQuery = "SELECT COUNT(*) AS cnt\n";
        String postfixQuery = "";

        if (isUnionGraph) {
            prefixQuery = "SELECT COUNT(*) AS cnt FROM ( SELECT DISTINCT spog.s, spog.p, spog.o\n";
            postfixQuery = "\n)";
        }

        QueryWithParams queryWithParams = buildQuery(
                "size",
                false,
                prefixQuery, postfixQuery,
                s, p, o,
                null, null, false,
                false,
                ctx);

        if (queryWithParams == null) {
            return 0;
        }

        // ----- execute and fetch count --------------------------------------
        try {
            return datasetGraph.getDatabase().read(
                    queryWithParams.sql,
                    rs -> {
                        rs.next();
                        return rs.getLong("cnt");
                    },
                    queryWithParams.params.toArray());
        } catch (SQLException ex) {
            throw new RuntimeException("Failed to count SPO triples", ex);
        }
    }

    private long sizeSPL(Node s, Node p, Node o, InvocationContext ctx) {
        // URI objects are never stored in the SPL table
        if (o.isURI()) {
            return 0L;
        }

        //no valid(triple) check is used here because it is only called from size
        //a union graph if more than one id
        boolean isUnionGraph = idAndUris.size() > 1;

        //use distinct in case of union graph because there can be same triple in different graphs
        String prefixQuery = "SELECT COUNT(*) AS cnt\n";
        String postfixQuery = "";

        if (isUnionGraph) {
            prefixQuery = "SELECT COUNT(*) AS cnt FROM ( SELECT DISTINCT splg.s, splg.p, splg.lex, splg.lang, splg.dt\n";
            postfixQuery = "\n)";
        }

        QueryWithParams queryWithParams = buildQuery(
                "size",
                true,
                prefixQuery, postfixQuery,
                s, p, o,
                null, null, false,
                false,
                ctx);

        if (queryWithParams == null) {
            return 0;
        }

        // ----- execute and fetch count --------------------------------------
        try {
            return datasetGraph.getDatabase().read(
                    queryWithParams.sql,
                    rs -> {
                        rs.next();
                        return rs.getLong("cnt");
                    },
                    queryWithParams.params.toArray());
        } catch (SQLException ex) {
            throw new RuntimeException("Failed to count SPL triples", ex);
        }
    }

    //--------------------------------------------------------
    //helper
    private long graphMustBeUniquelyIdentified(boolean isAdd) {
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

    private void checkGraphPermission(
            Permission required,
            Database db,
            InvocationContext ctx
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

    private static void validateTripleTerm(Triple inner, String position) {

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

    private static Node ifNullToNodeANY(Node n) {
        if (n == null) {
            return Node.ANY;
        }
        return n;
    }

    //------------------------------------------------------
    //the delegates (e.g. opens Triple and delegates to s,p,o method)
    @Override
    public boolean contains(Triple t, InvocationContext ctx) {
        return contains(t.getSubject(), t.getPredicate(), t.getObject(), ctx);
    }

    @Override
    public ExtendedIterator<Triple> find(Triple t, InvocationContext ctx) {
        return find(t.getSubject(), t.getPredicate(), t.getObject(), ctx);
    }

    @Override
    public boolean isEmpty(InvocationContext ctx) {
        return size(ctx) == 0;
    }

    //============================
    //management and other
    @Override
    public void close(InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean isClosed(InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public PrefixMapping getPrefixMapping(InvocationContext ctx) {
        return new PrefixMappingAdapter(datasetGraph.prefixes(ctx));
    }

    @Override
    public TransactionHandler getTransactionHandler() {
        return transactionHandler;
    }

    @Override
    public GraphEventManager getEventManager() {
        return graphEventManager;
    }

    @Override
    public boolean isIsomorphicWith(Graph g, InvocationContext ctx) {
        //TODO isIsomorphicWith currently does not check permissions
        return GraphMatcher.equals(this, g);
    }

    //UnsupportedOperationException =======================================
    @Override
    public boolean dependsOn(Graph other, InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    //getter & setter =======================================
    public static int getDefaultBufferSize() {
        return defaultBufferSize;
    }

    public static void setDefaultBufferSize(int defaultBufferSize) {
        SqliteAticGraph.defaultBufferSize = defaultBufferSize;
    }

    public static int getDefaultBatchSize() {
        return defaultBatchSize;
    }

    public static void setDefaultBatchSize(int defaultBatchSize) {
        SqliteAticGraph.defaultBatchSize = defaultBatchSize;
    }

    public static void setDefaultBufferAndBatchSize(int defaultSize) {
        setDefaultBufferSize(defaultSize);
        setDefaultBatchSize(defaultSize);
    }

    public List<Node> asNodes() {
        List<Node> nodes = new ArrayList<>();
        for (IdAndUri idAndUri : idAndUris) {
            nodes.add(NodeFactory.createURI(idAndUri.getUri()));
        }
        return nodes;
    }

}
