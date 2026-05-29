package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.system.Txn;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 *
 */
public class ComparisonUnitTest {

    private SqliteAticDatasetGraph dataset;

    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);
    }

    @Test
    void compareSQLiteAndInMemoryGraphOperations(@TempDir Path tempDir) throws Exception {
        // common data
        String graphUri = "http://example.org/compareGraph";
        Node graphNode = NodeFactory.createURI(graphUri);
        Triple testTriple = Triple.create(
                NodeFactory.createURI("http://example.org/s"),
                NodeFactory.createURI("http://example.org/p"),
                NodeFactory.createLiteralString("value"));

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        // SQLite‑backed dataset (created in @BeforeEach)
        Txn.executeWrite(dataset, ()
                -> dataset.addGraph(graphNode, ModelFactory.createDefaultModel().getGraph(), ctx));

        AticGraph sqliteGraph = Txn.calculateRead(dataset, () -> dataset.getGraph(graphNode, ctx));
        assertNotNull(sqliteGraph, "SQLite AticGraph must be created");

        // in‑memory transactional dataset
        DatasetGraph memDG = DatasetGraphFactory.createTxnMem();

        // add the same named graph to the in‑memory dataset
        Txn.executeWrite(memDG, ()
                -> memDG.addGraph(graphNode, ModelFactory.createDefaultModel().getGraph()));

        Graph memGraph = Txn.calculateRead(memDG, () -> memDG.getGraph(graphNode));

        // add the triple to both graphs
        Txn.executeWrite(dataset, () -> sqliteGraph.add(testTriple, ctx));
        Txn.executeWrite(memDG, () -> memGraph.add(testTriple));

        // size checks (must be identical)
        Txn.executeRead(dataset, ()
                -> assertEquals(1L, sqliteGraph.size(ctx), "SQLite graph size after add"));
        Txn.executeRead(memDG, ()
                -> assertEquals(1, memGraph.size(), "In‑memory graph size after add"));

        // contains checks (must be identical)
        Txn.executeRead(dataset, ()
                -> assertTrue(sqliteGraph.contains(testTriple, ctx), "SQLite must contain the added triple"));
        Txn.executeRead(memDG, ()
                -> assertTrue(memGraph.contains(testTriple), "In‑memory must contain the added triple"));

        // delete the triple from both graphs
        Txn.executeWrite(dataset, () -> sqliteGraph.delete(testTriple, ctx));
        Txn.executeWrite(memDG, () -> memGraph.delete(testTriple));

        // size after delete (both must be zero)
        Txn.executeRead(dataset, ()
                -> assertEquals(0L, sqliteGraph.size(ctx), "SQLite graph size after delete"));
        Txn.executeRead(memDG, ()
                -> assertEquals(0, memGraph.size(), "In‑memory graph size after delete"));

        // contains after delete (both must be false)
        Txn.executeRead(dataset, ()
                -> assertFalse(sqliteGraph.contains(testTriple, ctx), "SQLite must not contain the deleted triple"));
        Txn.executeRead(memDG, ()
                -> assertFalse(memGraph.contains(testTriple), "In‑memory must not contain the deleted triple"));
    }

    @Test
    void compareDeleteNonExistingTriple(@TempDir Path tempDir) throws Exception {
        // data common to both graphs
        String graphUri = "http://example.org/nonExistingGraph";
        Node graphNode = NodeFactory.createURI(graphUri);
        Triple missingTriple = Triple.create(
                NodeFactory.createURI("http://example.org/sMissing"),
                NodeFactory.createURI("http://example.org/pMissing"),
                NodeFactory.createLiteralString("nope"));

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        // SQLite‑backed dataset
        Txn.executeWrite(dataset, ()
                -> dataset.addGraph(graphNode, ModelFactory.createDefaultModel().getGraph(), ctx));
        AticGraph sqliteGraph = Txn.calculateRead(dataset, () -> dataset.getGraph(graphNode, ctx));

        // in‑memory transactional dataset
        DatasetGraph memDG = DatasetGraphFactory.createTxnMem();
        Txn.executeWrite(memDG, ()
                -> memDG.addGraph(graphNode, ModelFactory.createDefaultModel().getGraph()));
        Graph memGraph = Txn.calculateRead(memDG, () -> memDG.getGraph(graphNode));

        // attempt to delete a triple that was never added
        Txn.executeWrite(dataset, () -> sqliteGraph.delete(missingTriple, ctx));
        Txn.executeWrite(memDG, () -> memGraph.delete(missingTriple));

        // both graphs should still be empty
        Txn.executeRead(dataset, ()
                -> assertEquals(0L, sqliteGraph.size(ctx), "SQLite graph should remain empty"));
        Txn.executeRead(memDG, ()
                -> assertEquals(0, memGraph.size(), "In‑memory graph should remain empty"));

        // contains must be false for the missing triple
        Txn.executeRead(dataset, ()
                -> assertFalse(sqliteGraph.contains(missingTriple, ctx), "SQLite must not contain the missing triple"));
        Txn.executeRead(memDG, ()
                -> assertFalse(memGraph.contains(missingTriple), "In‑memory must not contain the missing triple"));
    }

    @Test
    void compareFindAndFindNGBetweenSQLiteAndInMemory() throws Exception {

        User user = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });
        
        InvocationContext ctx = new InvocationContext.Builder().fromUser(user).build();

        Node g1 = NodeFactory.createURI("http://example.org/g1");
        Node g2 = NodeFactory.createURI("http://example.org/g2");

        Node s = NodeFactory.createURI("http://example.org/s");
        Node p = NodeFactory.createURI("http://example.org/p");

        Node o1 = NodeFactory.createLiteralString("o1");
        Node o2 = NodeFactory.createLiteralString("o2");
        Node oDefault = NodeFactory.createLiteralString("default");

        Triple t1 = Triple.create(s, p, o1);
        Triple t2 = Triple.create(s, p, o2);
        Triple tDefault = Triple.create(s, p, oDefault);

        // -----------------------
        // SQLite dataset
        // -----------------------
        Txn.executeWrite(dataset, () -> {
            dataset.addGraph(g1, ModelFactory.createDefaultModel().getGraph(), ctx);
            dataset.addGraph(g2, ModelFactory.createDefaultModel().getGraph(), ctx);

            dataset.getGraph(g1, ctx).add(t1, ctx);
            dataset.getGraph(g2, ctx).add(t2, ctx);

            dataset.getDefaultGraph(ctx).add(tDefault, ctx);
        });

        // -----------------------
        // In-memory dataset
        // -----------------------
        DatasetGraph memDG = DatasetGraphFactory.createTxnMem();

        Txn.executeWrite(memDG, () -> {
            memDG.addGraph(g1, ModelFactory.createDefaultModel().getGraph());
            memDG.addGraph(g2, ModelFactory.createDefaultModel().getGraph());

            memDG.getGraph(g1).add(t1);
            memDG.getGraph(g2).add(t2);

            memDG.getDefaultGraph().add(tDefault);
        });

        // -----------------------
        // FIND (ALL GRAPHS)
        // -----------------------
        List<Quad> sqliteFind = Txn.calculateRead(dataset, () -> {
            List<Quad> list = new ArrayList<>();
            dataset.find(Node.ANY, Node.ANY, Node.ANY, Node.ANY, ctx)
                    .forEachRemaining(list::add);
            return list;
        });

        List<Quad> memFind = Txn.calculateRead(memDG, () -> {
            List<Quad> list = new ArrayList<>();
            memDG.find(Node.ANY, Node.ANY, Node.ANY, Node.ANY)
                    .forEachRemaining(list::add);
            return list;
        });

        assertEquals(memFind.size(), sqliteFind.size(),
                "find() must return same number of quads");

        assertTrue(sqliteFind.containsAll(memFind),
                "SQLite find() must match in-memory find()");

        // -----------------------
        // FIND NG (NAMED GRAPHS ONLY)
        // -----------------------
        List<Quad> sqliteFindNG = Txn.calculateRead(dataset, () -> {
            List<Quad> list = new ArrayList<>();
            dataset.findNG(Node.ANY, Node.ANY, Node.ANY, Node.ANY, ctx)
                    .forEachRemaining(list::add);
            return list;
        });

        List<Quad> memFindNG = Txn.calculateRead(memDG, () -> {
            List<Quad> list = new ArrayList<>();
            memDG.findNG(Node.ANY, Node.ANY, Node.ANY, Node.ANY)
                    .forEachRemaining(list::add);
            return list;
        });

        assertEquals(memFindNG.size(), sqliteFindNG.size(),
                "findNG() must return same number of quads");

        assertTrue(sqliteFindNG.containsAll(memFindNG),
                "SQLite findNG() must match in-memory findNG()");

        // -----------------------
        // EXTRA: ensure default graph excluded from NG
        // -----------------------
        assertEquals(3, sqliteFind.size(), "find() should include default graph");
        assertEquals(2, sqliteFindNG.size(), "findNG() should exclude default graph");

        assertTrue(sqliteFind.stream()
                .anyMatch(q -> Quad.isDefaultGraph(q.getGraph())),
                "find() must include default graph");

        assertTrue(sqliteFindNG.stream()
                .noneMatch(q -> Quad.isDefaultGraph(q.getGraph())),
                "findNG() must NOT include default graph");
    }

    @Test
    void compareDefaultGraphAddViaQuadAPI() throws Exception {

        User user = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });
        
        InvocationContext ctx = new InvocationContext.Builder().fromUser(user).build();

        Node s = NodeFactory.createURI("http://example.org/s");
        Node p = NodeFactory.createURI("http://example.org/p");
        Node o = NodeFactory.createLiteralString("defaultValue");

        Triple triple = Triple.create(s, p, o);

        // -----------------------
        // SQLite dataset
        // -----------------------
        Txn.executeWrite(dataset, () -> {
            dataset.add(Quad.defaultGraphIRI, s, p, o, ctx);
        });

        // -----------------------
        // In-memory dataset
        // -----------------------
        DatasetGraph memDG = DatasetGraphFactory.createTxnMem();

        Txn.executeWrite(memDG, () -> {
            memDG.add(Quad.defaultGraphIRI, s, p, o);
        });

        // -----------------------
        // Read + compare
        // -----------------------
        boolean sqliteContains = Txn.calculateRead(dataset, ()
                -> dataset.getDefaultGraph(ctx).contains(triple, ctx)
        );

        boolean memContains = Txn.calculateRead(memDG, ()
                -> memDG.getDefaultGraph().contains(triple)
        );

        assertTrue(sqliteContains,
                "SQLite default graph must contain triple added via quad API");

        assertTrue(memContains,
                "In-memory default graph must contain triple added via quad API");

        // -----------------------
        // EXTRA: verify via dataset.contains
        // -----------------------
        boolean sqliteDatasetContains = Txn.calculateRead(dataset, ()
                -> dataset.contains(Quad.defaultGraphIRI, s, p, o, ctx)
        );

        boolean memDatasetContains = Txn.calculateRead(memDG, ()
                -> memDG.contains(Quad.defaultGraphIRI, s, p, o)
        );

        assertTrue(sqliteDatasetContains,
                "SQLite dataset must contain quad in default graph");

        assertTrue(memDatasetContains,
                "In-memory dataset must contain quad in default graph");
    }

    @Test
    void compareDefaultGraphGeneratedNodeVsIRIBehavior() throws Exception {

        User user = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });
        
        InvocationContext ctx = new InvocationContext.Builder().fromUser(user).build();

        Node s = NodeFactory.createURI("http://example.org/s");
        Node p = NodeFactory.createURI("http://example.org/p");
        Node oGen = NodeFactory.createLiteralString("genValue");
        Node oIRI = NodeFactory.createLiteralString("iriValue");

        Triple tripleGen = Triple.create(s, p, oGen);
        Triple tripleIRI = Triple.create(s, p, oIRI);

        // -----------------------
        // SQLite: add with defaultGraphNodeGenerated
        // -----------------------
        Txn.executeWrite(dataset, () -> {
            dataset.add(Quad.defaultGraphNodeGenerated, s, p, oGen, ctx);
        });

        // -----------------------
        // SQLite: add with defaultGraphIRI
        // -----------------------
        Txn.executeWrite(dataset, () -> {
            dataset.add(Quad.defaultGraphIRI, s, p, oIRI, ctx);
        });

        // -----------------------
        // In‑memory dataset for comparison
        // -----------------------
        DatasetGraph memDG = DatasetGraphFactory.createTxnMem();

        Txn.executeWrite(memDG, () -> {
            memDG.add(Quad.defaultGraphNodeGenerated, s, p, oGen);
            memDG.add(Quad.defaultGraphIRI, s, p, oIRI);
        });

        // -----------------------
        // Check default graph contents
        // -----------------------
        boolean sqliteHasGen = Txn.calculateRead(dataset, ()
                -> dataset.getDefaultGraph(ctx).contains(tripleGen, ctx)
        );
        boolean memHasGen = Txn.calculateRead(memDG, ()
                -> memDG.getDefaultGraph().contains(tripleGen)
        );

        boolean sqliteHasIRI = Txn.calculateRead(dataset, ()
                -> dataset.getDefaultGraph(ctx).contains(tripleIRI, ctx)
        );
        boolean memHasIRI = Txn.calculateRead(memDG, ()
                -> memDG.getDefaultGraph().contains(tripleIRI)
        );

        assertTrue(sqliteHasGen,
                "SQLite default graph must contain triple added via defaultGraphNodeGenerated");
        assertTrue(memHasGen,
                "In‑memory default graph must contain triple added via defaultGraphNodeGenerated");

        assertTrue(sqliteHasIRI,
                "SQLite default graph must treat defaultGraphIRI as default graph quad");
        assertTrue(memHasIRI,
                "In‑memory default graph must treat defaultGraphIRI as default graph quad");

        // -----------------------
        // dataset.contains for both quads
        // -----------------------
        boolean sqliteContainsGenQuad = Txn.calculateRead(dataset, ()
                -> dataset.contains(Quad.defaultGraphNodeGenerated, s, p, oGen, ctx)
        );
        boolean sqliteContainsIRIQuad = Txn.calculateRead(dataset, ()
                -> dataset.contains(Quad.defaultGraphIRI, s, p, oIRI, ctx)
        );

        boolean memContainsGenQuad = Txn.calculateRead(memDG, ()
                -> memDG.contains(Quad.defaultGraphNodeGenerated, s, p, oGen)
        );
        boolean memContainsIRIQuad = Txn.calculateRead(memDG, ()
                -> memDG.contains(Quad.defaultGraphIRI, s, p, oIRI)
        );

        assertTrue(sqliteContainsGenQuad,
                "SQLite dataset must contain quad when using defaultGraphNodeGenerated");
        assertTrue(memContainsGenQuad,
                "In‑memory dataset must contain quad when using defaultGraphNodeGenerated");

        assertTrue(sqliteContainsIRIQuad,
                "SQLite dataset still contains the quad with defaultGraphIRI");
        assertTrue(memContainsIRIQuad,
                "In‑memory dataset still contains the quad with defaultGraphIRI");
    }
}
