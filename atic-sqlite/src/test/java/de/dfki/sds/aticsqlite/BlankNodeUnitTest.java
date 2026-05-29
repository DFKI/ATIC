package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.TxnType;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.graph.GraphFactory;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class BlankNodeUnitTest {

    private SqliteAticDatasetGraph dataset;

    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);
    }

    //======================================================================================
    //jena related tests
    @Test
    public void testJenaBlankNodeCanonicalization() {

        Graph g = GraphFactory.createDefaultGraph();

        Node s = NodeFactory.createURI("http://example/s");
        Node hasAddress = NodeFactory.createURI("http://example/hasAddress");
        Node street = NodeFactory.createURI("http://example/street");
        Node plz = NodeFactory.createURI("http://example/plz");

        // blank node 1
        Node addr1 = NodeFactory.createBlankNode();
        g.add(Triple.create(s, hasAddress, addr1));
        g.add(Triple.create(addr1, street, NodeFactory.createLiteralString("bla str.")));

        // blank node 2
        Node addr2 = NodeFactory.createBlankNode();
        g.add(Triple.create(s, hasAddress, addr2));
        g.add(Triple.create(addr2, plz, NodeFactory.createLiteralString("1234")));

        // Check they are different blank nodes
        assertNotEquals(addr1, addr2);

        // Count address relations
        int count = g.find(s, hasAddress, Node.ANY).toList().size();

        // If canonicalization happened we would get 1
        // Jena actually returns 2
        assertEquals(2, count);
    }

    @Test
    public void testJenaGraphIsomorphism() {

        Graph g1 = GraphFactory.createDefaultGraph();
        Graph g2 = GraphFactory.createDefaultGraph();

        Node s = NodeFactory.createURI("http://example/s");
        Node p = NodeFactory.createURI("http://example/p");

        Node b1 = NodeFactory.createBlankNode();
        Node b2 = NodeFactory.createBlankNode();

        g1.add(Triple.create(s, p, b1));
        g1.add(Triple.create(b1, p, NodeFactory.createLiteralString("x")));

        g2.add(Triple.create(s, p, b2));
        g2.add(Triple.create(b2, p, NodeFactory.createLiteralString("x")));

        // Graphs are structurally identical even though blank node IDs differ
        assertTrue(g1.isIsomorphicWith(g2));
    }

    @Test
    public void testJenaMergeIdenticalBlankNodes() {

        Graph g = GraphFactory.createDefaultGraph();

        Node s = NodeFactory.createURI("http://ex/s");
        Node hasAddress = NodeFactory.createURI("http://ex/hasAddress");
        Node street = NodeFactory.createURI("http://ex/street");

        Node streetValue = NodeFactory.createLiteralString("bla str.");

        // blank node 1
        Node b1 = NodeFactory.createBlankNode();
        g.add(Triple.create(s, hasAddress, b1));
        g.add(Triple.create(b1, street, streetValue));

        // blank node 2 (identical predicate-object structure)
        Node b2 = NodeFactory.createBlankNode();
        g.add(Triple.create(s, hasAddress, b2));
        g.add(Triple.create(b2, street, streetValue));

        // collect blank nodes that are objects of (s hasAddress ?x)
        Set<Node> addresses = new HashSet<>();
        g.find(s, hasAddress, Node.ANY).forEachRemaining(t -> {
            addresses.add(t.getObject());
        });

        /*
         EXPECTED IF CANONICALIZATION EXISTS:
             addresses.size() == 1

         ACTUAL JENA BEHAVIOR:
             addresses.size() == 2
         */
        assertNotEquals(
                1,
                addresses.size(),
                "Identical blank nodes should be merged if canonicalization is implemented"
        );
    }

    @Test
    public void testJenaSingleBlankNodeFromBracketSyntax() {

        String ttl = """
            @prefix : <http://ex/> .

            :s :hasAddress [
                :street "bla str." ;
                :plz 1234
            ] .
        """;

        Graph g = GraphFactory.createDefaultGraph();
        RDFDataMgr.read(g, new StringReader(ttl), null, org.apache.jena.riot.Lang.TURTLE);

        Node s = NodeFactory.createURI("http://ex/s");
        Node hasAddress = NodeFactory.createURI("http://ex/hasAddress");

        // collect blank nodes used as address
        Set<Node> addresses = new HashSet<>();
        g.find(s, hasAddress, Node.ANY).forEachRemaining(t
                -> addresses.add(t.getObject())
        );

        // Should be exactly ONE blank node
        assertEquals(1, addresses.size());

        Node address = addresses.iterator().next();

        // Count properties of that blank node
        int propertyCount = g.find(address, Node.ANY, Node.ANY).toList().size();

        // street + plz
        assertEquals(2, propertyCount);
    }

    @Test
    public void testJenaParsingSameTTLtwiceCreatesTwoBlankNodes() {

        String ttl = """
            @prefix : <http://ex/> .

            :s :hasAddress [
                :street "bla str." ;
                :plz 1234
            ] .
        """;

        Graph g = GraphFactory.createDefaultGraph();

        // parse the same document twice
        RDFDataMgr.read(g, new StringReader(ttl), null, Lang.TURTLE);
        RDFDataMgr.read(g, new StringReader(ttl), null, Lang.TURTLE);

        Node s = NodeFactory.createURI("http://ex/s");
        Node hasAddress = NodeFactory.createURI("http://ex/hasAddress");

        // collect addresses
        Set<Node> addresses = new HashSet<>();
        g.find(s, hasAddress, Node.ANY).forEachRemaining(t
                -> addresses.add(t.getObject())
        );

        // After parsing twice we expect TWO blank nodes
        assertEquals(2, addresses.size());

        // verify each blank node has the same structure
        Node street = NodeFactory.createURI("http://ex/street");
        Node plz = NodeFactory.createURI("http://ex/plz");

        for (Node addr : addresses) {
            assertTrue(g.contains(addr, street, NodeFactory.createLiteralString("bla str.")));
            assertTrue(g.contains(addr, plz, NodeFactory.createLiteralByValue(1234, XSDDatatype.XSDinteger)));
        }
    }

    @Test
    void testJenaAddAndFindBlankNodeTripleJenaTxnMem() throws Exception {
        DatasetGraph datasetJena = DatasetGraphFactory.createTxnMem();

        Node graph = NodeFactory.createURI("urn:test:blankGraph");

        // create blank node for subject
        Node blankSubject = NodeFactory.createBlankNode();

        Node p = NodeFactory.createURI("urn:test:p");
        Node o = NodeFactory.createURI("urn:test:o");

        Triple blankTriple = Triple.create(blankSubject, p, o);

        // -----------------------
        // Set up: create named graph
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            datasetJena.addGraph(graph, GraphFactory.createDefaultGraph());
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Add blank node triple
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            datasetJena.add(graph, blankSubject, p, o);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Find & verify
        // -----------------------
        dataset.begin(TxnType.READ);
        try {
            // find with specific subject
            Iterator<Quad> it = datasetJena.find(graph, blankSubject, p, o);

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(1, results.size(),
                    "There should be exactly one quad with the blank node subject");

            Quad quad = results.get(0);
            assertTrue(quad.getSubject().isBlank(),
                    "The subject of the returned quad should be a blank node");
            assertEquals(p, quad.getPredicate());
            assertEquals(o, quad.getObject());

        } finally {
            dataset.end();
        }
    }

    @Test
    void testJenaUnderstandingNodeLabel() throws Exception {

        String urn = "urn:atic:blanknode-1234";

        Node b = NodeFactory.createBlankNode(urn);
        String label = b.getBlankNodeLabel();

        assertEquals(urn, label);
    }

    //======================================================================================
    
    @Test
    void testAddAndFindBlankNodeTriple() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:blankGraph");

        // create blank node for subject
        Node blankSubject = NodeFactory.createBlankNode();

        Node p = NodeFactory.createURI("urn:test:p");
        Node o = NodeFactory.createURI("urn:test:o");

        Triple blankTriple = Triple.create(blankSubject, p, o);

        // -----------------------
        // Set up: create named graph
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Add blank node triple
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.add(graph, blankSubject, p, o, ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Find & verify
        // -----------------------
        dataset.begin(TxnType.READ);
        try {
            // find with specific subject
            Iterator<Quad> it = dataset.find(graph, Node.ANY, Node.ANY, Node.ANY, ctx);

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(1, results.size(),
                    "There should be exactly one quad with the blank node subject");

            Quad quad = results.get(0);
            assertTrue(quad.getSubject().isBlank(),
                    "The subject of the returned quad should be a blank node");
            assertEquals(p, quad.getPredicate());
            assertEquals(o, quad.getObject());

        } finally {
            dataset.end();
        }
    }

    @Test
    void testAddAndFindBlankNodeTripleSPO() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:blankGraph");

        // create blank node for subject
        Node blankSubject = NodeFactory.createBlankNode();

        Node p = NodeFactory.createURI("urn:test:p");
        Node o = NodeFactory.createURI("urn:test:o");


        // -----------------------
        // Set up: create named graph
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Add blank node triple
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.add(graph, blankSubject, p, o, ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Find & verify
        // -----------------------
        dataset.begin(TxnType.READ);
        try {
            // find with specific subject
            Iterator<Quad> it = dataset.find(graph, Node.ANY, p, o, ctx);

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(1, results.size(),
                    "There should be exactly one quad with the blank node subject");

            Quad quad = results.get(0);
            assertTrue(quad.getSubject().isBlank(),
                    "The subject of the returned quad should be a blank node");
            assertEquals(p, quad.getPredicate());
            assertEquals(o, quad.getObject());
            
            assertNotEquals(blankSubject, quad.getSubject(),
                    "Blanknode should not be equal");

        } finally {
            dataset.end();
        }
    }

    @Test
    void testAddAndFindBlankNodeTripleSPL() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:blankGraphLiteral");

        // create blank node for subject
        Node blankSubject = NodeFactory.createBlankNode();

        Node p = NodeFactory.createURI("urn:test:p");

        // literal object
        Node o = NodeFactory.createLiteralString("testLiteral");

        // -----------------------
        // Set up: create named graph
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Add blank node triple with literal object
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.add(graph, blankSubject, p, o, ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Find & verify
        // -----------------------
        dataset.begin(TxnType.READ);
        try {
            Iterator<Quad> it = dataset.find(graph, Node.ANY, p, o, ctx);

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(1, results.size(),
                    "There should be exactly one quad with the blank node subject and literal object");

            Quad quad = results.get(0);

            assertTrue(quad.getSubject().isBlank(),
                    "The subject of the returned quad should be a blank node");

            assertEquals(p, quad.getPredicate());

            assertTrue(quad.getObject().isLiteral(),
                    "The object should be a literal");

            assertEquals("testLiteral", quad.getObject().getLiteralLexicalForm());

        } finally {
            dataset.end();
        }
    }

    @Test
    void testAddAndFindBlankNodeTripleBlankObject() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:blankGraph");

        Node s = NodeFactory.createURI("urn:test:s");
        Node p = NodeFactory.createURI("urn:test:p");

        // create blank node for object
        Node blankObject = NodeFactory.createBlankNode();

        // -----------------------
        // Set up: create named graph
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Add triple with blank node object
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.add(graph, s, p, blankObject, ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Find & verify
        // -----------------------
        dataset.begin(TxnType.READ);
        try {
            Iterator<Quad> it = dataset.find(graph, s, p, Node.ANY, ctx);

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(1, results.size(),
                    "There should be exactly one quad with the blank node object");

            Quad quad = results.get(0);

            assertEquals(s, quad.getSubject());
            assertEquals(p, quad.getPredicate());

            assertTrue(quad.getObject().isBlank(),
                    "The object of the returned quad should be a blank node");

        } finally {
            dataset.end();
        }
    }

    @Test
    void testLoadTTLWithBlankNode() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:ttlGraph");

        // TTL content containing a blank node
        String ttl = """
        @prefix ex: <urn:test:> .

        _:b1 ex:p ex:o .
        """;

        // -----------------------
        // Create graph
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Load TTL into graph
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            Graph g = GraphFactory.createDefaultGraph();
            RDFDataMgr.read(g, new ByteArrayInputStream(ttl.getBytes()), Lang.TTL);

            g.find().forEachRemaining(t -> {
                dataset.add(graph, t.getSubject(), t.getPredicate(), t.getObject(), ctx);
            });

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Verify blank node exists
        // -----------------------
        dataset.begin(TxnType.READ);
        try {

            Node p = NodeFactory.createURI("urn:test:p");
            Node o = NodeFactory.createURI("urn:test:o");

            Iterator<Quad> it = dataset.find(graph, Node.ANY, p, o, ctx);

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(1, results.size(),
                    "Exactly one triple should exist from TTL");

            Quad quad = results.get(0);

            assertTrue(quad.getSubject().isBlank(),
                    "Subject loaded from TTL should be a blank node");

            //System.out.println(quad.getSubject());

            assertEquals(p, quad.getPredicate());
            assertEquals(o, quad.getObject());

        } finally {
            dataset.end();
        }
    }

    @Test
    void testSerializeBlankNodePropertyList() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:ttlGraphAddress");

        // TTL with blank node property list (address)
        String ttl = """
        @prefix ex: <urn:test:> .

        ex:person1 ex:hasAddress [
            ex:street "Main Street 1" ;
            ex:city "Berlin"
        ] .
        """;

        // -----------------------
        // Create graph
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Load TTL into dataset
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            Graph g = GraphFactory.createDefaultGraph();
            RDFDataMgr.read(g, new ByteArrayInputStream(ttl.getBytes()), Lang.TTL);

            g.find().forEachRemaining(t -> {
                dataset.add(graph, t.getSubject(), t.getPredicate(), t.getObject(), ctx);
            });

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Serialize dataset graph to TTL
        // -----------------------
        dataset.begin(TxnType.READ);
        try {

            Graph g = dataset.getGraph(graph, ctx);
            dataset.getContext().put(InvocationContext.USER_ID, ctx.getUserId());
            dataset.getContext().put(InvocationContext.PRIMARY_GROUP_ID, ctx.getPrimaryGroupId());
            dataset.getContext().put(InvocationContext.GROUP_IDS, ctx.getGroupIds());

            StringWriter sw = new StringWriter();
            RDFDataMgr.write(sw, g, RDFFormat.TURTLE_PRETTY);

            String serialized = sw.toString();

            //System.out.println(serialized);

            // Verify that a blank node property list was serialized
            assertTrue(serialized.contains("_:"),
                    "Serialized TTL should contain a blank node '_:'");

        } finally {
            dataset.end();
        }
    }

    @Test
    void testDefaultGraphBlankNodeSizeContainsRemove() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        dataset.getContext().put(InvocationContext.USER_ID, ctx.getUserId());
        dataset.getContext().put(InvocationContext.PRIMARY_GROUP_ID, ctx.getPrimaryGroupId());
        dataset.getContext().put(InvocationContext.GROUP_IDS, ctx.getGroupIds());

        Node s = NodeFactory.createURI("urn:test:person");
        Node p = NodeFactory.createURI("urn:test:hasAddress");

        // blank node object
        Node bnode = NodeFactory.createBlankNode();

        //Triple t = Triple.create(s, p, bnode);

        // -----------------------
        // Add triple to DEFAULT graph
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {

            dataset.add(Quad.defaultGraphIRI, s, p, bnode, ctx);

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Verify size + contains
        // -----------------------
        dataset.begin(TxnType.READ);
        try {

            Graph g = dataset.getDefaultGraph(ctx);

            assertEquals(1, g.size(), "Default graph should contain exactly one triple");

            assertTrue(
                    g.contains(s, p, Node.ANY),
                    "Default graph should contain the blank node triple"
            );

        } finally {
            dataset.end();
        }

        // -----------------------
        // Remove triple
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {

            dataset.deleteAny(Quad.defaultGraphIRI, s, p, Node.ANY, ctx);

            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Verify removal
        // -----------------------
        dataset.begin(TxnType.READ);
        try {

            Graph g = dataset.getDefaultGraph(ctx);

            assertEquals(0, g.size(), "Default graph should be empty after removal");

            assertFalse(
                    g.contains(s, p, bnode),
                    "Triple should no longer exist in default graph"
            );

        } finally {
            dataset.end();
        }
    }

    @Test
    void testReuseBlankNodeAcrossTwoAddsSameTransaction() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:bnodeReuseGraph");

        Node s = NodeFactory.createURI("urn:test:person");
        Node p1 = NodeFactory.createURI("urn:test:hasAddress");
        Node p2 = NodeFactory.createURI("urn:test:city");

        Node city = NodeFactory.createLiteralString("Berlin");

        // single blank node reused in two triples
        Node bnode = NodeFactory.createBlankNode();

        // -----------------------
        // create graph
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        //auto-commit of add
        SqliteAticGraph.setDefaultBatchSize(1);
        SqliteAticGraph.setDefaultBufferSize(1);
        
        // -----------------------
        // add two triples using same blank node
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {

            dataset.add(graph, s, p1, bnode, ctx);
            dataset.add(graph, bnode, p2, city, ctx);

            // find triples referring to the blank node
            Iterator<Quad> it = dataset.find(graph, Node.ANY, Node.ANY, Node.ANY, ctx);

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(2, results.size(),
                    "Two triples should exist using the same blank node");

            // find the blank node returned by the store
            Node returnedBnode = null;

            for (Quad q : results) {
                if (q.getObject().isBlank()) {
                    returnedBnode = q.getObject();
                    break;
                }
            }

            assertNotNull(returnedBnode, "Returned graph should contain a blank node");

            // second triple should reference the SAME blank node
            boolean foundLink = false;
            for (Quad q : results) {
                if (q.getSubject().equals(returnedBnode)) {
                    foundLink = true;
                }
            }

            assertTrue(foundLink,
                    "Returned blank node should link the two triples");

            dataset.commit();

        } finally {
            dataset.end();
        }
    }

    @Test
    void testAddTripleWithBlankNodePredicateShouldFail() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:blankPredicateGraph");

        Node s = NodeFactory.createURI("urn:test:s");
        Node blankPredicate = NodeFactory.createBlankNode(); // illegal predicate
        Node o = NodeFactory.createURI("urn:test:o");

        // -----------------------
        // Create graph
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Try to add triple with blank predicate
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {

            assertThrows(IllegalArgumentException.class, () -> {
                dataset.add(graph, s, blankPredicate, o, ctx);
            }, "Adding a triple with a blank node predicate should throw IllegalArgumentException");

            dataset.commit();
        } finally {
            dataset.end();
        }
    }
}
