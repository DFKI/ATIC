package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.Permission;
import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.AticTriple;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.jena.datatypes.BaseDatatype;
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
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.sparql.graph.PrefixMappingAdapter;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.util.iterator.NiceIterator;

/**
 *
 */
public class SystemAticGraph implements AticGraph {

    public static final Node node = NodeFactory.createURI("urn:atic:system");

    private SqliteAticDatasetGraph datasetGraph;

    private final TransactionHandler transactionHandler;
    private final GraphEventManager graphEventManager;

    private Map<Integer, TransactionData> user2trans;

    public SystemAticGraph(SqliteAticDatasetGraph datasetGraph) {
        this.datasetGraph = datasetGraph;

        user2trans = new HashMap<>();

        SystemAticGraph thisGraph = this;
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
    }

    private class TransactionData {

        int bufferSize;
        int batchSize;

        Map<Node, Long> resourceCache;
        Map<Node, Permission> permissionCache;
        Map<Node, Long> predicateCache;

        List<Triple> buffer;

        InvocationContext ctx;

        public TransactionData(InvocationContext ctx) {
            this.ctx = ctx;

            this.bufferSize = SqliteAticGraph.getDefaultBufferSize();
            this.batchSize = SqliteAticGraph.getDefaultBatchSize();

            resourceCache = new HashMap<>(bufferSize * 2);
            permissionCache = new HashMap<>(bufferSize * 2);
            predicateCache = new HashMap<>(bufferSize);

            buffer = new ArrayList<>(bufferSize);
        }

