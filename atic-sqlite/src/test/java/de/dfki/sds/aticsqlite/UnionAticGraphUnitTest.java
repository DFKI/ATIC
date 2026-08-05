package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.system.Txn;
import org.apache.jena.util.iterator.ExtendedIterator;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 *
 */
public class UnionAticGraphUnitTest {

    private SqliteAticDatasetGraph dataset;

    private InvocationContext adminCtx;

    @BeforeEach
    void setup(@TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        adminCtx = new InvocationContext.Builder().fromUser(adminUser).build();
    }

    @Test
    public void testUnion() {

        Node g1 = dataset.calculateWrite(() -> {
            return dataset.addGraph(Graph.emptyGraph, adminCtx);
        });

        Node g2 = dataset.calculateWrite(() -> {
            return dataset.addGraph(Graph.emptyGraph, adminCtx);
        });

        AticGraph union = dataset.calculateRead(() -> {
            return new UnionAticGraph(
                    dataset.getGraph(g1, adminCtx),
                    dataset.getGraph(g2, adminCtx)
            );
        });

        Node s1 = NodeFactory.createURI("urn:test:s1");
        Node p1 = NodeFactory.createURI("urn:test:p1");
        Node o1 = NodeFactory.createLiteralString("value1");

        Node s2 = NodeFactory.createURI("urn:test:s2");
        Node p2 = NodeFactory.createURI("urn:test:p2");
        Node o2 = NodeFactory.createLiteralString("value2");

        Node sharedS = NodeFactory.createURI("urn:test:shared");
        Node sharedP = NodeFactory.createURI("urn:test:value");
        Node sharedO = NodeFactory.createLiteralString("shared");

        Txn.executeWrite(dataset, () -> {
            AticGraph graph1 = dataset.getGraph(g1, adminCtx);
            AticGraph graph2 = dataset.getGraph(g2, adminCtx);

            graph1.add(
                    Triple.create(s1, p1, o1),
                    adminCtx);

            graph2.add(
                    Triple.create(s2, p2, o2),
                    adminCtx);

            // Same triple in both graphs to test distinct handling
            graph1.add(
                    Triple.create(sharedS, sharedP, sharedO),
                    adminCtx);

            graph2.add(
                    Triple.create(sharedS, sharedP, sharedO),
                    adminCtx);
        });

        dataset.executeRead(() -> {
            assertTrue(
                    union.contains(s1, p1, o1, adminCtx),
                    "Union should contain triple from graph 1");

            assertTrue(
                    union.contains(s2, p2, o2, adminCtx),
                    "Union should contain triple from graph 2");

            assertTrue(
                    union.contains(sharedS, sharedP, sharedO, adminCtx),
                    "Union should contain shared triple");

            assertFalse(
                    union.contains(
                            NodeFactory.createURI("urn:test:none"),
                            p1,
                            o1,
                            adminCtx),
                    "Union should not contain missing triple");

            assertEquals(
                    3,
                    union.size(adminCtx),
                    "Union size should count distinct triples only");

            ExtendedIterator<Triple> it = union.find(
                    Node.ANY,
                    Node.ANY,
                    Node.ANY,
                    adminCtx);

            Set<Triple> triples = new HashSet<>();
            try {
                while (it.hasNext()) {
                    triples.add(it.next());
                }
            } finally {
                it.close();
            }

            assertEquals(3, triples.size());
            assertTrue(triples.contains(Triple.create(s1, p1, o1)));
            assertTrue(triples.contains(Triple.create(s2, p2, o2)));
            assertTrue(triples.contains(Triple.create(sharedS, sharedP, sharedO)));
        });
    }

    @Test
    public void testUnionAddWritesToAllGraphs() {

        Node g1 = dataset.calculateWrite(() -> {
            return dataset.addGraph(Graph.emptyGraph, adminCtx);
        });

        Node g2 = dataset.calculateWrite(() -> {
            return dataset.addGraph(Graph.emptyGraph, adminCtx);
        });

        AticGraph union = dataset.calculateRead(() -> {
            return new UnionAticGraph(
                    dataset.getGraph(g1, adminCtx),
                    dataset.getGraph(g2, adminCtx)
            );
        });

        Node s = NodeFactory.createURI("urn:test:s");
        Node p = NodeFactory.createURI("urn:test:p");
        Node o = NodeFactory.createLiteralString("value");

        Triple triple = Triple.create(s, p, o);

        Txn.executeWrite(dataset, () -> {
            union.add(triple, adminCtx);
        });

        dataset.executeRead(() -> {
            AticGraph graph1 = dataset.getGraph(g1, adminCtx);
            AticGraph graph2 = dataset.getGraph(g2, adminCtx);

            assertTrue(
                    graph1.contains(s, p, o, adminCtx),
                    "Graph 1 should contain triple added through union");

            assertTrue(
                    graph2.contains(s, p, o, adminCtx),
                    "Graph 2 should contain triple added through union");

            assertTrue(
                    union.contains(s, p, o, adminCtx),
                    "Union should contain triple added through union");

            assertEquals(
                    1,
                    union.size(adminCtx),
                    "Union should contain one distinct triple");
        });
    }

