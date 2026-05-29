package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.Permission;
import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.TxnType;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.system.Txn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class RdfStarUnitTest {

    private SqliteAticDatasetGraph dataset;

    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);
    }

    @Test
    void datasetGraphQuotedTripleSubjectAdmin() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:rdfstar");

        Node s = NodeFactory.createURI("urn:s");
        Node p = NodeFactory.createURI("urn:p");
        Node l = NodeFactory.createLiteralString("l");
        Node o = NodeFactory.createURI("urn:o");

        Node outerP = NodeFactory.createURI("urn:meta:p");
        Node outerO = NodeFactory.createLiteralString("meta");

        // create graph
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // CREATE RDF-star triple term
        // -------------------------
        Node quotedL = NodeFactory.createTripleTerm(s, p, l);
        Node quotedO = NodeFactory.createTripleTerm(s, p, o);

        // -------------------------
        // ADD (quoted triple as subject)
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.add(graph, quotedL, outerP, outerO, ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        dataset.begin(TxnType.WRITE);
        try {
            dataset.add(graph, quotedO, outerP, outerO, ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // FIND with Node.ANY
        // -------------------------
        dataset.begin(TxnType.READ);
        try {

            Iterator<Quad> it = dataset.find(graph, Node.ANY, Node.ANY, Node.ANY, ctx);

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(2, results.size());

            for (Quad q : results) {
                // subject must be a triple term
                Node subj = q.getSubject();
                assertTrue(subj.isTripleTerm());

                // extract inner triple
                Triple inner = subj.getTriple();

                assertEquals(s, inner.getSubject());
                assertEquals(p, inner.getPredicate());

                assertTrue(inner.getObject().equals(l) || inner.getObject().equals(o));

                // outer triple correctness
                assertEquals(outerP, q.getPredicate());
                assertEquals(outerO, q.getObject());
            }

        } finally {
            dataset.end();
        }
    }

    @Test
    void datasetGraphQuotedTripleAllCombinationsAdmin() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:rdfstar:all");

        // base nodes
        Node s = NodeFactory.createURI("urn:s");
        Node p = NodeFactory.createURI("urn:p");
        Node o = NodeFactory.createURI("urn:o");

        Node lit = NodeFactory.createLiteralString("lit");

        Node outerP = NodeFactory.createURI("urn:meta:p");

        // create graph
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------------------------
        // Build test nodes
        // -----------------------------------------
        // plain
        List<Node> plainNodes = List.of(
                s,
                o
        );

        // SPO triple terms
        List<Node> spoTerms = List.of(
                NodeFactory.createTripleTerm(s, p, o)
        );

        // SPL triple terms
        List<Node> splTerms = List.of(
                NodeFactory.createTripleTerm(s, p, lit)
        );

        // combine all possible node types
        List<Node> allNodes = new ArrayList<>();
        allNodes.addAll(plainNodes);
        allNodes.addAll(spoTerms);
        allNodes.addAll(splTerms);

        // -----------------------------------------
        // INSERT all combinations
        // -----------------------------------------
        List<Quad> expected = new ArrayList<>();

        dataset.begin(TxnType.WRITE);
        try {

            for (Node subj : allNodes) {
                for (Node obj : allNodes) {

                    dataset.add(graph, subj, outerP, obj, ctx);

                    expected.add(Quad.create(graph, subj, outerP, obj));
                }
            }

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------------------------
        // FIND + VERIFY
        // -----------------------------------------
        dataset.begin(TxnType.READ);
        try {

            //System.out.println("RDFDataMgr.write =====================");
            //ctx.transferContext(dataset.getContext());
            //RDFDataMgr.write(System.out, dataset.getGraph(graph, ctx), Lang.TTL);
            //System.out.flush();

            Iterator<Quad> it = dataset.find(graph, Node.ANY, Node.ANY, Node.ANY, ctx);

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(expected.size(), results.size());

            for (Quad q : results) {

                //System.out.println(q);

                Node subj = q.getSubject();
                Node obj = q.getObject();

                // ---------- SUBJECT ----------
                if (subj.isTripleTerm()) {
                    Triple t = subj.getTriple();

                    assertEquals(s, t.getSubject());
                    assertEquals(p, t.getPredicate());

                    // object must be either URI or literal
                    assertTrue(t.getObject().equals(o) || t.getObject().equals(lit));
                } else {
                    assertTrue(subj.equals(s) || subj.equals(o));
                }

                // ---------- OBJECT ----------
                if (obj.isTripleTerm()) {
                    Triple t = obj.getTriple();

                    assertEquals(s, t.getSubject());
                    assertEquals(p, t.getPredicate());

                    assertTrue(t.getObject().equals(o) || t.getObject().equals(lit));
                } else if (obj.isLiteral()) {
                    // object is literal meta string
                    assertTrue(obj.getLiteralLexicalForm().startsWith("meta:"));
                }

                // ---------- PREDICATE ----------
                assertEquals(outerP, q.getPredicate());
            }

        } finally {
            dataset.end();
        }
    }

    @Test
    void datasetGraphQuotedTripleSubjectUser() throws Exception {

        Txn.executeWrite(dataset, () -> {
            dataset.addUser("Alice", "Doe", "alice.doe@example.com", "alice", InvocationContext.EMPTY);
        });

        User aliceUser = dataset.calculateRead(() -> {
            return dataset.getUser("alice", InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(aliceUser).build();

        Node graph = NodeFactory.createURI("urn:test:rdfstar");

        Node s = NodeFactory.createURI("urn:s");
        Node p = NodeFactory.createURI("urn:p");
        Node l = NodeFactory.createLiteralString("l");
        Node o = NodeFactory.createURI("urn:o");

        Node outerP = NodeFactory.createURI("urn:meta:p");
        Node outerO = NodeFactory.createLiteralString("meta");

        // create graph
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // CREATE RDF-star triple term
        // -------------------------
        Node quotedL = NodeFactory.createTripleTerm(s, p, l);
        Node quotedO = NodeFactory.createTripleTerm(s, p, o);

        // -------------------------
        // ADD (quoted triple as subject)
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.add(graph, quotedL, outerP, outerO, ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        dataset.begin(TxnType.WRITE);
        try {
            dataset.add(graph, quotedO, outerP, outerO, ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // FIND with Node.ANY
        // -------------------------
        dataset.begin(TxnType.READ);
        try {

            Iterator<Quad> it = dataset.find(graph, Node.ANY, Node.ANY, Node.ANY, ctx);

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(2, results.size());

            for (Quad q : results) {
                // subject must be a triple term
                Node subj = q.getSubject();
                assertTrue(subj.isTripleTerm());

                // extract inner triple
                Triple inner = subj.getTriple();

                assertEquals(s, inner.getSubject());
                assertEquals(p, inner.getPredicate());

                assertTrue(inner.getObject().equals(l) || inner.getObject().equals(o));

                // outer triple correctness
                assertEquals(outerP, q.getPredicate());
                assertEquals(outerO, q.getObject());
            }

        } finally {
            dataset.end();
        }
    }

    @Test
    void datasetGraphQuotedTriplePermissionsPropagation() throws Exception {

        // -------------------------
        // CREATE USERS
        // -------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.addUser("Alice", "Doe", "alice.doe@example.com", "alice", InvocationContext.EMPTY);
            dataset.addUser("Bob", "Doe", "bob.doe@example.com", "bob", InvocationContext.EMPTY);
        });

        User alice = dataset.calculateRead(()
                -> dataset.getUser("alice", InvocationContext.EMPTY)
        );

        User bob = dataset.calculateRead(()
                -> dataset.getUser("bob", InvocationContext.EMPTY)
        );

        InvocationContext aliceCtx = new InvocationContext.Builder().fromUser(alice).build();
        InvocationContext bobCtx = new InvocationContext.Builder().fromUser(bob).build();

        Node graph = NodeFactory.createURI("urn:test:rdfstar:perm");

        Node device = NodeFactory.createURI("urn:device:1");
        Node measured = NodeFactory.createURI("urn:measured");
        Node value = NodeFactory.createLiteralString("0.3");

        Node unitP = NodeFactory.createURI("urn:unit");
        Node unitO = NodeFactory.createLiteralString("cm");

        // -------------------------
        // CREATE GRAPH
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), aliceCtx);
            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // ALICE ADDS DATA
        // -------------------------
        Node quoted = NodeFactory.createTripleTerm(device, measured, value);

        dataset.begin(TxnType.WRITE);
        try {
            // base triple
            dataset.add(graph, device, measured, value, aliceCtx);

            // quoted triple metadata
            dataset.add(graph, quoted, unitP, unitO, aliceCtx);

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // BOB SHOULD SEE NOTHING
        // -------------------------
        dataset.begin(TxnType.READ);
        try {
            List<Quad> results = new ArrayList<>();
            dataset.find(graph, Node.ANY, Node.ANY, Node.ANY, bobCtx)
                    .forEachRemaining(results::add);

            assertTrue(results.isEmpty(), "Bob must not see anything before sharing");

        } finally {
            dataset.end();
        }

        // -------------------------
        // ALICE SHARES DEVICE WITH BOB
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareResources(
                    Set.of(device.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // BOB SHOULD NOW SEE BOTH TRIPLES
        // -------------------------
        dataset.begin(TxnType.READ);
        try {
            List<Quad> results = new ArrayList<>();
            dataset.find(graph, Node.ANY, Node.ANY, Node.ANY, bobCtx)
                    .forEachRemaining(results::add);

            assertEquals(2, results.size(), "Bob should see base + quoted triple");

            boolean sawBase = false;
            boolean sawQuoted = false;

            for (Quad q : results) {

                if (q.getSubject().equals(device)) {
                    // base triple
                    assertEquals(measured, q.getPredicate());
                    assertEquals(value, q.getObject());
                    sawBase = true;
                }

                if (q.getSubject().isTripleTerm()) {
                    Triple inner = q.getSubject().getTriple();

                    assertEquals(device, inner.getSubject());
                    assertEquals(measured, inner.getPredicate());
                    assertEquals(value, inner.getObject());

                    assertEquals(unitP, q.getPredicate());
                    assertEquals(unitO, q.getObject());

                    sawQuoted = true;
                }
            }

            assertTrue(sawBase, "Bob must see base triple");
            assertTrue(sawQuoted, "Bob must see quoted triple");

        } finally {
            dataset.end();
        }
    }

    @Test
    void datasetGraphQuotedTripleRequiresAllInnerResources() throws Exception {

        // -------------------------
        // CREATE USERS
        // -------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.addUser("Alice", "Doe", "alice.doe@example.com", "alice", InvocationContext.EMPTY);
            dataset.addUser("Bob", "Doe", "bob.doe@example.com", "bob", InvocationContext.EMPTY);
        });

        User alice = dataset.calculateRead(()
                -> dataset.getUser("alice", InvocationContext.EMPTY)
        );

        User bob = dataset.calculateRead(()
                -> dataset.getUser("bob", InvocationContext.EMPTY)
        );

        InvocationContext aliceCtx = new InvocationContext.Builder().fromUser(alice).build();
        InvocationContext bobCtx = new InvocationContext.Builder().fromUser(bob).build();

        Node graph = NodeFactory.createURI("urn:test:rdfstar:perm:ice");

        Node ice = NodeFactory.createURI("urn:ice");
        Node is = NodeFactory.createURI("urn:is");
        Node cold = NodeFactory.createURI("urn:cold");

        Node confP = NodeFactory.createURI("urn:confidence");
        Node confO = NodeFactory.createLiteralString("0.8");

        // -------------------------
        // CREATE GRAPH + SHARE GRAPH
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), aliceCtx);

            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // ALICE ADDS DATA
        // -------------------------
        Node quoted = NodeFactory.createTripleTerm(ice, is, cold);

        dataset.begin(TxnType.WRITE);
        try {
            dataset.add(graph, ice, is, cold, aliceCtx);
            dataset.add(graph, quoted, confP, confO, aliceCtx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // BOB SEES NOTHING
        // -------------------------
        dataset.begin(TxnType.READ);
        try {
            List<Quad> results = new ArrayList<>();
            dataset.find(graph, Node.ANY, Node.ANY, Node.ANY, bobCtx)
                    .forEachRemaining(results::add);

            assertTrue(results.isEmpty());

        } finally {
            dataset.end();
        }

        // -------------------------
        // SHARE ONLY :ice
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareResources(
                    Set.of(ice.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // STILL NOTHING (missing :cold)
        // -------------------------
        dataset.begin(TxnType.READ);
        try {
            List<Quad> results = new ArrayList<>();
            dataset.find(graph, Node.ANY, Node.ANY, Node.ANY, bobCtx)
                    .forEachRemaining(results::add);

            assertTrue(results.isEmpty(),
                    "Bob must NOT see anything because :cold is missing");

        } finally {
            dataset.end();
        }

        // -------------------------
        // SHARE :cold
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareResources(
                    Set.of(cold.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // NOW BOB SEES BOTH
        // -------------------------
        dataset.begin(TxnType.READ);
        try {
            List<Quad> results = new ArrayList<>();
            dataset.find(graph, Node.ANY, Node.ANY, Node.ANY, bobCtx)
                    .forEachRemaining(results::add);

            assertEquals(2, results.size());

            boolean sawBase = false;
            boolean sawQuoted = false;

            for (Quad q : results) {

                if (q.getSubject().equals(ice)) {
                    assertEquals(is, q.getPredicate());
                    assertEquals(cold, q.getObject());
                    sawBase = true;
                }

                if (q.getSubject().isTripleTerm()) {
                    Triple inner = q.getSubject().getTriple();

                    assertEquals(ice, inner.getSubject());
                    assertEquals(is, inner.getPredicate());
                    assertEquals(cold, inner.getObject());

                    assertEquals(confP, q.getPredicate());
                    assertEquals(confO, q.getObject());

                    sawQuoted = true;
                }
            }

            assertTrue(sawBase, "Bob must see base triple");
            assertTrue(sawQuoted, "Bob must see quoted triple");

        } finally {
            dataset.end();
        }
    }

    @Test
    void datasetGraphQuotedTripleObjectPermissionsPropagation() throws Exception {

        // -------------------------
        // CREATE USERS
        // -------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.addUser("Alice", "Doe", "alice.doe@example.com", "alice", InvocationContext.EMPTY);
            dataset.addUser("Bob", "Doe", "bob.doe@example.com", "bob", InvocationContext.EMPTY);
        });

        User alice = dataset.calculateRead(()
                -> dataset.getUser("alice", InvocationContext.EMPTY)
        );

        User bob = dataset.calculateRead(()
                -> dataset.getUser("bob", InvocationContext.EMPTY)
        );

        InvocationContext aliceCtx = new InvocationContext.Builder().fromUser(alice).build();
        InvocationContext bobCtx = new InvocationContext.Builder().fromUser(bob).build();

        Node graph = NodeFactory.createURI("urn:test:rdfstar:perm:object");

        Node aliceNode = NodeFactory.createURI("urn:alice");
        Node said = NodeFactory.createURI("urn:said");

        Node wall = NodeFactory.createURI("urn:wall");
        Node is = NodeFactory.createURI("urn:is");
        Node black = NodeFactory.createURI("urn:black");

        // -------------------------
        // CREATE GRAPH + SHARE GRAPH
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), aliceCtx);

            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // CREATE RDF-star triple term
        // -------------------------
        Node quoted = NodeFactory.createTripleTerm(wall, is, black);

        // -------------------------
        // ALICE ADDS DATA
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.add(graph, aliceNode, said, quoted, aliceCtx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // BOB SEES NOTHING
        // -------------------------
        dataset.begin(TxnType.READ);
        try {
            List<Quad> results = new ArrayList<>();
            dataset.find(graph, Node.ANY, Node.ANY, Node.ANY, bobCtx)
                    .forEachRemaining(results::add);

            assertTrue(results.isEmpty());

        } finally {
            dataset.end();
        }

        // -------------------------
        // SHARE ONLY INNER SUBJECT (:wall)
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareResources(
                    Set.of(wall.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // STILL NOTHING (missing :black and :alice)
        // -------------------------
        dataset.begin(TxnType.READ);
        try {
            List<Quad> results = new ArrayList<>();
            dataset.find(graph, Node.ANY, Node.ANY, Node.ANY, bobCtx)
                    .forEachRemaining(results::add);

            assertTrue(results.isEmpty(),
                    "Bob must NOT see anything (missing :black and :alice)");

        } finally {
            dataset.end();
        }

        // -------------------------
        // SHARE INNER OBJECT (:black)
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareResources(
                    Set.of(black.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // STILL NOTHING (missing outer subject :alice)
        // -------------------------
        dataset.begin(TxnType.READ);
        try {
            List<Quad> results = new ArrayList<>();
            dataset.find(graph, Node.ANY, Node.ANY, Node.ANY, bobCtx)
                    .forEachRemaining(results::add);

            assertTrue(results.isEmpty(),
                    "Bob must NOT see anything (missing :alice)");

        } finally {
            dataset.end();
        }

        // -------------------------
        // SHARE OUTER SUBJECT (:alice)
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareResources(
                    Set.of(aliceNode.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // NOW BOB SEES THE TRIPLE
        // -------------------------
        dataset.begin(TxnType.READ);
        try {
            List<Quad> results = new ArrayList<>();
            dataset.find(graph, Node.ANY, Node.ANY, Node.ANY, bobCtx)
                    .forEachRemaining(results::add);

            assertEquals(1, results.size());

            Quad q = results.get(0);

            // outer triple
            assertEquals(aliceNode, q.getSubject());
            assertEquals(said, q.getPredicate());

            // object must be triple term
            Node obj = q.getObject();
            assertTrue(obj.isTripleTerm());

            Triple inner = obj.getTriple();

            assertEquals(wall, inner.getSubject());
            assertEquals(is, inner.getPredicate());
            assertEquals(black, inner.getObject());

        } finally {
            dataset.end();
        }
    }

    @Test
    void datasetGraphFindOnlyTripleSubjects() throws Exception {

        // -------------------------
        // CREATE USERS
        // -------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.addUser("Alice", "Doe", "alice.doe@example.com", "alice", InvocationContext.EMPTY);
            dataset.addUser("Bob", "Doe", "bob.doe@example.com", "bob", InvocationContext.EMPTY);
        });

        User alice = dataset.calculateRead(()
                -> dataset.getUser("alice", InvocationContext.EMPTY)
        );

        User bob = dataset.calculateRead(()
                -> dataset.getUser("bob", InvocationContext.EMPTY)
        );

        InvocationContext aliceCtx = new InvocationContext.Builder().fromUser(alice).build();
        InvocationContext bobCtx = new InvocationContext.Builder().fromUser(bob).build();

        Node graph = NodeFactory.createURI("urn:test:rdfstar:find:triple-subject");

        Node ice = NodeFactory.createURI("urn:ice");
        Node is = NodeFactory.createURI("urn:is");
        Node cold = NodeFactory.createURI("urn:cold");

        Node confidence = NodeFactory.createURI("urn:confidence");
        Node confVal = NodeFactory.createLiteralString("0.8");

        Node quoted = NodeFactory.createTripleTerm(ice, is, cold);

        SqliteAticGraph.setDefaultBufferSize(1);
        SqliteAticGraph.setDefaultBatchSize(1);
        
        // -------------------------
        // CREATE GRAPH + DATA + SHARE EVERYTHING
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), aliceCtx);

            dataset.add(graph, ice, is, cold, aliceCtx);
            dataset.add(graph, quoted, confidence, confVal, aliceCtx);

            // share graph + all involved resources
            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );

            dataset.shareResources(
                    Set.of(ice.getURI(), cold.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // BOB: query only triple-term subjects
        // -------------------------
        Node triplePattern = NodeFactory.createTripleTerm(Node.ANY, Node.ANY, Node.ANY);

        dataset.begin(TxnType.READ);
        try {
            List<Quad> results = new ArrayList<>();
            dataset.find(graph, triplePattern, Node.ANY, Node.ANY, bobCtx)
                    .forEachRemaining(results::add);

            assertEquals(1, results.size(), "Only quoted triple should match");

            Quad q = results.get(0);

            assertTrue(q.getSubject().isTripleTerm());

            Triple inner = q.getSubject().getTriple();

            assertEquals(ice, inner.getSubject());
            assertEquals(is, inner.getPredicate());
            assertEquals(cold, inner.getObject());

            assertEquals(confidence, q.getPredicate());
            assertEquals(confVal, q.getObject());

        } finally {
            dataset.end();
        }
    }

    @Test
    void datasetGraphFindTripleSubjectPatternFiltering() throws Exception {

        // -------------------------
        // CREATE USERS
        // -------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.addUser("Alice", "Doe", "alice.doe@example.com", "alice", InvocationContext.EMPTY);
            dataset.addUser("Bob", "Doe", "bob.doe@example.com", "bob", InvocationContext.EMPTY);
        });

        User alice = dataset.calculateRead(()
                -> dataset.getUser("alice", InvocationContext.EMPTY)
        );

        User bob = dataset.calculateRead(()
                -> dataset.getUser("bob", InvocationContext.EMPTY)
        );

        InvocationContext aliceCtx = new InvocationContext.Builder().fromUser(alice).build();
        InvocationContext bobCtx = new InvocationContext.Builder().fromUser(bob).build();

        Node graph = NodeFactory.createURI("urn:test:rdfstar:find:pattern");

        Node ice = NodeFactory.createURI("urn:ice");
        Node is = NodeFactory.createURI("urn:is");
        Node cold = NodeFactory.createURI("urn:cold");

        Node confidence = NodeFactory.createURI("urn:confidence");
        Node confVal = NodeFactory.createLiteralString("0.8");

        Node quoted = NodeFactory.createTripleTerm(ice, is, cold);
        
        SqliteAticGraph.setDefaultBufferSize(1);
        SqliteAticGraph.setDefaultBatchSize(1);

        // -------------------------
        // CREATE GRAPH + DATA + SHARE EVERYTHING
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), aliceCtx);

            dataset.add(graph, ice, is, cold, aliceCtx);
            dataset.add(graph, quoted, confidence, confVal, aliceCtx);
            
            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );

            dataset.shareResources(
                    Set.of(ice.getURI(), cold.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // QUERY 1: << :something ANY ANY >> → nothing
        // -------------------------
        dataset.begin(TxnType.READ);
        try {
            Node pattern1 = NodeFactory.createTripleTerm(
                    NodeFactory.createURI("urn:something"),
                    Node.ANY,
                    Node.ANY
            );

            List<Quad> results = new ArrayList<>();
            dataset.find(graph, pattern1, Node.ANY, Node.ANY, bobCtx)
                    .forEachRemaining(results::add);

            assertTrue(results.isEmpty());

        } finally {
            dataset.end();
        }

        // -------------------------
        // QUERY 2: << :ice ANY ANY >> → match
        // -------------------------
        dataset.begin(TxnType.READ);
        try {
            Node pattern2 = NodeFactory.createTripleTerm(
                    ice,
                    Node.ANY,
                    Node.ANY
            );

            List<Quad> results = new ArrayList<>();
            dataset.find(graph, pattern2, Node.ANY, Node.ANY, bobCtx)
                    .forEachRemaining(results::add);

            assertEquals(1, results.size());

            Quad q = results.get(0);

            assertTrue(q.getSubject().isTripleTerm());

            Triple inner = q.getSubject().getTriple();

            assertEquals(ice, inner.getSubject());
            assertEquals(is, inner.getPredicate());
            assertEquals(cold, inner.getObject());

        } finally {
            dataset.end();
        }
    }

    @Test
    void datasetGraphQuotedTripleVariableStabilityForBob() throws Exception {

        // -------------------------
        // USERS
        // -------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.addUser("Alice", "Doe", "alice@example.com", "alice", InvocationContext.EMPTY);
            dataset.addUser("Bob", "Doe", "bob@example.com", "bob", InvocationContext.EMPTY);
        });

        User alice = dataset.calculateRead(()
                -> dataset.getUser("alice", InvocationContext.EMPTY));

        User bob = dataset.calculateRead(()
                -> dataset.getUser("bob", InvocationContext.EMPTY));

        InvocationContext aliceCtx = new InvocationContext.Builder().fromUser(alice).build();
        InvocationContext bobCtx = new InvocationContext.Builder().fromUser(bob).build();

        Node graph = NodeFactory.createURI("urn:test:rdfstar:stable");

        Node aliceNode = NodeFactory.createURI("urn:alice");
        Node said = NodeFactory.createURI("urn:said");

        Node ice = NodeFactory.createURI("urn:ice");
        Node melts = NodeFactory.createURI("urn:melts");
        Node water = NodeFactory.createURI("urn:water");

        Node quoted = NodeFactory.createTripleTerm(ice, melts, water);

        SqliteAticGraph.setDefaultBufferSize(1);
        SqliteAticGraph.setDefaultBatchSize(1);
        
        // -------------------------
        // SHARE + INSERT
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), aliceCtx);

            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );

            dataset.add(graph, quoted, said, aliceNode, aliceCtx);

            dataset.shareResources(
                    Set.of(aliceNode.getURI(), ice.getURI(), water.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // BOB TESTS
        // -------------------------
        dataset.begin(TxnType.READ);
        try {

            List<Quad> r1 = new ArrayList<>();
            dataset.find(
                    graph,
                    NodeFactory.createTripleTerm(Node.ANY, Node.ANY, Node.ANY),
                    Node.ANY,
                    Node.ANY,
                    bobCtx
            ).forEachRemaining(r1::add);

            List<Quad> r2 = new ArrayList<>();
            dataset.find(
                    graph,
                    NodeFactory.createTripleTerm(ice, Node.ANY, Node.ANY),
                    Node.ANY,
                    Node.ANY,
                    bobCtx
            ).forEachRemaining(r2::add);

            List<Quad> r3 = new ArrayList<>();
            dataset.find(
                    graph,
                    NodeFactory.createTripleTerm(Node.ANY, melts, Node.ANY),
                    Node.ANY,
                    Node.ANY,
                    bobCtx
            ).forEachRemaining(r3::add);

            List<Quad> r4 = new ArrayList<>();
            dataset.find(
                    graph,
                    NodeFactory.createTripleTerm(Node.ANY, Node.ANY, water),
                    Node.ANY,
                    Node.ANY,
                    bobCtx
            ).forEachRemaining(r4::add);

            List<Quad> r5 = new ArrayList<>();
            dataset.find(
                    graph,
                    NodeFactory.createTripleTerm(Node.ANY, Node.ANY, Node.ANY),
                    said,
                    Node.ANY,
                    bobCtx
            ).forEachRemaining(r5::add);

            List<Quad> r6 = new ArrayList<>();
            dataset.find(
                    graph,
                    NodeFactory.createTripleTerm(Node.ANY, Node.ANY, Node.ANY),
                    Node.ANY,
                    aliceNode,
                    bobCtx
            ).forEachRemaining(r6::add);

            List<Quad> r7 = new ArrayList<>();
            dataset.find(
                    graph,
                    NodeFactory.createTripleTerm(Node.ANY, Node.ANY, Node.ANY),
                    said,
                    aliceNode,
                    bobCtx
            ).forEachRemaining(r7::add);

            // -------------------------
            // ASSERTIONS
            // -------------------------
            List<List<Quad>> all = List.of(r1, r2, r3, r4, r5, r6, r7);

            int index = 1;
            for (List<Quad> r : all) {
                assertEquals(1, r.size(), "index " + index);
                index++;

                Quad q = r.get(0);

                // IMPORTANT: graph must always match
                assertEquals(graph, q.getGraph());

                assertEquals(quoted, q.getSubject());
                assertEquals(said, q.getPredicate());
                assertEquals(aliceNode, q.getObject());
            }

        } finally {
            dataset.end();
        }
    }

    @Test
    void datasetGraphTripleSubjectGraphApiFindContainsSizeAndRemove() throws Exception {

        // -------------------------
        // USERS
        // -------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.addUser("Alice", "Doe", "alice@example.com", "alice", InvocationContext.EMPTY);
            dataset.addUser("Bob", "Doe", "bob@example.com", "bob", InvocationContext.EMPTY);
        });

        User alice = dataset.calculateRead(()
                -> dataset.getUser("alice", InvocationContext.EMPTY));

        User bob = dataset.calculateRead(()
                -> dataset.getUser("bob", InvocationContext.EMPTY));

        InvocationContext aliceCtx = new InvocationContext.Builder().fromUser(alice).build();
        InvocationContext bobCtx = new InvocationContext.Builder().fromUser(bob).build();

        Node graphNode = NodeFactory.createURI("urn:test:rdfstar:graph-api");

        Node ice = NodeFactory.createURI("urn:ice");
        Node is = NodeFactory.createURI("urn:is");
        Node cold = NodeFactory.createURI("urn:cold");

        Node confidence = NodeFactory.createURI("urn:confidence");
        Node confVal = NodeFactory.createLiteralString("0.8");

        Node quoted = NodeFactory.createTripleTerm(ice, is, cold);
        
        SqliteAticGraph.setDefaultBufferSize(1);
        SqliteAticGraph.setDefaultBatchSize(1);

        // -------------------------
        // SETUP DATA
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graphNode, GraphFactory.createDefaultGraph(), aliceCtx);

            dataset.add(graphNode, ice, is, cold, aliceCtx);
            dataset.add(graphNode, quoted, confidence, confVal, aliceCtx);

            dataset.shareGraphs(
                    Set.of(graphNode.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );

            dataset.shareResources(
                    Set.of(ice.getURI(), cold.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // GET GRAPH
        // -------------------------
        AticGraph graph = dataset.calculateRead(() -> {
            return dataset.getGraph(graphNode, bobCtx);
        });

        // -------------------------
        // VERIFY INITIAL STATE
        // -------------------------
        dataset.begin(TxnType.READ);
        try {

            // size
            long size = graph.size(bobCtx);
            assertEquals(2, size);

            // contains
            assertTrue(graph.contains(ice, is, cold, bobCtx));
            assertTrue(graph.contains(quoted, confidence, confVal, bobCtx));

            // find triple-term subjects
            List<Triple> tripleSubjects = new ArrayList<>();
            graph.find(
                    NodeFactory.createTripleTerm(Node.ANY, Node.ANY, Node.ANY),
                    Node.ANY,
                    Node.ANY,
                    bobCtx
            ).forEachRemaining(tripleSubjects::add);

            assertEquals(1, tripleSubjects.size());

            Triple t = tripleSubjects.get(0);
            assertEquals(quoted, t.getSubject());
            assertEquals(confidence, t.getPredicate());
            assertEquals(confVal, t.getObject());

        } finally {
            dataset.end();
        }

        // -------------------------
        // REMOVE VIA GRAPH API
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {
            AticGraph graphWrite = dataset.getGraph(graphNode, aliceCtx);

            graphWrite.remove(quoted, confidence, confVal, aliceCtx);

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // VERIFY AFTER REMOVAL
        // -------------------------
        dataset.begin(TxnType.READ);
        try {

            AticGraph graphRead = dataset.getGraph(graphNode, bobCtx);

            long size = graphRead.size(bobCtx);
            assertEquals(1, size);

            assertFalse(graphRead.contains(quoted, confidence, confVal, bobCtx));
            assertTrue(graphRead.contains(ice, is, cold, bobCtx));

            List<Triple> tripleSubjects = new ArrayList<>();
            graphRead.find(
                    NodeFactory.createTripleTerm(Node.ANY, Node.ANY, Node.ANY),
                    Node.ANY,
                    Node.ANY,
                    bobCtx
            ).forEachRemaining(tripleSubjects::add);

            assertTrue(tripleSubjects.isEmpty());

        } finally {
            dataset.end();
        }
    }

    @Test
    void datasetGraphQuotedTripleSubjectFilteringGraphApi() throws Exception {

        // -------------------------
        // USERS
        // -------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.addUser("Alice", "Doe", "alice@example.com", "alice", InvocationContext.EMPTY);
            dataset.addUser("Bob", "Doe", "bob@example.com", "bob", InvocationContext.EMPTY);
        });

        User alice = dataset.calculateRead(()
                -> dataset.getUser("alice", InvocationContext.EMPTY));

        User bob = dataset.calculateRead(()
                -> dataset.getUser("bob", InvocationContext.EMPTY));

        InvocationContext aliceCtx = new InvocationContext.Builder().fromUser(alice).build();
        InvocationContext bobCtx = new InvocationContext.Builder().fromUser(bob).build();

        Node graphNode = NodeFactory.createURI("urn:test:rdfstar:filter");

        // -------------------------
        // DATA
        // -------------------------
        Node s1 = NodeFactory.createURI("urn:s1");
        Node s2 = NodeFactory.createURI("urn:s2");

        Node p1 = NodeFactory.createURI("urn:p1");
        Node p2 = NodeFactory.createURI("urn:p2");

        Node o1 = NodeFactory.createURI("urn:o1");
        Node o2 = NodeFactory.createURI("urn:o2");

        Node metaP = NodeFactory.createURI("urn:meta");
        Node metaO1 = NodeFactory.createLiteralString("A");
        Node metaO2 = NodeFactory.createLiteralString("B");

        Node quoted1 = NodeFactory.createTripleTerm(s1, p1, o1);
        Node quoted2 = NodeFactory.createTripleTerm(s2, p2, o2);

        SqliteAticGraph.setDefaultBufferSize(1);
        SqliteAticGraph.setDefaultBatchSize(1);
        
        // -------------------------
        // SETUP
        // -------------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graphNode, GraphFactory.createDefaultGraph(), aliceCtx);

            // base triples (needed for ACL propagation)
            dataset.add(graphNode, s1, p1, o1, aliceCtx);
            dataset.add(graphNode, s2, p2, o2, aliceCtx);

            // quoted triples
            dataset.add(graphNode, quoted1, metaP, metaO1, aliceCtx);
            dataset.add(graphNode, quoted2, metaP, metaO2, aliceCtx);
            
            // share everything relevant
            dataset.shareGraphs(
                    Set.of(graphNode.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );

            dataset.shareResources(
                    Set.of(
                            s1.getURI(), s2.getURI(),
                            o1.getURI(), o2.getURI()
                    ),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -------------------------
        // BOB GRAPH
        // -------------------------
        AticGraph graph = dataset.calculateRead(()
                -> dataset.getGraph(graphNode, bobCtx)
        );

        Node pattern = NodeFactory.createTripleTerm(s1, Node.ANY, Node.ANY);

        // -------------------------
        // VERIFY USING FIND
        // -------------------------
        dataset.begin(TxnType.READ);
        try {

            List<Triple> results = new ArrayList<>();
            graph.find(pattern, Node.ANY, Node.ANY, bobCtx)
                    .forEachRemaining(results::add);

            assertEquals(1, results.size(), "Only triples with <<s1 ?p ?o>> should match");

            Triple t = results.get(0);

            assertEquals(quoted1, t.getSubject());
            assertEquals(metaP, t.getPredicate());
            assertEquals(metaO1, t.getObject());

        } finally {
            dataset.end();
        }

        // -------------------------
        // VERIFY USING CONTAINS
        // -------------------------
        dataset.begin(TxnType.READ);
        try {

            // must exist
            assertTrue(graph.contains(quoted1, metaP, metaO1, bobCtx));

            // must NOT match filtered pattern result
            // (even though it exists globally)
            assertTrue(graph.contains(quoted2, metaP, metaO2, bobCtx));

            // but via pattern <<s1 ? ?>> it must not be returned
            List<Triple> filtered = new ArrayList<>();
            graph.find(pattern, Node.ANY, Node.ANY, bobCtx)
                    .forEachRemaining(filtered::add);

            assertFalse(filtered.stream().anyMatch(t -> t.getSubject().equals(quoted2)));

        } finally {
            dataset.end();
        }

        // -------------------------
        // VERIFY SIZE BEHAVIOR
        // -------------------------
        dataset.begin(TxnType.READ);
        try {

            // total size = 4 triples:
            // 2 base + 2 quoted
            assertEquals(4, graph.size(bobCtx));

            // filtered size via find = 1
            long filteredSize = graph.find(pattern, Node.ANY, Node.ANY, bobCtx)
                    .toList()
                    .size();

            assertEquals(1, filteredSize);

        } finally {
            dataset.end();
        }
    }
    
}
