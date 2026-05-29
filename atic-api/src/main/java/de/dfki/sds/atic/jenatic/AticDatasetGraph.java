package de.dfki.sds.atic.jenatic;

import java.util.Iterator;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.riot.system.PrefixMap;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;

/**
 *
 */
public interface AticDatasetGraph extends DatasetGraph {

    @Override
    public default Graph getDefaultGraph() {
        return getDefaultGraph(InvocationContext.EMPTY);
    }

    public AticGraph getDefaultGraph(InvocationContext ctx);

    @Override
    public default Graph getGraph(Node graphNode) {
        return getGraph(graphNode, InvocationContext.EMPTY);
    }

    public AticGraph getGraph(Node graphNode, InvocationContext ctx);

    @Override
    public default Graph getUnionGraph() {
        return getUnionGraph(InvocationContext.EMPTY);
    }

    public AticGraph getUnionGraph(InvocationContext ctx);
    
    public AticGraph getUnionGraph(Iterator<Node> graphNodes, InvocationContext ctx);

    @Override
    public default boolean containsGraph(Node graphNode) {
        return containsGraph(graphNode, InvocationContext.EMPTY);
    }

    public boolean containsGraph(Node graphNode, InvocationContext ctx);

    @Override
    public default void addGraph(Node graphName, Graph graph) {
        addGraph(graphName, graph, InvocationContext.EMPTY);
    }

    public void addGraph(Node graphName, Graph graph, InvocationContext ctx);

    @Override
    public default void removeGraph(Node graphName) {
        removeGraph(graphName, InvocationContext.EMPTY);
    }

    public void removeGraph(Node graphName, InvocationContext ctx);

    @Override
    public default Iterator<Node> listGraphNodes() {
        return listGraphNodes(InvocationContext.EMPTY);
    }

    public Iterator<Node> listGraphNodes(InvocationContext ctx);

    @Override
    public default void add(Quad quad) {
        add(quad, InvocationContext.EMPTY);
    }

    public void add(Quad quad, InvocationContext ctx);

    @Override
    public default void delete(Quad quad) {
        delete(quad, InvocationContext.EMPTY);
    }

    public void delete(Quad quad, InvocationContext ctx);

    @Override
    public default void add(Node g, Node s, Node p, Node o) {
        add(g, s, p, o, InvocationContext.EMPTY);
    }

    public void add(Node g, Node s, Node p, Node o, InvocationContext ctx);

    @Override
    public default void delete(Node g, Node s, Node p, Node o) {
        delete(g, s, p, o, InvocationContext.EMPTY);
    }

    public void delete(Node g, Node s, Node p, Node o, InvocationContext ctx);

    @Override
    public default void deleteAny(Node g, Node s, Node p, Node o) {
        deleteAny(g, s, p, o, InvocationContext.EMPTY);
    }

    public void deleteAny(Node g, Node s, Node p, Node o, InvocationContext ctx);

    @Override
    public default Iterator<Quad> find(Quad quad) {
        return find(quad, InvocationContext.EMPTY);
    }

    public Iterator<Quad> find(Quad quad, InvocationContext ctx);

    @Override
    public default Iterator<Quad> find(Node g, Node s, Node p, Node o) {
        return find(g, s, p, o, InvocationContext.EMPTY);
    }

    public Iterator<Quad> find(Node g, Node s, Node p, Node o, InvocationContext ctx);

    @Override
    public default Iterator<Quad> findNG(Node g, Node s, Node p, Node o) {
        return findNG(g, s, p, o, InvocationContext.EMPTY);
    }

    public Iterator<Quad> findNG(Node g, Node s, Node p, Node o, InvocationContext ctx);

    @Override
    public default boolean contains(Node g, Node s, Node p, Node o) {
        return contains(g, s, p, o, InvocationContext.EMPTY);
    }

    public boolean contains(Node g, Node s, Node p, Node o, InvocationContext ctx);

    @Override
    public default boolean contains(Quad quad) {
        return contains(quad, InvocationContext.EMPTY);
    }

    public boolean contains(Quad quad, InvocationContext ctx);

    @Override
    public default void clear() {
        clear(InvocationContext.EMPTY);
    }

    public void clear(InvocationContext ctx);

    @Override
    public default boolean isEmpty() {
        return isEmpty(InvocationContext.EMPTY);
    }

    public boolean isEmpty(InvocationContext ctx);

    @Override
    public default long size() {
        return size(InvocationContext.EMPTY);
    }

    public long size(InvocationContext ctx);

    @Override
    public default PrefixMap prefixes() {
        return prefixes(InvocationContext.EMPTY);
    }

    public PrefixMap prefixes(InvocationContext ctx);
    
}
