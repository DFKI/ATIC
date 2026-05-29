package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.Permission;
import de.dfki.sds.atic.api.IdAndUri;
import de.dfki.sds.atic.api.IdAndUriOrLiteral;
import de.dfki.sds.atic.api.IdAndUriQuad;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.TxnType;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.changes.RDFChangesCollector;
import org.apache.jena.sparql.JenaTransactionException;
import org.apache.jena.sparql.core.Transactional;
import org.apache.jena.vocabulary.ORG;
import org.apache.jena.vocabulary.RDF;

/**
 *
 */
public class RDFPatchEmitterTransactional implements Transactional {

    private final ThreadLocal<RDFChangesCollector> transCollector = new ThreadLocal<>();
    private final ThreadLocal<TxnType> transType = new ThreadLocal<>();
    private final ThreadLocal<Long> transBegin = new ThreadLocal<>();
    private final ThreadLocal<Boolean> transCommitted = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<List<InvocationContext>> transContexts = ThreadLocal.withInitial(ArrayList::new);

    private final List<RDFPatchListener> listeners = new ArrayList<>();
    
    public final static Node ATIC_GRAPH_PERMISSION = NodeFactory.createURI("urn:atic:graphPermission");
    public final static Node ATIC_RESOURCE_PERMISSION = NodeFactory.createURI("urn:atic:resourcePermission");
    public final static Node ATIC_USER_GROUP = NodeFactory.createURI("urn:atic:userGroup");
    
    //TODO later better ontology concepts
    public final static Node memberOf = ORG.memberOf.asNode();
    public final static Node primaryGroup = NodeFactory.createURI("urn:atic:primaryGroup");
    public final static Node Group = NodeFactory.createURI("urn:atic:Group");

    public RDFPatchEmitterTransactional() {
        
    }

    //what needs to be reported
    //- quad level add/remove with uri and id
    //- graph add/remove (maybe not necessary, because handled at quad level)
    //- user/group add/remove assign/unassign
    //- graph/resource permission share/unshare 
    public void add(IdAndUriQuad quad, InvocationContext ctx) {
        RDFChangesCollector col = transCollector.get();
        if (col == null) {
            throw new JenaTransactionException("Not in a transaction");
        }

        Node gn = toNode(quad.getGraph());
        Node sn = toNode(quad.getSubject());
        Node pn = toNode(quad.getPredicate());
        Node on = toNode(quad.getObject());
        
        //TODO maybe deliver confidence with rdf-star

        col.add(gn, sn, pn, on);
        transContexts.get().add(ctx);
    }

    public void delete(IdAndUriQuad quad, InvocationContext ctx) {
        RDFChangesCollector col = transCollector.get();
        if (col == null) {
            throw new JenaTransactionException("Not in a transaction");
        }

        Node gn = toNode(quad.getGraph());
        Node sn = toNode(quad.getSubject());
        Node pn = toNode(quad.getPredicate());
        Node on = toNode(quad.getObject());
        
        //TODO maybe deliver confidence with rdf-star

        col.delete(gn, sn, pn, on);
        transContexts.get().add(ctx);
    }

    public void shareGraph(IdAndUri graph, IdAndUri group, Permission permission, InvocationContext ctx) {
        RDFChangesCollector col = transCollector.get();
        if (col == null) {
            throw new JenaTransactionException("Not in a transaction");
        }
        
        Node gn = wrap(ATIC_GRAPH_PERMISSION);
        Node sn = toNode(group);
        Node pn = toNode(IdAndUri.create(permission.getCode(), permission.getUri()));
        Node on = toNode(graph);
        
        col.add(gn, sn, pn, on);
        transContexts.get().add(ctx);
    }
    
    public void shareResource(IdAndUri resource, IdAndUri group, Permission permission, InvocationContext ctx) {
        RDFChangesCollector col = transCollector.get();
        if (col == null) {
            throw new JenaTransactionException("Not in a transaction");
        }
        
        Node gn = wrap(ATIC_RESOURCE_PERMISSION);
        Node sn = toNode(group);
        Node pn = toNode(IdAndUri.create(permission.getCode(), permission.getUri()));
        Node on = toNode(resource);
        
        col.add(gn, sn, pn, on);
        transContexts.get().add(ctx);
    }
    
    public void unshareGraph(IdAndUri graph, IdAndUri group, InvocationContext ctx) {
        RDFChangesCollector col = transCollector.get();
        if (col == null) {
            throw new JenaTransactionException("Not in a transaction");
        }
        
        Node gn = wrap(ATIC_GRAPH_PERMISSION);
        Node sn = toNode(group);
        Node pn = wrap(Permission.ANY);
        Node on = toNode(graph);
        
        col.delete(gn, sn, pn, on);
        transContexts.get().add(ctx);
    }
    