    @Test
    public void testUnionRemoveDeletesFromAllGraphs() {

        Node g1 = dataset.calculateWrite(() -> {
            return dataset.addGraph(Graph.emptyGraph, adminCtx);
        });

        Node g2 = dataset.calculateWrite(() -> {
            return dataset.addGraph(Graph.emptyGraph, adminCtx);
        });

        AticGraph union = dataset.calculateRead(() -> {
            return new UnionAticGraph(
                    dataset.getGraph(g1, adminCtx),
                    dataset.getGraph(g2, adminCtx)
            );
        });

        Node s = NodeFactory.createURI("urn:test:s");
        Node p = NodeFactory.createURI("urn:test:p");
        Node o1 = NodeFactory.createLiteralString("value1");
        Node o2 = NodeFactory.createLiteralString("value2");

        Txn.executeWrite(dataset, () -> {
            AticGraph graph1 = dataset.getGraph(g1, adminCtx);
            AticGraph graph2 = dataset.getGraph(g2, adminCtx);

            graph1.add(
                    Triple.create(s, p, o1),
                    adminCtx);

            graph2.add(
                    Triple.create(s, p, o2),
                    adminCtx);
        });

        dataset.executeRead(() -> {
            assertTrue(
                    union.contains(s, p, o1, adminCtx),
                    "Union should contain first triple");

            assertTrue(
                    union.contains(s, p, o2, adminCtx),
                    "Union should contain second triple");

            assertEquals(
                    2,
                    union.size(adminCtx),
                    "Union should contain both triples before removal");
        });

        Txn.executeWrite(dataset, () -> {
            union.remove(s, p, Node.ANY, adminCtx);
        });

        dataset.executeRead(() -> {
            AticGraph graph1 = dataset.getGraph(g1, adminCtx);
            AticGraph graph2 = dataset.getGraph(g2, adminCtx);

            assertFalse(
                    graph1.contains(s, p, o1, adminCtx),
                    "Graph 1 triple should be removed");

            assertFalse(
                    graph2.contains(s, p, o2, adminCtx),
                    "Graph 2 triple should be removed");

            assertFalse(
                    union.contains(s, p, o1, adminCtx),
                    "Union should not contain first triple anymore");

            assertFalse(
                    union.contains(s, p, o2, adminCtx),
                    "Union should not contain second triple anymore");

            assertEquals(
                    0,
                    union.size(adminCtx),
                    "Union should be empty after removal");
        });
    }

    @Test
    public void testUnionSizeVsSizeEstimated() {

        Node g1 = dataset.calculateWrite(() -> {
            return dataset.addGraph(Graph.emptyGraph, adminCtx);
        });

        Node g2 = dataset.calculateWrite(() -> {
            return dataset.addGraph(Graph.emptyGraph, adminCtx);
        });

        UnionAticGraph union = dataset.calculateRead(() -> {
            return new UnionAticGraph(
                    dataset.getGraph(g1, adminCtx),
                    dataset.getGraph(g2, adminCtx)
            );
        });

        Node s = NodeFactory.createURI("urn:test:s");
        Node p = NodeFactory.createURI("urn:test:p");
        Node o = NodeFactory.createLiteralString("value");

        Triple triple = Triple.create(s, p, o);

        Txn.executeWrite(dataset, () -> {
            AticGraph graph1 = dataset.getGraph(g1, adminCtx);
            AticGraph graph2 = dataset.getGraph(g2, adminCtx);

            graph1.add(triple, adminCtx);
            graph2.add(triple, adminCtx);
        });

        dataset.executeRead(() -> {
            assertEquals(
                    1,
                    union.size(adminCtx),
                    "Union size should count distinct triples only");

            assertEquals(
                    2,
                    union.sizeEstimated(adminCtx),
                    "Estimated size should sum all underlying graph sizes");
        });
    }
}
