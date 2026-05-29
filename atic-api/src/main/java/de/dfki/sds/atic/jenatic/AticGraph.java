
package de.dfki.sds.atic.jenatic;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.util.iterator.ExtendedIterator;

/**
 *
 */
public interface AticGraph extends Graph {

    @Deprecated
    @SuppressWarnings("removal")
    @Override
    default boolean dependsOn(Graph other) {
        return dependsOn(other, InvocationContext.EMPTY);
    }
    @Deprecated
    boolean dependsOn(Graph other, InvocationContext ctx);

    @Override
    default PrefixMapping getPrefixMapping() {
        return getPrefixMapping(InvocationContext.EMPTY);
    }
    PrefixMapping getPrefixMapping(InvocationContext ctx);

    @Override
    default void add(Triple t) {
        add(t, InvocationContext.EMPTY);
    }
    void add(Triple t, InvocationContext ctx);

    @Override
    default void delete(Triple t) {
        delete(t, InvocationContext.EMPTY);
    }
    void delete(Triple t, InvocationContext ctx);

    @Override
    default ExtendedIterator<Triple> find(Triple m) {
        return find(m, InvocationContext.EMPTY);
    }
    ExtendedIterator<Triple> find(Triple m, InvocationContext ctx);

    @Override
    default ExtendedIterator<Triple> find(Node s, Node p, Node o) {
        return find(s, p, o, InvocationContext.EMPTY);
    }
    ExtendedIterator<Triple> find(Node s, Node p, Node o, InvocationContext ctx);

    @Override
    default boolean isIsomorphicWith(Graph g) {
        return isIsomorphicWith(g, InvocationContext.EMPTY);
    }
    boolean isIsomorphicWith(Graph g, InvocationContext ctx);

    @Override
    default boolean contains(Node s, Node p, Node o) {
        return contains(s, p, o, InvocationContext.EMPTY);
    }
    boolean contains(Node s, Node p, Node o, InvocationContext ctx);

    @Override
    default boolean contains(Triple t) {
        return contains(t, InvocationContext.EMPTY);
    }
    boolean contains(Triple t, InvocationContext ctx);

    @Override
    default void clear() {
        clear(InvocationContext.EMPTY);
    }
    void clear(InvocationContext ctx);

    @Override
    default void remove(Node s, Node p, Node o) {
        remove(s, p, o, InvocationContext.EMPTY);
    }
    void remove(Node s, Node p, Node o, InvocationContext ctx);

    @Override
    default void close() {
        close(InvocationContext.EMPTY);
    }
    void close(InvocationContext ctx);

    @Override
    default boolean isEmpty() {
        return isEmpty(InvocationContext.EMPTY);
    }
    boolean isEmpty(InvocationContext ctx);

    @Override
    default int size() {
        return size(InvocationContext.EMPTY);
    }
    int size(InvocationContext ctx);

    @Override
    default boolean isClosed() {
        return isClosed(InvocationContext.EMPTY);
    }
    boolean isClosed(InvocationContext ctx);
    
    
    
}