    public void unshareResource(IdAndUri resource, IdAndUri group, InvocationContext ctx) {
        RDFChangesCollector col = transCollector.get();
        if (col == null) {
            throw new JenaTransactionException("Not in a transaction");
        }
        
        Node gn = wrap(ATIC_RESOURCE_PERMISSION);
        Node sn = toNode(group);
        Node pn = wrap(Permission.ANY);
        Node on = toNode(resource);
        
        col.delete(gn, sn, pn, on);
        transContexts.get().add(ctx);
    }

    public void addUser(IdAndUri user, IdAndUri primaryGroup, InvocationContext ctx) {
        RDFChangesCollector col = transCollector.get();
        if (col == null) {
            throw new JenaTransactionException("Not in a transaction");
        }
        
        Node gn = wrap(ATIC_USER_GROUP);
        Node sn = toNode(user);
        Node pn = wrap(RDFPatchEmitterTransactional.primaryGroup);
        Node on = toNode(primaryGroup);
        
        col.add(gn, sn, pn, on);
        transContexts.get().add(ctx);
    }
    
    public void removeUser(IdAndUri user, IdAndUri primaryGroup, InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported yet.");
    }
    
    public void addGroup(IdAndUri group, InvocationContext ctx) {
        RDFChangesCollector col = transCollector.get();
        if (col == null) {
            throw new JenaTransactionException("Not in a transaction");
        }
        
        Node gn = wrap(ATIC_USER_GROUP);
        Node sn = toNode(group);
        Node pn = wrap(RDF.type.asNode());
        Node on = wrap(Group);
        
        col.add(gn, sn, pn, on);
        transContexts.get().add(ctx);
    }
    
    public void removeGroup(IdAndUri group, InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported yet.");
    }
    
    public void assignUserToGroup(IdAndUri user, IdAndUri group, InvocationContext ctx) {
        RDFChangesCollector col = transCollector.get();
        if (col == null) {
            throw new JenaTransactionException("Not in a transaction");
        }
        
        Node gn = wrap(ATIC_USER_GROUP);
        Node sn = toNode(user);
        Node pn = wrap(memberOf);
        Node on = toNode(group);
        
        col.add(gn, sn, pn, on);
        transContexts.get().add(ctx);
    }
    
    public void unassignUserFromGroup(IdAndUri user, IdAndUri group, InvocationContext ctx) {
        RDFChangesCollector col = transCollector.get();
        if (col == null) {
            throw new JenaTransactionException("Not in a transaction");
        }
        
        Node gn = wrap(ATIC_USER_GROUP);
        Node sn = toNode(user);
        Node pn = wrap(memberOf);
        Node on = toNode(group);
        
        col.delete(gn, sn, pn, on);
        transContexts.get().add(ctx);
    }
    
    //====================================================
    public void addListener(RDFPatchListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(RDFPatchListener listener) {
        listeners.remove(listener);
    }
    
    public boolean hasListeners() {
        return !listeners.isEmpty();
    }

    public void emit(RDFChangesCollector collector) {
        //nothing to emit if no listeners registered or collector is null
        if (listeners.isEmpty() || collector == null) {
            return;
        }

        //- unique uri for this patch
        collector.header("uri", NodeFactory.createURI("urn:atic:patch-" + UUID.randomUUID().toString()));

        //- datetime (as xsd datetime from now)
        String now = Instant.now().toString(); // ISO-8601
        Node createdNode = NodeFactory.createLiteralDT(now, XSDDatatype.XSDdateTime);
        collector.header("created", createdNode);

        //- duration (from start to finish): as xsd duration literal
        long beginNano = transBegin.get();
        long endNano = System.nanoTime();
        long durationNano = endNano - beginNano;
        Duration duration = Duration.ofNanos(durationNano);
        String durationLex = duration.toString(); // ISO-8601 duration (e.g. PT0.123S)
        Node durationNode = NodeFactory.createLiteralDT(durationLex, XSDDatatype.XSDduration);
        collector.header("duration", durationNode);

        //- user (or users) involved
        //only one header field is allowed
        List<InvocationContext> ctxs = transContexts.get();
        Set<Integer> userIds = ctxs.stream()
                .map(InvocationContext::getUserId)
                .collect(Collectors.toSet());
        if(!userIds.isEmpty()) {
            String userIdsStr = userIds.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));
            collector.header("userIds", NodeFactory.createLiteralString(userIdsStr));
        }
        
        //for now we use this as indicator that triple changes are in the rdf patch
        if(userIds.isEmpty()) {
            //if no user is mentioned: no triple changes so do not emit
            return;
        }
        