        public void flush() {
            try {
                processBuffer(
                        this.buffer,
                        this.ctx,
                        datasetGraph.getDatabase(),
                        this.resourceCache,
                        this.permissionCache,
                        this.predicateCache,
                        this.batchSize);
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

    private void processBuffer(List<Triple> triples,
            InvocationContext ctx,
            Database db,
            Map<Node, Long> resourceCache,
            Map<Node, Permission> permissionCache,
            Map<Node, Long> predicateCache,
            int batchSize) throws SQLException {

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
        AticGraphUtils.bulkResolveResources(resourceNodes, ctx, db, true, false, resourceCache, predicateCache, permissionCache, datasetGraph);

        // ---------------------------------------
        // 3. Resolve predicates in bulk
        // ---------------------------------------
        AticGraphUtils.bulkResolvePredicates(predicateNodes, ctx, db, predicateCache, datasetGraph);

        // ---------------------------------------
        // 4. Prepare batch inserts
        // ---------------------------------------
        List<Object[]> systemSpluBatch = new ArrayList<>();

        for (Triple t : triples) {
            if (!t.getObject().isLiteral()) {
                continue;
            }

            long s = resourceCache.get(t.getSubject());
            long p = predicateCache.get(t.getPredicate());

            LiteralLabel lit = t.getObject().getLiteral();
            
            //we would like to create system triples which are user agnostic (independent)
            boolean userAgnostic = false;
            if(t instanceof AticTriple aticTriple) {
                userAgnostic = aticTriple.isUserAgnostic();
            }

            systemSpluBatch.add(new Object[]{
                s,
                p,
                lit.getLexicalForm(),
                lit.language(),
                lit.getDatatypeURI(),
                userAgnostic ? null : ctx.getPrimaryGroupId(),
                ctx.getUserId()
            });
        }

        db.writeBatch("""
        INSERT OR IGNORE INTO system_splu
        (s, p, lex, lang, dt, user_primary_group, creator)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, systemSpluBatch, batchSize);
    }

    private void valid(Triple t) {
        Node s = t.getSubject();
        Node p = t.getPredicate();
        Node o = t.getObject();
        valid(s, p, o);
    }

    private void valid(Node s, Node p, Node o) {

        // -------------------------
        // SUBJECT (allow ANY)
        // -------------------------
        if (!s.equals(Node.ANY) && !s.isURI()) {
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
        if (!o.equals(Node.ANY) && !o.isLiteral()) {
            throw new IllegalArgumentException("Invalid object: " + o);
        }
    }

    //----------------------------------
    //read
    @Override
    public ExtendedIterator<Triple> find(Node s, Node p, Node o, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, datasetGraph.getContext());

        valid(s, p, o);

        try {
            Database db = datasetGraph.getDatabase();

            Map<Node, Long> resourceCache = new HashMap<>();
            Map<Node, Long> predicateCache = new HashMap<>();
            Map<Node, Permission> permissionCache = new HashMap<>();

            // Resolve subject if needed
            if (s != null && s != Node.ANY) {
                AticGraphUtils.bulkResolveResources(
                        Set.of(s),
                        ctx,
                        db,
                        false,
                        false,
                        resourceCache,
                        predicateCache,
                        permissionCache,
                        datasetGraph);

                if (!resourceCache.containsKey(s)) {
                    return NiceIterator.emptyIterator();
                }
            }

            // Resolve predicate if needed
            if (p != null && p != Node.ANY) {
                AticGraphUtils.bulkResolvePredicates(
                        Set.of(p),
                        ctx,
                        db,
                        predicateCache,
                        datasetGraph);

                if (!predicateCache.containsKey(p)) {
                    return NiceIterator.emptyIterator();
                }
            }

            StringBuilder sql = new StringBuilder("""
                SELECT
                    su.uri,
                    su.is_blank,
                    pu.uri,
                    splu.lex,
                    splu.lang,
                    splu.dt
                FROM system_splu splu
                JOIN resource_uri su ON su.id = splu.s
                JOIN property pu ON pu.id = splu.p
                LEFT JOIN resource_acl_effective ae_rs ON ae_rs.resource_id = splu.s AND ae_rs.user_id = ?
                WHERE COALESCE(ae_rs.permission, 0) >= ? AND (splu.user_primary_group = ? OR splu.user_primary_group IS NULL)
                """);

            List<Object> params = new ArrayList<>();
            params.add(ctx.getUserId());
            params.add(Permission.READ.getCode());
            params.add(ctx.getPrimaryGroupId());

            if (s != null && s != Node.ANY) {
                sql.append(" AND splu.s = ?");
                params.add(resourceCache.get(s));
            }

            if (p != null && p != Node.ANY) {
                sql.append(" AND splu.p = ?");
                params.add(predicateCache.get(p));
            }

            if (o != null && o != Node.ANY) {
                if (!o.isLiteral()) {
                    throw new IllegalArgumentException("Object must be a literal or Node.ANY");
                }

                LiteralLabel lit = o.getLiteral();

                sql.append(" AND splu.lex = ?");
                params.add(lit.getLexicalForm());

                String language = lit.language();
                if (!language.isBlank()) {
                    sql.append(" AND splu.lang = ?");
                    params.add(language);
                }

                String datatype = lit.getDatatypeURI();
                if (datatype != null) {
                    sql.append(" AND splu.dt = ?");
                    params.add(datatype);
                }
            }

            TransactionalResultSet txnResultSet = db.read(
                    sql.toString(),
                    params.toArray());

            ResultSetTripleMapper splMapper = rs -> {
                Node subj;

                String subjectUri = rs.getString(1);
                boolean subjectIsBlank = rs.getBoolean(2);

                if (subjectIsBlank) {
                    subj = NodeFactory.createBlankNode(subjectUri);
                } else {
                    subj = NodeFactory.createURI(subjectUri);
                }

                Node pred = NodeFactory.createURI(rs.getString(3));

                String lex = rs.getString(4);
                String lang = rs.getString(5);
                String dt = rs.getString(6);

                Node obj;

                if (lang != null && !lang.isBlank()) {
                    obj = NodeFactory.createLiteralLang(lex, lang);
                } else if (dt != null) {
                    obj = NodeFactory.createLiteralDT(
                            lex,
                            new BaseDatatype(dt));
                } else {
                    obj = NodeFactory.createLiteralString(lex);
                }

                return Triple.create(subj, pred, obj);
            };

            return new PagedTripleIterator(
                    txnResultSet,
                    null,
                    datasetGraph,
                    splMapper);

        } catch (SQLException ex) {
            throw new RuntimeException("Database error while finding system triples", ex);
        }
    }

    @Override
    public boolean contains(Node s, Node p, Node o, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, datasetGraph.getContext());

        valid(s, p, o);

        try {
            Database db = datasetGraph.getDatabase();

            Map<Node, Long> resourceCache = new HashMap<>();
            Map<Node, Long> predicateCache = new HashMap<>();
            Map<Node, Permission> permissionCache = new HashMap<>();

            // Resolve subject if needed
            if (s != null && s != Node.ANY) {
                AticGraphUtils.bulkResolveResources(
                        Set.of(s),
                        ctx,
                        db,
                        false,
                        false,
                        resourceCache,
                        predicateCache,
                        permissionCache,
                        datasetGraph);

                if (!resourceCache.containsKey(s)) {
                    return false;
                }
            }

            // Resolve predicate if needed
            if (p != null && p != Node.ANY) {
                AticGraphUtils.bulkResolvePredicates(
                        Set.of(p),
                        ctx,
                        db,
                        predicateCache,
                        datasetGraph);

                if (!predicateCache.containsKey(p)) {
                    return false;
                }
            }

            StringBuilder sql = new StringBuilder("""
                SELECT EXISTS (
                    SELECT 1
                    FROM system_splu splu
                    LEFT JOIN resource_acl_effective ae_rs ON ae_rs.resource_id = splu.s AND ae_rs.user_id = ?
                    WHERE COALESCE(ae_rs.permission, 0) >= ? AND (splu.user_primary_group = ? OR splu.user_primary_group IS NULL)
                """);

            List<Object> params = new ArrayList<>();
            params.add(ctx.getUserId());
            params.add(Permission.READ.getCode());
            params.add(ctx.getPrimaryGroupId());

            if (s != null && s != Node.ANY) {
                sql.append(" AND splu.s = ?");
                params.add(resourceCache.get(s));
            }

            if (p != null && p != Node.ANY) {
                sql.append(" AND splu.p = ?");
                params.add(predicateCache.get(p));
            }

            if (o != null && o != Node.ANY) {
                LiteralLabel lit = o.getLiteral();

                sql.append(" AND splu.lex = ?");
                params.add(lit.getLexicalForm());

                String language = lit.language();
                if (!language.isBlank()) {
                    sql.append(" AND splu.lang = ?");
                    params.add(language);
                }

                String datatype = lit.getDatatypeURI();
                if (datatype != null) {
                    sql.append(" AND splu.dt = ?");
                    params.add(datatype);
                }
            }

            sql.append(")");

            return db.read(sql.toString(), (ResultSet rs) -> rs.next() && rs.getBoolean(1), params.toArray());

        } catch (SQLException ex) {
            throw new RuntimeException("Database error while checking system triple existence", ex);
        }
    }

    @Override
    public int size(InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, datasetGraph.getContext());

        try {
            Database db = datasetGraph.getDatabase();

            String sql = """
                SELECT COUNT(*)
                FROM system_splu splu
                LEFT JOIN resource_acl_effective ae_rs ON ae_rs.resource_id = splu.s AND ae_rs.user_id = ?
                WHERE COALESCE(ae_rs.permission, 0) >= ? AND (splu.user_primary_group = ? OR splu.user_primary_group IS NULL)
                """;

            Object[] params = new Object[] { ctx.getUserId(), Permission.READ.getCode(), ctx.getPrimaryGroupId() };

            return db.read(
                    sql,
                    (ResultSet rs) -> {
                        rs.next();
                        return rs.getInt(1);
                    },
                    params);

        } catch (SQLException ex) {
            throw new RuntimeException("Database error while counting system triples", ex);
        }
    }

    //-----------------------------
    //write
    @Override
    public void add(Triple t, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, datasetGraph.getContext());

        valid(t);

        if (!t.isConcrete()) {
            throw new IllegalArgumentException("Invalid triple: " + t);
        }

        //per user transaction data
        InvocationContext finalCtx = ctx;
        //note: we use user id as key which is not so clean if user get group change during transaction
        TransactionData transactionData = user2trans.computeIfAbsent(ctx.getUserId(), uid -> new TransactionData(finalCtx));

        transactionData.buffer.add(t);
    }

    @Override
    public void remove(Node s, Node p, Node o, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, datasetGraph.getContext());

        valid(s, p, o);

        try {
            Database db = datasetGraph.getDatabase();

            Map<Node, Long> resourceCache = new HashMap<>();
            Map<Node, Long> predicateCache = new HashMap<>();
            Map<Node, Permission> permissionCache = new HashMap<>();

            // Resolve subject if needed
            if (s != null && s != Node.ANY) {
                AticGraphUtils.bulkResolveResources(
                        Set.of(s),
                        ctx,
                        db,
                        false,
                        false,
                        resourceCache,
                        predicateCache,
                        permissionCache,
                        datasetGraph);

                if (!resourceCache.containsKey(s)) {
                    return;
                }
            }

            // Resolve predicate if needed
            if (p != null && p != Node.ANY) {
                AticGraphUtils.bulkResolvePredicates(
                        Set.of(p),
                        ctx,
                        db,
                        predicateCache,
                        datasetGraph);

                if (!predicateCache.containsKey(p)) {
                    return;
                }
            }

            StringBuilder where = new StringBuilder("WHERE user_primary_group = ?");
            List<Object> params = new ArrayList<>();
            params.add(ctx.getPrimaryGroupId());

            if (s != null && s != Node.ANY) {
                where.append(" AND s = ?");
                params.add(resourceCache.get(s));
            }

            if (p != null && p != Node.ANY) {
                where.append(" AND p = ?");
                params.add(predicateCache.get(p));
            }

            if (o != null && o != Node.ANY) {
                LiteralLabel lit = o.getLiteral();

                where.append(" AND lex = ?");
                params.add(lit.getLexicalForm());

                String language = lit.language();
                if (!language.isBlank()) {
                    where.append(" AND lang = ?");
                    params.add(language);
                }

                String datatype = lit.getDatatypeURI();
                if (datatype != null) {
                    where.append(" AND dt = ?");
                    params.add(datatype);
                }
            }

            String sql = """
                WITH eligible AS (
                    SELECT id
                    FROM system_splu
                """
                    + where
                    + """
                )
                DELETE FROM system_splu
                WHERE id IN (
                    SELECT id
                    FROM eligible
                )
                """;

            db.write(sql, params.toArray());

        } catch (SQLException ex) {
            throw new RuntimeException("Database error while removing system triple(s)", ex);
        }
    }

    //delete reuses remove
    @Override
    public void delete(Triple t, InvocationContext ctx) {
        if (!t.isConcrete()) {
            throw new IllegalArgumentException("Triple has to be concrete: " + t);
        }
        remove(t.getSubject(), t.getPredicate(), t.getObject(), ctx);
    }

    //clear reuses remove with Node.ANY, Node.ANY, Node.ANY
    @Override
    public void clear(InvocationContext ctx) {
        //user needs to have admin permission to do that, just for safty reason
        //note: the ADMIN check is done in remove when Node.ANY, Node.ANY, Node.ANY
        //maybe removes splg/spog but not resource/property (see discussion on delete method)
        remove(Node.ANY, Node.ANY, Node.ANY, ctx);
    }

    //------------------------------------------------------
    //the delegates (e.g. opens Triple and delegates to s,p,o method)
    
    //helper method for remove and add in one step
    public void set(Node s, Node p, Node o, InvocationContext ctx) {
        remove(s, p, Node.ANY, ctx);
        add(s, p, o, ctx);
    }
    
    public void add(Node s, Node p, Node o, InvocationContext ctx) {
        add(Triple.create(s, p, o), ctx);
    }
    
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

    @Override
    public boolean dependsOn(Graph other, InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

}
