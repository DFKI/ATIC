package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.GraphEventManager;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.TransactionHandler;
import org.apache.jena.graph.Triple;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.util.iterator.NiceIterator;

public class UnionAticGraph implements AticGraph {

    private final AticGraph[] graphs;

    public UnionAticGraph(AticGraph... graphs) {
        this.graphs = graphs;
    }

    @Override
    public ExtendedIterator<Triple> find(Node s, Node p, Node o, InvocationContext ctx) {
        ExtendedIterator<Triple> it = NiceIterator.emptyIterator();

        for (AticGraph graph : graphs) {
            it = it.andThen(graph.find(s, p, o, ctx));
        }

        return it.filterKeep(new Predicate<Triple>() {
            private final Set<Triple> seen = new HashSet<>();

            @Override
            public boolean test(Triple triple) {
                return seen.add(triple);
            }
        });
    }

    @Override
    public int size(InvocationContext ctx) {
        Set<Triple> seen = new HashSet<>();

        ExtendedIterator<Triple> it = find(Node.ANY, Node.ANY, Node.ANY, ctx);

        try {
            while (it.hasNext()) {
                seen.add(it.next());
            }
        } finally {
            it.close();
        }

        return seen.size();
    }

    public int sizeEstimated(InvocationContext ctx) {
        int size = 0;

        for (AticGraph graph : graphs) {
            size += graph.size(ctx);
        }

        return size;
    }

    @Override
    public boolean contains(Node s, Node p, Node o, InvocationContext ctx) {
        for (AticGraph graph : graphs) {
            if (graph.contains(s, p, o, ctx)) {
                return true;
            }
        }

        return false;
    }

    //-----------------------------
    // write
    @Override
    public void add(Triple t, InvocationContext ctx) {
        for (AticGraph graph : graphs) {
            graph.add(t, ctx);
        }
    }

    @Override
    public void remove(Node s, Node p, Node o, InvocationContext ctx) {
        for (AticGraph graph : graphs) {
            graph.remove(s, p, o, ctx);
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
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public boolean isClosed(InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public PrefixMapping getPrefixMapping(InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public TransactionHandler getTransactionHandler() {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public GraphEventManager getEventManager() {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public boolean isIsomorphicWith(Graph g, InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    public boolean dependsOn(Graph other, InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported.");
    }

}
