package de.dfki.sds.aticsqlite;

import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.rdfpatch.RDFChanges;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.RDFPatchOps;
import org.apache.jena.sparql.core.Quad;

/**
 * RDFChanges collector that removes duplicate change events while preserving insertion order.
 *
 * Unlike RDFChangesCollector, repeated identical add/delete/prefix/header events are only stored once.
 */
public class RDFChangesDistinctCollector implements RDFChanges {

    private final Set<Quad> adds = new LinkedHashSet<>();
    private final Set<Quad> deletes = new LinkedHashSet<>();

    private final Set<Prefix> addPrefixes = new LinkedHashSet<>();
    private final Set<Prefix> deletePrefixes = new LinkedHashSet<>();

    private final Set<Header> headers = new LinkedHashSet<>();

    private boolean started = false;
    private boolean finished = false;

    @Override
    public void header(String field, Node value) {
        headers.add(new Header(field, value));
    }

    @Override
    public void add(Node g, Node s, Node p, Node o) {
        adds.add(Quad.create(g, s, p, o));
    }

    @Override
    public void delete(Node g, Node s, Node p, Node o) {
        deletes.add(Quad.create(g, s, p, o));
    }

    @Override
    public void addPrefix(Node gn, String prefix, String uriStr) {
        addPrefixes.add(new Prefix(gn, prefix, uriStr));
    }

    @Override
    public void deletePrefix(Node gn, String prefix) {
        deletePrefixes.add(new Prefix(gn, prefix, null));
    }

    @Override
    public void txnBegin() {
        // Transaction markers are not stored.
    }

    @Override
    public void txnCommit() {
        // Transaction markers are not stored.
    }

    @Override
    public void txnAbort() {
        // Transaction markers are not stored.
    }

    @Override
    public void segment() {
        // Segment markers are not stored.
    }

    @Override
    public void start() {
        started = true;
    }

    @Override
    public void finish() {
        finished = true;
    }

    /**
     * Build a RDFPatch from the collected distinct changes.
     *
     * @return the patch
     */
    public RDFPatch getRDFPatch() {
        return RDFPatchOps.build(changes -> {

            headers.forEach(h
                    -> changes.header(h.field(), h.value())
            );

            deletePrefixes.forEach(p
                    -> changes.deletePrefix(
                            p.graph(),
                            p.prefix()
                    )
            );

            addPrefixes.forEach(p
                    -> changes.addPrefix(
                            p.graph(),
                            p.prefix(),
                            p.uri()
                    )
            );

            deletes.forEach(q
                    -> changes.delete(
                            q.getGraph(),
                            q.getSubject(),
                            q.getPredicate(),
                            q.getObject()
                    )
            );

            adds.forEach(q
                    -> changes.add(
                            q.getGraph(),
                            q.getSubject(),
                            q.getPredicate(),
                            q.getObject()
                    )
            );

        });
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isFinished() {
        return finished;
    }

    public Set<Quad> getAdds() {
        return Set.copyOf(adds);
    }

    public Set<Quad> getDeletes() {
        return Set.copyOf(deletes);
    }

    private record Header(String field, Node value) {

    }

    private record Prefix(Node graph, String prefix, String uri) {

    }

    public int size() {
        return headers.size()
                + addPrefixes.size()
                + deletePrefixes.size()
                + adds.size()
                + deletes.size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }
}
