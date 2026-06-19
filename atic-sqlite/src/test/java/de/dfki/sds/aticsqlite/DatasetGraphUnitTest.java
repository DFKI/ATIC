package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.AticTriple;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.TxnType;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.jupiter.api.Assertions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class DatasetGraphUnitTest {

    private SqliteAticDatasetGraph dataset;

    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);
    }

    @Test
    void datasetGraphQuadOperationsConsistency() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:quadGraph");

        Node s1 = NodeFactory.createURI("urn:s1");
        Node s2 = NodeFactory.createURI("urn:s2");

        Node p = NodeFactory.createURI("urn:p");

        Node o1 = NodeFactory.createLiteralString("o1");
        Node o2 = NodeFactory.createLiteralString("o2");

        // create graph first
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // ADD
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {

            dataset.add(graph, s1, p, o1, ctx);
            dataset.add(graph, s1, p, o2, ctx);
            dataset.add(graph, s2, p, o1, ctx);

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // CONTAINS
        // -------------------------
        dataset.begin(TxnType.READ);
        try {

            assertTrue(dataset.contains(graph, s1, p, o1, ctx));
            assertTrue(dataset.contains(graph, s1, p, o2, ctx));
            assertTrue(dataset.contains(graph, s2, p, o1, ctx));

            assertFalse(dataset.contains(graph, s2, p, o2, ctx));

        } finally {
            dataset.end();
        }

        // -------------------------
        // FIND
        // -------------------------
        dataset.begin(TxnType.READ);
        try {

            Iterator<Quad> it = dataset.find(graph, s1, p, Node.ANY, ctx);

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(2, results.size());

        } finally {
            dataset.end();
        }

        // -------------------------
        // SIZE
        // -------------------------
        dataset.begin(TxnType.READ);
        try {

            long size = dataset.size(ctx);

            assertTrue(size >= 1); // at least our graph exists

        } finally {
            dataset.end();
        }

        // -------------------------
        // DELETE specific quad
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {

            dataset.delete(graph, s1, p, o1, ctx);

            dataset.commit();
        } finally {
            dataset.end();
        }
        
        //delete has to be concrete
        dataset.begin(TxnType.WRITE);
        try {

            Assertions.assertThrows(IllegalArgumentException.class, () -> {
                dataset.delete(graph, s1, p, Node.ANY, ctx);
            });

            dataset.commit();
        } finally {
            dataset.end();
        }

        dataset.begin(TxnType.READ);
        try {

            assertFalse(dataset.contains(graph, s1, p, o1, ctx));
            assertTrue(dataset.contains(graph, s1, p, o2, ctx));

        } finally {
            dataset.end();
        }

        // -------------------------
        // DELETE ANY pattern
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {

            dataset.deleteAny(graph, s1, p, Node.ANY, ctx);

            dataset.commit();
        } finally {
            dataset.end();
        }

        dataset.begin(TxnType.READ);
        try {

            Iterator<Quad> it = dataset.find(graph, s1, p, Node.ANY, ctx);
            assertFalse(it.hasNext());

            // s2 triple should still exist
            assertTrue(dataset.contains(graph, s2, p, o1, ctx));

        } finally {
            dataset.end();
        }

    }

    @Test
    void testUnionGraphAndAnyGraphQueries() throws Exception {

        User user = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(user).build();

        Node g1 = NodeFactory.createURI("urn:test:g1");
        Node g2 = NodeFactory.createURI("urn:test:g2");
        Node g3 = NodeFactory.createURI("urn:test:g3");

        Node s1 = NodeFactory.createURI("urn:test:s1");
        Node s2 = NodeFactory.createURI("urn:test:s2");
        Node s3 = NodeFactory.createURI("urn:test:s3");

        Node p = NodeFactory.createURI("urn:test:p");

        Node o1 = NodeFactory.createLiteralString("o1");
        Node o2 = NodeFactory.createLiteralString("o2");
        Node o3 = NodeFactory.createLiteralString("o3");

        // -----------------------
        // Create graphs and triples
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {

            dataset.addGraph(g1, GraphFactory.createDefaultGraph(), ctx);
            dataset.addGraph(g2, GraphFactory.createDefaultGraph(), ctx);
            dataset.addGraph(g3, GraphFactory.createDefaultGraph(), ctx);

            dataset.add(g1, s1, p, o1, ctx);
            dataset.add(g2, s2, p, o2, ctx);
            dataset.add(g3, s3, p, o3, ctx);

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // listGraphNodes
        // -----------------------
        dataset.begin(TxnType.READ);
        try {

            List<Node> graphs = new ArrayList<>();
            dataset.listGraphNodes(ctx).forEachRemaining(graphs::add);

            assertEquals(3, graphs.size());
            assertTrue(graphs.contains(g1));
            assertTrue(graphs.contains(g2));
            assertTrue(graphs.contains(g3));

        } finally {
            dataset.end();
        }

        // -----------------------
        // union graph
        // -----------------------
        dataset.begin(TxnType.READ);
        try {

            AticGraph unionGraph = dataset.getUnionGraph(ctx);

            assertTrue(unionGraph.contains(s1, p, o1, ctx));
            assertTrue(unionGraph.contains(s2, p, o2, ctx));
            assertTrue(unionGraph.contains(s3, p, o3, ctx));

            // should have exactly 3 triples
            int count = 0;
            Iterator<Triple> it = unionGraph.find(Node.ANY, Node.ANY, Node.ANY, ctx);
            while (it.hasNext()) {
                it.next();
                count++;
            }

            assertEquals(3, count);

        } finally {
            dataset.end();
        }

        // -----------------------
        // find with g = Node.ANY
        // -----------------------
        dataset.begin(TxnType.READ);
        try {

            Iterator<Quad> it = dataset.find(Node.ANY, Node.ANY, Node.ANY, Node.ANY, ctx);

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(3, results.size());

        } finally {
            dataset.end();
        }

        // -----------------------
        // contains with g = Node.ANY
        // -----------------------
        dataset.begin(TxnType.READ);
        try {

            assertTrue(dataset.contains(Node.ANY, s1, p, o1, ctx));
            assertTrue(dataset.contains(Node.ANY, s2, p, o2, ctx));
            assertTrue(dataset.contains(Node.ANY, s3, p, o3, ctx));

            assertFalse(dataset.contains(Node.ANY,
                    NodeFactory.createURI("urn:test:missing"),
                    p,
                    o1,
                    ctx));

        } finally {
            dataset.end();
        }
    }

    @Test
    void testClearResetsDatasetButKeepsDefaultGraph() throws Exception {

        User user = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(user).build();

        Node g1 = NodeFactory.createURI("urn:test:g1");
        Node g2 = NodeFactory.createURI("urn:test:g2");

        Node s = NodeFactory.createURI("urn:test:s");
        Node p = NodeFactory.createURI("urn:test:p");
        Node o = NodeFactory.createLiteralString("value");

        // -----------------------
        // Setup: create graphs + data
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(g1, GraphFactory.createDefaultGraph(), ctx);
            dataset.addGraph(g2, GraphFactory.createDefaultGraph(), ctx);

            dataset.add(g1, s, p, o, ctx);
            dataset.add(g2, s, p, o, ctx);

            dataset.add(Quad.defaultGraphIRI, s, p, o, ctx);

            dataset.commit();
        } finally {
            dataset.end();
        }

        // sanity check before clear
        dataset.begin(TxnType.READ);
        try {
            assertTrue(dataset.contains(g1, s, p, o, ctx));
            assertTrue(dataset.contains(g2, s, p, o, ctx));
            assertTrue(dataset.contains(Quad.defaultGraphIRI, s, p, o, ctx));
        } finally {
            dataset.end();
        }

        // -----------------------
        // Act: clear dataset
        // -----------------------
        dataset.executeWrite(() -> {
            dataset.clear(ctx);
        });

        // -----------------------
        // Assert: everything reset
        // -----------------------
        dataset.begin(TxnType.READ);
        try {

            // 1. No triples anywhere
            assertFalse(dataset.contains(Node.ANY, s, p, o, ctx),
                    "No triples should remain after clear()");

            // 2. No named graphs remain
            List<Node> graphs = new ArrayList<>();
            dataset.listGraphNodes(ctx).forEachRemaining(graphs::add);

            assertTrue(graphs.isEmpty(),
                    "No named graphs should remain after clear()");

            // 3. Default graph still exists
            AticGraph defaultGraph = dataset.getDefaultGraph(ctx);
            assertNotNull(defaultGraph, "Default graph must still exist");

            // but it should be empty
            Iterator<Triple> it = defaultGraph.find(Node.ANY, Node.ANY, Node.ANY, ctx);
            assertFalse(it.hasNext(), "Default graph should be empty after clear()");

        } finally {
            dataset.end();
        }
    }

    @Test
    void testDeleteAnyAcrossGraphs() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node g1 = NodeFactory.createURI("urn:test:delAny:g1");
        Node g2 = NodeFactory.createURI("urn:test:delAny:g2");

        Node s = NodeFactory.createURI("urn:test:s");
        Node p = NodeFactory.createURI("urn:test:p");

        Node oA = NodeFactory.createLiteralString("oA");
        Node oB = NodeFactory.createLiteralString("oB");

        // -----------------------
        // Insert triples into g1 and g2
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(g1, GraphFactory.createDefaultGraph(), ctx);
            dataset.addGraph(g2, GraphFactory.createDefaultGraph(), ctx);

            dataset.add(g1, s, p, oA, ctx);
            dataset.add(g1, s, p, oB, ctx);

            dataset.add(g2, s, p, oA, ctx);
            dataset.add(g2, s, p, oB, ctx);

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Sanity checks before deletion
        // -----------------------
        dataset.begin(TxnType.READ);
        try {
            assertTrue(dataset.contains(g1, s, p, oA, ctx));
            assertTrue(dataset.contains(g1, s, p, oB, ctx));
            assertTrue(dataset.contains(g2, s, p, oA, ctx));
            assertTrue(dataset.contains(g2, s, p, oB, ctx));
        } finally {
            dataset.end();
        }

        // -----------------------
        // deleteAny on specific graph (g1) – only g1 triples should be removed
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.deleteAny(g1, s, p, Node.ANY, ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        dataset.begin(TxnType.READ);
        try {
            // g1's triples should be gone
            assertFalse(dataset.contains(g1, s, p, oA, ctx),
                    "g1 should no longer contain triple oA after deleteAny on g1");
            assertFalse(dataset.contains(g1, s, p, oB, ctx),
                    "g1 should no longer contain triple oB after deleteAny on g1");

            // g2's triples should remain
            assertTrue(dataset.contains(g2, s, p, oA, ctx),
                    "g2 should still contain oA because deleteAny was only on g1");
            assertTrue(dataset.contains(g2, s, p, oB, ctx),
                    "g2 should still contain oB because deleteAny was only on g1");

        } finally {
            dataset.end();
        }

        // -----------------------
        // deleteAny across ANY graph – this should remove remaining matches
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.deleteAny(Node.ANY, s, p, Node.ANY, ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        dataset.begin(TxnType.READ);
        try {
            // now g2 should also be clean
            assertFalse(dataset.contains(g2, s, p, oA, ctx),
                    "g2 should no longer contain oA after deleteAny on Node.ANY");
            assertFalse(dataset.contains(g2, s, p, oB, ctx),
                    "g2 should no longer contain oB after deleteAny on Node.ANY");

        } finally {
            dataset.end();
        }
    }

    @Test
    void testDeleteAnyWithNodeANYAcrossAllGraphs() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node g1 = NodeFactory.createURI("urn:test:deleteAnyAll:g1");
        Node g2 = NodeFactory.createURI("urn:test:deleteAnyAll:g2");

        Node s = NodeFactory.createURI("urn:test:s");
        Node p = NodeFactory.createURI("urn:test:p");

        Node oA = NodeFactory.createLiteralString("oA");
        Node oB = NodeFactory.createLiteralString("oB");

        // -----------------------
        // Insert triples into g1 and g2
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(g1, GraphFactory.createDefaultGraph(), ctx);
            dataset.addGraph(g2, GraphFactory.createDefaultGraph(), ctx);

            // both graphs get oA and oB
            dataset.add(g1, s, p, oA, ctx);
            dataset.add(g1, s, p, oB, ctx);

            dataset.add(g2, s, p, oA, ctx);
            dataset.add(g2, s, p, oB, ctx);

            dataset.commit();
        } finally {
            dataset.end();
        }

        // sanity before deletion
        dataset.begin(TxnType.READ);
        try {
            assertTrue(dataset.contains(g1, s, p, oA, ctx));
            assertTrue(dataset.contains(g1, s, p, oB, ctx));
            assertTrue(dataset.contains(g2, s, p, oA, ctx));
            assertTrue(dataset.contains(g2, s, p, oB, ctx));
        } finally {
            dataset.end();
        }

        // -----------------------
        // deleteAny across ANY graph for pattern (ANY, s, p, oA)
        // this should remove oA in both g1 and g2
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.deleteAny(Node.ANY, s, p, oA, ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // verify that oA was removed from both graphs
        dataset.begin(TxnType.READ);
        try {
            assertFalse(dataset.contains(g1, s, p, oA, ctx),
                    "g1 should no longer contain oA after deleteAny with Node.ANY");
            assertTrue(dataset.contains(g1, s, p, oB, ctx),
                    "g1 should still contain oB");

            assertFalse(dataset.contains(g2, s, p, oA, ctx),
                    "g2 should no longer contain oA after deleteAny with Node.ANY");
            assertTrue(dataset.contains(g2, s, p, oB, ctx),
                    "g2 should still contain oB");
        } finally {
            dataset.end();
        }
    }

    @Test
    void testAticTripleConfidencePersistedInSpog() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:confidenceGraph");

        Node s = NodeFactory.createURI("urn:test:s");
        Node p = NodeFactory.createURI("urn:test:p");
        Node o = NodeFactory.createURI("urn:test:o");

        double confidence = Math.random(); // [0,1)

        // -----------------------
        // Insert triple
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);

            AticTriple triple = AticTriple.create(s, p, o, confidence);
            dataset.getGraph(graph, ctx).add(triple, ctx);

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Verify via db.read(...)
        // -----------------------
        dataset.begin(TxnType.READ);
        try {

            Double dbConfidence = dataset.getDatabase().read(
                    "SELECT confidence FROM spog "
                    + "JOIN graph g ON spog.g = g.id "
                    + "WHERE g.uri = ? LIMIT 1",
                    rs -> {
                        if (!rs.next()) {
                            throw new AssertionError("Expected one row in spog");
                        }
                        return rs.getDouble("confidence");
                    },
                    graph.getURI()
            );

            assertEquals(confidence, dbConfidence, 1e-9,
                    "Stored confidence should match inserted value");

        } finally {
            dataset.end();
        }
    }

    @Test
    void testAticTripleConfidenceRetrievedViaFind() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:confidenceGraphFind");

        Node s = NodeFactory.createURI("urn:test:s");
        Node p = NodeFactory.createURI("urn:test:p");
        Node o = NodeFactory.createURI("urn:test:o");

        double confidence = Math.random(); // [0,1)

        // -----------------------
        // Insert triple
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);

            AticTriple triple = AticTriple.create(s, p, o, confidence);
            dataset.getGraph(graph, ctx).add(triple, ctx);

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Verify via find(...)
        // -----------------------
        dataset.begin(TxnType.READ);
        try {

            Iterator<Triple> it = dataset.getGraph(graph, ctx)
                    .find(s, p, o, ctx);

            assertTrue(it.hasNext(), "Expected one triple");

            Triple t = it.next();

            assertTrue(t instanceof AticTriple,
                    "Returned triple should be an AticTriple");

            AticTriple at = (AticTriple) t;

            assertEquals(confidence, at.getConfidence(), 1e-9,
                    "Confidence should match inserted value");

            assertFalse(it.hasNext(), "Expected exactly one triple");

        } finally {
            dataset.end();
        }
    }
    
    @Test
    void testAticTripleConfidenceRetrievedViaVirtualRDFStar() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:confidenceGraphFind");

        Node s = NodeFactory.createURI("urn:test:s");
        Node p = NodeFactory.createURI("urn:test:p");
        Node o = NodeFactory.createURI("urn:test:o");

        double confidence = Math.random(); // [0,1)

        // -----------------------
        // Insert triple
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);

            AticTriple triple = AticTriple.create(s, p, o, confidence);
            dataset.getGraph(graph, ctx).add(triple, ctx);

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Verify via find(...)
        // -----------------------
        dataset.begin(TxnType.READ);
        try {

            Node quotedTriple = NodeFactory.createTripleTerm(s, p, o);
            Node confidencePredicate = SqliteAticGraph.ATIC_CONFIDENCE;
            
            Iterator<Triple> it = dataset.getGraph(graph, ctx)
                    .find(quotedTriple, confidencePredicate, Node.ANY, ctx);

            assertTrue(it.hasNext(), "Expected one triple");

            Triple t = it.next();

            assertEquals(confidence, (double) t.getObject().getLiteralValue(), 1e-9,
                    "Confidence should match inserted value");

            assertFalse(it.hasNext(), "Expected exactly one triple");

        } finally {
            dataset.end();
        }
    }

    @Test
    void testAticTripleApplicabilityPersistedInSpog() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:applicabilityGraph");

        Node s = NodeFactory.createURI("urn:test:s");
        Node p = NodeFactory.createURI("urn:test:p");
        Node o = NodeFactory.createURI("urn:test:o");

        double applicability = 0.75;

        // -----------------------
        // Insert triple
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);

            AticTriple triple = AticTriple.create(s, p, o, 1.0, applicability);
            dataset.getGraph(graph, ctx).add(triple, ctx);

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Verify via db.read(...)
        // -----------------------
        dataset.begin(TxnType.READ);
        try {

            Double dbApplicability = dataset.getDatabase().read(
                    "SELECT applicability FROM spog "
                    + "JOIN graph g ON spog.g = g.id "
                    + "WHERE g.uri = ? LIMIT 1",
                    rs -> {
                        if (!rs.next()) {
                            throw new AssertionError("Expected one row in spog");
                        }
                        return rs.getDouble("applicability");
                    },
                    graph.getURI()
            );

            assertEquals(applicability, dbApplicability, 1e-9,
                    "Stored applicability should match inserted value");

        } finally {
            dataset.end();
        }
    }

    @Test
    void testAticTripleApplicabilityRetrievedViaFind() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:applicabilityGraphFind");

        Node s = NodeFactory.createURI("urn:test:s");
        Node p = NodeFactory.createURI("urn:test:p");
        Node o = NodeFactory.createURI("urn:test:o");

        double applicability = -0.5;

        // -----------------------
        // Insert triple
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);

            AticTriple triple = AticTriple.create(s, p, o, 1.0, applicability);
            dataset.getGraph(graph, ctx).add(triple, ctx);

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Verify via find(...)
        // -----------------------
        dataset.begin(TxnType.READ);
        try {

            Iterator<Triple> it = dataset.getGraph(graph, ctx)
                    .find(s, p, o, ctx);

            assertTrue(it.hasNext(), "Expected one triple");

            Triple t = it.next();

            assertTrue(t instanceof AticTriple,
                    "Returned triple should be an AticTriple");

            AticTriple at = (AticTriple) t;

            assertEquals(applicability, at.getApplicability(), 1e-9,
                    "Applicability should match inserted value");

            assertFalse(it.hasNext(), "Expected exactly one triple");

        } finally {
            dataset.end();
        }
    }

    @Test
    void testAticTripleDefaultApplicabilityIsOne() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:defaultApplicabilityGraph");

        Node s = NodeFactory.createURI("urn:test:s");
        Node p = NodeFactory.createURI("urn:test:p");
        Node o = NodeFactory.createURI("urn:test:o");

        // -----------------------
        // Insert triple without specifying applicability
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);

            AticTriple triple = AticTriple.create(s, p, o, 1.0);
            dataset.getGraph(graph, ctx).add(triple, ctx);

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Verify via find(...)
        // -----------------------
        dataset.begin(TxnType.READ);
        try {

            Iterator<Triple> it = dataset.getGraph(graph, ctx)
                    .find(s, p, o, ctx);

            assertTrue(it.hasNext(), "Expected one triple");

            Triple t = it.next();
            AticTriple at = (AticTriple) t;

            assertEquals(1.0, at.getApplicability(), 1e-9,
                    "Default applicability should be 1.0");

            assertFalse(it.hasNext(), "Expected exactly one triple");

        } finally {
            dataset.end();
        }
    }

    @Test
    void testAticTripleApplicabilityBoundaryValues() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:boundaryApplicabilityGraph");

        Node s = NodeFactory.createURI("urn:test:s");
        Node p = NodeFactory.createURI("urn:test:p");
        Node o = NodeFactory.createURI("urn:test:o");

        // Test -1.0
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);

            AticTriple tripleNeg = AticTriple.create(s, p, o, 1.0, -1.0);
            dataset.getGraph(graph, ctx).add(tripleNeg, ctx);

            dataset.commit();
        } finally {
            dataset.end();
        }

        dataset.begin(TxnType.READ);
        try {
            Iterator<Triple> it = dataset.getGraph(graph, ctx).find(s, p, o, ctx);
            assertTrue(it.hasNext());
            assertEquals(-1.0, ((AticTriple) it.next()).getApplicability(), 1e-9,
                    "Applicability -1.0 should persist correctly");
        } finally {
            dataset.end();
        }

        // Test 1.0
        dataset.begin(TxnType.WRITE);
        try {
            Node s2 = NodeFactory.createURI("urn:test:s2");
            AticTriple triplePos = AticTriple.create(s2, p, o, 1.0, 1.0);
            dataset.getGraph(graph, ctx).add(triplePos, ctx);

            dataset.commit();
        } finally {
            dataset.end();
        }

        dataset.begin(TxnType.READ);
        try {
            Node s2 = NodeFactory.createURI("urn:test:s2");
            Iterator<Triple> it = dataset.getGraph(graph, ctx).find(s2, p, o, ctx);
            assertTrue(it.hasNext());
            assertEquals(1.0, ((AticTriple) it.next()).getApplicability(), 1e-9,
                    "Applicability 1.0 should persist correctly");
        } finally {
            dataset.end();
        }
    }

    @Test
    void testAticTripleApplicabilityValidation() {
        Node s = NodeFactory.createURI("urn:test:s");
        Node p = NodeFactory.createURI("urn:test:p");
        Node o = NodeFactory.createURI("urn:test:o");

        // Valid boundaries
        AticTriple t = AticTriple.create(s, p, o, 1.0, -1.0);
        assertEquals(-1.0, t.getApplicability(), 1e-9);

        t = AticTriple.create(s, p, o, 1.0, 1.0);
        assertEquals(1.0, t.getApplicability(), 1e-9);

        // Invalid values should throw
        assertThrows(IllegalArgumentException.class, () ->
            AticTriple.create(s, p, o, 1.0, 1.1));
        assertThrows(IllegalArgumentException.class, () ->
            AticTriple.create(s, p, o, 1.0, -1.1));
        assertThrows(IllegalArgumentException.class, () ->
            AticTriple.create(s, p, o, 1.0, 2.0));
        assertThrows(IllegalArgumentException.class, () ->
            AticTriple.create(s, p, o, 1.0, -2.0));
    }
}