        collector.finish();

        RDFPatch patch = collector.getRDFPatch();

        for (RDFPatchListener listener : listeners) {
            listener.handlePatch(patch);
        }
    }

    //====================================================
    //transaction
    @Override
    public void begin(TxnType type) {
        RDFChangesCollector col = new RDFChangesCollector();
        col.start();
        col.txnBegin();
        transCollector.set(col);
        transType.set(type);
        transBegin.set(System.nanoTime());
        transContexts.get().clear();
    }

    @Override
    public boolean promote(Promote mode) {
        RDFChangesCollector col = transCollector.get();
        if (col == null) {
            return false;
        }
        TxnType current = transType.get();
        if (current == TxnType.WRITE) {
            return true;
        }
        //TODO a savepoint in RDFChangesCollector?
        /*
        try (Statement stmt = conn.createStatement()) {
            // A promotion for SQLite is effectively marking a savepoint
            stmt.execute("SAVEPOINT promote_sp;");
            transType.set(TxnType.WRITE);
            return true;
        } catch (SQLException e) {
            return false;
        }
         */
        transType.set(TxnType.WRITE);
        return true;
    }

    @Override
    public void commit() {
        RDFChangesCollector col = transCollector.get();
        if (col == null) {
            return;
        }
        col.txnCommit();
        //but not yet emitted, happens in end()
        transCommitted.set(true);
    }

    @Override
    public void abort() {
        RDFChangesCollector col = transCollector.get();
        if (col == null) {
            return;
        }
        //abort means reset: it will not be emitted
        col.reset();
        transCommitted.set(false);
    }

    @Override
    public void end() {
        RDFChangesCollector col = transCollector.get();
        if (col == null) {
            return;
        }

        if (Boolean.TRUE.equals(transCommitted.get())) {
            //now emit it: will finish it
            emit(col);
        }

        transCollector.remove();
        transType.remove();
        transCommitted.remove();
        transBegin.remove();
        transContexts.remove();
    }

    @Override
    public ReadWrite transactionMode() {
        TxnType type = transType.get();
        if (type == null) {
            return null;
        }
        return (type == TxnType.WRITE) ? ReadWrite.WRITE : ReadWrite.READ;
    }

    @Override
    public TxnType transactionType() {
        return transType.get();
    }

    @Override
    public boolean isInTransaction() {
        return transCollector.get() != null;
    }

    //================================================
    //static helper
    
    //urn:atic:{id}:{enc-uri}
    public static Node toNode(IdAndUri idAndUri) {
        String uriEnc = URLEncoder.encode(idAndUri.getUri(), StandardCharsets.UTF_8);
        return NodeFactory.createURI("urn:atic:" + idAndUri.getId() + ":" + uriEnc);
    }

    public static Node toNode(IdAndUriOrLiteral idAndUriLiteral) {
        if (idAndUriLiteral.isLiteral()) {
            return idAndUriLiteral.getLiteral();
        }
        return toNode((IdAndUri) idAndUriLiteral);
    }
    
    //urn:atic:{id}:{enc-uri}, here we use id = -1
    public static Node wrap(Node simpleNode) {
        String uriEnc = URLEncoder.encode(simpleNode.getURI(), StandardCharsets.UTF_8);
        return NodeFactory.createURI("urn:atic:" + -1 + ":" + uriEnc);
    }
    
    public static Node unwrap(Node n) {
        if (n.isLiteral()) {
            return n;
        }

        //urn:atic:{id}:{enc-uri}
        String[] segments = n.getURI().split(":");
        String encUri = segments[segments.length - 1];
        return NodeFactory.createURI(URLDecoder.decode(encUri, StandardCharsets.UTF_8));
    }
    
    public static IdAndUriOrLiteral unwrapIdAndUri(Node n) {
        if (n.isLiteral()) {
            return IdAndUriOrLiteral.create(n);
        }

        //urn:atic:{id}:{enc-uri}
        String[] segments = n.getURI().split(":");
        
        long id = Long.parseLong(segments[segments.length - 2]);
        String encUri = segments[segments.length - 1];
        Node node = NodeFactory.createURI(URLDecoder.decode(encUri, StandardCharsets.UTF_8));
        
        return IdAndUriOrLiteral.create(id, node);
    }
    
    /**
     * Checks if graph is a special graph: graph permission, resource permission, user group.
     * @param g
     * @return 
     */
    public static boolean isSpecialGraph(Node g) {
        return g.equals(ATIC_GRAPH_PERMISSION) ||
                g.equals(ATIC_RESOURCE_PERMISSION) ||
                g.equals(ATIC_USER_GROUP);
    } 
    
}
