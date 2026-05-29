package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.system.Txn;
import org.apache.jena.util.iterator.ExtendedIterator;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 *
 */
public class ConsistencyUnitTest {

    private SqliteAticDatasetGraph dataset;

    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);
    }

    @Test
    void testAddGraphAndAticGraphOperations(@TempDir Path tempDir) throws Exception {
        // bootstrap a fresh dataset (fields db and dataset are set up by @BeforeEach)

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        // create a named graph and register it with the dataset
        Node graphNode = NodeFactory.createURI("http://example.org/graph");
        Graph emptyGraph = ModelFactory.createDefaultModel().getGraph(); // empty Jena graph
        Txn.executeWrite(dataset, () -> dataset.addGraph(graphNode, emptyGraph, ctx));

        // obtain the AticGraph instance for further operations
        AticGraph aticGraph = Txn.calculateRead(dataset, () -> dataset.getGraph(graphNode, ctx));
        assertNotNull(aticGraph, "getGraph should return a non‑null AticGraph");

        // triples to be used in the test
        Triple t1 = Triple.create(
                NodeFactory.createURI("http://example.org/s"),
                NodeFactory.createURI("http://example.org/p"),
                NodeFactory.createLiteralString("o"));

        Triple t2 = Triple.create(
                NodeFactory.createURI("http://example.org/s2"),
                NodeFactory.createURI("http://example.org/p2"),
                NodeFactory.createLiteralString("x"));

        // add first triple
        Txn.executeWrite(dataset, () -> aticGraph.add(t1, ctx));

        // verify size and containment after first add
        Txn.executeRead(dataset, () -> {
            assertEquals(1L, aticGraph.size(ctx), "Graph should contain exactly one triple after first add");
            assertTrue(aticGraph.contains(t1, ctx), "The added triple must be reported as contained");
        });

        // add the same triple again – should be ignored because of the UNIQUE constraint
        Txn.executeWrite(dataset, () -> aticGraph.add(t1, ctx));
        Txn.executeRead(dataset, () -> assertEquals(1L, aticGraph.size(ctx),
                "Duplicate insert must be ignored (UNIQUE constraint)"));

        // a different triple must not be reported as contained yet
        Txn.executeRead(dataset, () -> assertFalse(aticGraph.contains(t2, ctx),
                "Graph must not contain a triple that was never added"));

        // add the second triple and verify updated size/containment
        Txn.executeWrite(dataset, () -> aticGraph.add(t2, ctx));
        Txn.executeRead(dataset, () -> {
            assertEquals(2L, aticGraph.size(ctx), "Graph should contain two triples after adding a second one");
            assertTrue(aticGraph.contains(t2, ctx), "Second added triple must be reported as contained");
        });
    }

    @Test
    void testSPOOperations(@TempDir Path tempDir) throws Exception {
        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        // create a named graph
        Node graphNode = NodeFactory.createURI("http://example.org/spoGraph");
        Graph emptyGraph = ModelFactory.createDefaultModel().getGraph();
        Txn.executeWrite(dataset, () -> dataset.addGraph(graphNode, emptyGraph, ctx));

        // obtain the AticGraph for the newly created graph
        AticGraph aticGraph = Txn.calculateRead(dataset, () -> dataset.getGraph(graphNode, ctx));
        assertNotNull(aticGraph, "AticGraph must not be null");

        // triples to be inserted (all use URI objects, i.e. SPO)
        Triple t1 = Triple.create(
                NodeFactory.createURI("http://example.org/s1"),
                NodeFactory.createURI("http://example.org/p1"),
                NodeFactory.createURI("http://example.org/o1"));

        Triple t2 = Triple.create(
                NodeFactory.createURI("http://example.org/s2"),
                NodeFactory.createURI("http://example.org/p1"),
                NodeFactory.createURI("http://example.org/o2"));

        Triple t3 = Triple.create(
                NodeFactory.createURI("http://example.org/s1"),
                NodeFactory.createURI("http://example.org/p2"),
                NodeFactory.createURI("http://example.org/o2"));

        // add the three triples
        Txn.executeWrite(dataset, () -> {
            aticGraph.add(t1, ctx);
            aticGraph.add(t2, ctx);
            aticGraph.add(t3, ctx);
        });

        // size must be 3
        Txn.executeRead(dataset, ()
                -> assertEquals(3L, aticGraph.size(ctx), "Graph should contain three SPO triples"));

        // duplicate insert must be ignored (UNIQUE constraint)
        Txn.executeWrite(dataset, () -> aticGraph.add(t1, ctx));
        Txn.executeRead(dataset, ()
                -> assertEquals(3L, aticGraph.size(ctx), "Duplicate insert must not change size"));

        // ----- containment checks with concrete nodes -----
        Txn.executeRead(dataset, () -> {
            assertTrue(aticGraph.contains(t1, ctx), "t1 must be present");
            assertTrue(aticGraph.contains(t2, ctx), "t2 must be present");
            assertTrue(aticGraph.contains(t3, ctx), "t3 must be present");
        });

        // ----- containment checks using Node.ANY (behaviour like find) -----
        Txn.executeRead(dataset, () -> {
            // any triple at all
            assertTrue(aticGraph.contains(Node.ANY, Node.ANY, Node.ANY, ctx),
                    "Graph is not empty, ANY‑ANY‑ANY must be true");

            // any triple with object o1
            assertTrue(aticGraph.contains(Node.ANY, Node.ANY,
                    NodeFactory.createURI("http://example.org/o1"), ctx),
                    "There is a triple with object o1");

            // any triple with predicate p1
            assertTrue(aticGraph.contains(Node.ANY,
                    NodeFactory.createURI("http://example.org/p1"), Node.ANY, ctx),
                    "There are triples with predicate p1");

            // any triple with subject s1
            assertTrue(aticGraph.contains(NodeFactory.createURI("http://example.org/s1"),
                    Node.ANY, Node.ANY, ctx),
                    "There are triples with subject s1");

            // specific pattern s1‑p2‑ANY
            assertTrue(aticGraph.contains(NodeFactory.createURI("http://example.org/s1"),
                    NodeFactory.createURI("http://example.org/p2"), Node.ANY, ctx),
                    "Pattern s1‑p2‑ANY must match");

            // pattern that does NOT exist
            assertFalse(aticGraph.contains(NodeFactory.createURI("http://example.org/s3"),
                    Node.ANY, Node.ANY, ctx),
                    "No triple with subject s3 should exist");

            assertFalse(aticGraph.contains(Node.ANY,
                    NodeFactory.createURI("http://example.org/p3"), Node.ANY, ctx),
                    "No triple with predicate p3 should exist");

            assertFalse(aticGraph.contains(Node.ANY, Node.ANY,
                    NodeFactory.createURI("http://example.org/o3"), ctx),
                    "No triple with object o3 should exist");
        });
    }

    @Test
    void testLiteralVariationsConsistency(@TempDir Path tempDir) throws Exception {
        // admin context – full rights on everything
        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        // create a named graph for the literal tests
        Node graphNode = NodeFactory.createURI("http://example.org/literalGraph");
        Graph emptyGraph = ModelFactory.createDefaultModel().getGraph();
        Txn.executeWrite(dataset, () -> dataset.addGraph(graphNode, emptyGraph, ctx));

        // obtain the AticGraph instance
        AticGraph aticGraph = Txn.calculateRead(dataset, () -> dataset.getGraph(graphNode, ctx));
        assertNotNull(aticGraph, "AticGraph must not be null");

        // -----------------------------------------------------------------
        // 1️⃣  Build triples with different literal flavours
        // -----------------------------------------------------------------
        Triple plainLit = Triple.create(
                NodeFactory.createURI("http://example.org/sPlain"),
                NodeFactory.createURI("http://example.org/pPlain"),
                NodeFactory.createLiteralString("plain"));                     // no lang, no datatype

        Triple langLit = Triple.create(
                NodeFactory.createURI("http://example.org/sLang"),
                NodeFactory.createURI("http://example.org/pLang"),
                NodeFactory.createLiteralLang("bonjour", "fr"));               // language tag only

        Triple dtLit = Triple.create(
                NodeFactory.createURI("http://example.org/sDt"),
                NodeFactory.createURI("http://example.org/pDt"),
                NodeFactory.createLiteralDT("42", XSDDatatype.XSDinteger));   // datatype only

        // -----------------------------------------------------------------
        // 2️⃣  Insert all triples (write transaction)
        // -----------------------------------------------------------------
        Txn.executeWrite(dataset, () -> {
            aticGraph.add(plainLit, ctx);
            aticGraph.add(langLit, ctx);
            aticGraph.add(dtLit, ctx);
        });

        // -----------------------------------------------------------------
        // 3️⃣  Verify total size (should be 4 literal triples)
        // -----------------------------------------------------------------
        Txn.executeRead(dataset, ()
                -> assertEquals(3L, aticGraph.size(ctx), "Graph must contain four literal triples"));

        // -----------------------------------------------------------------
        // 4️⃣  Exact‑match containment checks
        // -----------------------------------------------------------------
        Txn.executeRead(dataset, () -> {
            assertTrue(aticGraph.contains(plainLit, ctx), "Plain literal triple must be present");
            assertTrue(aticGraph.contains(langLit, ctx), "Language‑tagged literal triple must be present");
            assertTrue(aticGraph.contains(dtLit, ctx), "Datatype literal triple must be present");
        });

        // -----------------------------------------------------------------
        // 5️⃣  ANY‑position containment checks – behave like find
        // -----------------------------------------------------------------
        Txn.executeRead(dataset, () -> {
            // any triple at all
            assertTrue(aticGraph.contains(Node.ANY, Node.ANY, Node.ANY, ctx),
                    "ANY‑ANY‑ANY must be true for a non‑empty graph");

            // any triple with a plain literal object
            assertTrue(aticGraph.contains(Node.ANY, Node.ANY,
                    NodeFactory.createLiteralString("plain"), ctx),
                    "Should find the plain literal");

            // any triple with language‑tagged literal object
            assertTrue(aticGraph.contains(Node.ANY, Node.ANY,
                    NodeFactory.createLiteralLang("bonjour", "fr"), ctx),
                    "Should find the language‑tagged literal");

            // any triple with datatype literal object (integer)
            assertTrue(aticGraph.contains(Node.ANY, Node.ANY,
                    NodeFactory.createLiteralDT("42", XSDDatatype.XSDinteger), ctx),
                    "Should find the datatype literal");

            // any triple with a specific predicate but any literal object
            assertTrue(aticGraph.contains(Node.ANY,
                    NodeFactory.createURI("http://example.org/pLang"), Node.ANY, ctx),
                    "Predicate pLang must match at least one triple");

            // any triple with a specific subject but any literal object
            assertTrue(aticGraph.contains(NodeFactory.createURI("http://example.org/sDt"),
                    Node.ANY, Node.ANY, ctx),
                    "Subject sDt must match at least one triple");

            // negative checks – patterns that do not exist
            assertFalse(aticGraph.contains(Node.ANY,
                    NodeFactory.createURI("http://example.org/pMissing"), Node.ANY, ctx),
                    "No triple should have predicate pMissing");

            assertFalse(aticGraph.contains(NodeFactory.createURI("http://example.org/sMissing"),
                    Node.ANY, Node.ANY, ctx),
                    "No triple should have subject sMissing");

            assertFalse(aticGraph.contains(Node.ANY, Node.ANY,
                    NodeFactory.createLiteralString("nonexistent"), ctx),
                    "No triple should have the literal \"nonexistent\"");
        });

        // -----------------------------------------------------------------
        // 6️⃣  Re‑insert the same triples – size must stay unchanged (UNIQUE)
        // -----------------------------------------------------------------
        Txn.executeWrite(dataset, () -> {
            aticGraph.add(plainLit, ctx);
            aticGraph.add(langLit, ctx);
            aticGraph.add(dtLit, ctx);
        });
        Txn.executeRead(dataset, ()
                -> assertEquals(3L, aticGraph.size(ctx), "Duplicate inserts must not increase size"));
    }

    @Test
    void testSPOAddRemoveWithWildcards(@TempDir Path tempDir) throws Exception {
        // admin context – full rights on everything
        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        // create a named graph
        Node graphNode = NodeFactory.createURI("http://example.org/spoGraph");
        Graph emptyGraph = ModelFactory.createDefaultModel().getGraph();
        Txn.executeWrite(dataset, () -> dataset.addGraph(graphNode, emptyGraph, ctx));

        // obtain the AticGraph for the newly created graph
        AticGraph aticGraph = Txn.calculateRead(dataset, () -> dataset.getGraph(graphNode, ctx));
        assertNotNull(aticGraph, "AticGraph must not be null");

        // three distinct SPO triples
        Triple t1 = Triple.create(
                NodeFactory.createURI("http://example.org/s1"),
                NodeFactory.createURI("http://example.org/p1"),
                NodeFactory.createURI("http://example.org/o1"));

        Triple t2 = Triple.create(
                NodeFactory.createURI("http://example.org/s2"),
                NodeFactory.createURI("http://example.org/p1"),
                NodeFactory.createURI("http://example.org/o2"));

        Triple t3 = Triple.create(
                NodeFactory.createURI("http://example.org/s1"),
                NodeFactory.createURI("http://example.org/p2"),
                NodeFactory.createURI("http://example.org/o2"));

        // add the three triples
        Txn.executeWrite(dataset, () -> {
            aticGraph.add(t1, ctx);
            aticGraph.add(t2, ctx);
            aticGraph.add(t3, ctx);
        });

        // verify initial size
        Txn.executeRead(dataset, ()
                -> assertEquals(3L, aticGraph.size(ctx), "Graph should contain three SPO triples"));

        /* -------------------------------------------------
     * 1️⃣  Remove a single concrete triple (t1)
     * ------------------------------------------------- */
        Txn.executeWrite(dataset, () -> aticGraph.remove(t1.getSubject(),
                t1.getPredicate(),
                t1.getObject(),
                ctx));

        Txn.executeRead(dataset, () -> {
            assertEquals(2L, aticGraph.size(ctx), "Size after removing t1 must be 2");
            assertFalse(aticGraph.contains(t1, ctx), "t1 must no longer be present");
            assertTrue(aticGraph.contains(t2, ctx), "t2 must still be present");
            assertTrue(aticGraph.contains(t3, ctx), "t3 must still be present");
        });

        /* -------------------------------------------------
     * 2️⃣  Remove all triples that have subject s1 (wild‑card for p/o)
     * ------------------------------------------------- */
        Txn.executeWrite(dataset, () -> aticGraph.remove(
                NodeFactory.createURI("http://example.org/s1"),
                Node.ANY,
                Node.ANY,
                ctx));

        Txn.executeRead(dataset, () -> {
            assertEquals(1L, aticGraph.size(ctx), "Only t2 should remain after removing subject s1");
            assertFalse(aticGraph.contains(t3, ctx), "t3 (subject s1) must be gone");
            assertTrue(aticGraph.contains(t2, ctx), "t2 must still be present");
        });

        /* -------------------------------------------------
     * 3️⃣  Remove all triples that use predicate p1 (wild‑card for s/o)
     * ------------------------------------------------- */
        Txn.executeWrite(dataset, () -> aticGraph.remove(
                Node.ANY,
                NodeFactory.createURI("http://example.org/p1"),
                Node.ANY,
                ctx));

        Txn.executeRead(dataset, () -> {
            assertEquals(0L, aticGraph.size(ctx), "Graph should be empty after removing predicate p1");
            assertFalse(aticGraph.contains(t2, ctx), "t2 must be removed");
        });

        /* -------------------------------------------------
     * 4️⃣  Re‑add the triples and delete everything with a full wildcard
     * ------------------------------------------------- */
        Txn.executeWrite(dataset, () -> {
            aticGraph.add(t1, ctx);
            aticGraph.add(t2, ctx);
            aticGraph.add(t3, ctx);
        });
        Txn.executeRead(dataset, ()
                -> assertEquals(3L, aticGraph.size(ctx), "Graph should contain three triples again"));

        // full wildcard delete – should wipe the whole graph
        Txn.executeWrite(dataset, () -> aticGraph.remove(Node.ANY, Node.ANY, Node.ANY, ctx));

        Txn.executeRead(dataset, ()
                -> assertEquals(0L, aticGraph.size(ctx), "Full‑wildcard remove must delete all triples"));
    }

    @Test
    void testFindAndClearOperations(@TempDir Path tempDir) throws Exception {
        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graphNode = NodeFactory.createURI("http://example.org/findGraph");
        Graph emptyGraph = ModelFactory.createDefaultModel().getGraph();
        Txn.executeWrite(dataset, () -> dataset.addGraph(graphNode, emptyGraph, ctx));

        AticGraph aticGraph = Txn.calculateRead(dataset, () -> dataset.getGraph(graphNode, ctx));
        assertNotNull(aticGraph, "AticGraph must not be null");

        // mix of SPO and SPL triples
        Triple spo1 = Triple.create(
                NodeFactory.createURI("http://example.org/sA"),
                NodeFactory.createURI("http://example.org/pA"),
                NodeFactory.createURI("http://example.org/oA"));
        Triple spo2 = Triple.create(
                NodeFactory.createURI("http://example.org/sB"),
                NodeFactory.createURI("http://example.org/pB"),
                NodeFactory.createURI("http://example.org/oB"));
        Triple spl1 = Triple.create(
                NodeFactory.createURI("http://example.org/sC"),
                NodeFactory.createURI("http://example.org/pC"),
                NodeFactory.createLiteralString("lit1"));
        Triple spl2 = Triple.create(
                NodeFactory.createURI("http://example.org/sD"),
                NodeFactory.createURI("http://example.org/pD"),
                NodeFactory.createLiteralLang("bonjour", "fr"));

        Txn.executeWrite(dataset, () -> {
            aticGraph.add(spo1, ctx);
            aticGraph.add(spo2, ctx);
            aticGraph.add(spl1, ctx);
            aticGraph.add(spl2, ctx);
        });

        // ----- find SPO (subject‑any, predicate‑any, object‑any) -----
        Txn.executeRead(dataset, () -> {
            ExtendedIterator<Triple> it = aticGraph.find(Node.ANY, Node.ANY, Node.ANY, ctx);
            Set<Triple> found = new HashSet<>();
            it.forEachRemaining(found::add);
            assertTrue(found.contains(spo1), "spo1 missing");
            assertTrue(found.contains(spo2), "spo2 missing");
            assertTrue(found.contains(spl1), "spl1 missing");
            assertTrue(found.contains(spl2), "spl2 missing");
        });

        // ----- find SPL (subject‑any, predicate‑any, literal‑any) -----
        Txn.executeRead(dataset, () -> {
            ExtendedIterator<Triple> it = aticGraph.find(Node.ANY, Node.ANY,
                    NodeFactory.createLiteralString("lit1"), ctx);
            assertTrue(it.hasNext());
            Triple found = it.next();
            assertEquals(spl1, found);
            assertFalse(it.hasNext());
        });

        // ----- contains via find semantics (wildcards) -----
        Txn.executeRead(dataset, () -> {
            assertTrue(aticGraph.contains(Node.ANY, Node.ANY, Node.ANY, ctx));
            assertTrue(aticGraph.contains(Node.ANY, Node.ANY,
                    NodeFactory.createLiteralString("lit1"), ctx));
            assertTrue(aticGraph.contains(Node.ANY, Node.ANY,
                    NodeFactory.createLiteralLang("bonjour", "fr"), ctx));
            assertFalse(aticGraph.contains(Node.ANY, Node.ANY,
                    NodeFactory.createLiteralString("missing"), ctx));
        });

        // ----- clear the graph using full‑wildcard remove (same as clear) -----
        Txn.executeWrite(dataset, () -> aticGraph.remove(Node.ANY, Node.ANY, Node.ANY, ctx));
        Txn.executeRead(dataset, () -> {
            assertEquals(0L, aticGraph.size(ctx));
            assertFalse(aticGraph.contains(spo1, ctx));
            assertFalse(aticGraph.contains(spl1, ctx));
        });
    }
    
}
