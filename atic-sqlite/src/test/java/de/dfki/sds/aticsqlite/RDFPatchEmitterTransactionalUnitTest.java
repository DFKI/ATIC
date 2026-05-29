package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.Permission;
import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.commons.io.output.WriterOutputStream;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdfpatch.RDFChanges;
import org.apache.jena.rdfpatch.RDFPatchOps;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.sparql.graph.GraphFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class RDFPatchEmitterTransactionalUnitTest {

    private SqliteAticDatasetGraph dataset;

    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);
    }

    @Test
    public void testAddSPL() {

        Graph graph = GraphFactory.createGraphMem();

        dataset.addListener((patch) -> {
            //RDFPatchOps.write(System.out, patch);
            patch.apply(new RDFChangesApplyGraphUnwrap(graph));
        });

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();

        // --- invented nodes ---
        Node s = NodeFactory.createURI("urn:test:s");
        Node p = NodeFactory.createURI("urn:test:p");
        Node o = NodeFactory.createLiteralString("value");

        dataset.executeWrite(() -> {

            Node g = dataset.addGraph(Graph.emptyGraph, ctx);

            dataset.add(g, s, p, o, ctx);
        });

        // --- ASSERT ---
        assertEquals(1, graph.size());
        assertTrue(graph.contains(s, p, o));
    }

    @Test
    public void testAddSPLMultiUser() {

        Graph graph = GraphFactory.createGraphMem();

        Boolean[] check = new Boolean[] { false };
        
        dataset.addListener((patch) -> {
            
            StringWriter sw = new StringWriter();
            WriterOutputStream wos = null;
            try {
                wos = WriterOutputStream.builder()
                        .setWriter(sw)
                        .setCharset(StandardCharsets.UTF_8)
                        .get();
            } catch (IOException ex) {
                //ignore
            }
            RDFPatchOps.write(wos, patch);
            
            //two users involved
            if(check[0]) {
                assertTrue(sw.toString().contains("H userids \"1,2\""));
            }
                
            patch.apply(new RDFChangesApplyGraphUnwrap(graph));
        });

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });
        
        dataset.executeWrite(() -> {
            dataset.addUser("A", "B", "", "alice", InvocationContext.EMPTY);
        });
        
        User aliceUser = dataset.calculateRead(() -> {
            return dataset.getUser("alice", InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();
        
        InvocationContext ctx2 = new InvocationContext.Builder()
                .fromUser(aliceUser)
                .build();

        // --- invented nodes ---
        Node s = NodeFactory.createURI("urn:test:s");
        Node p = NodeFactory.createURI("urn:test:p");
        Node o = NodeFactory.createLiteralString("value");
        
        Node s2 = NodeFactory.createURI("urn:test:s2");
        Node p2 = NodeFactory.createURI("urn:test:p2");
        Node o2 = NodeFactory.createLiteralString("value2");

        //now check for two users
        check[0] = true;
        
        dataset.executeWrite(() -> {

            Node g = dataset.addGraph(Graph.emptyGraph, ctx);
            
            dataset.shareGraphs(Set.of(g.getURI()), Set.of(aliceUser.getPrimaryGroupUri()), Permission.ADMIN, ctx);

            dataset.add(g, s, p, o, ctx);
            dataset.add(g, s2, p2, o2, ctx2);
        });

        // --- ASSERT ---
        assertEquals(2, graph.size());
        assertTrue(graph.contains(s, p, o));
        assertTrue(graph.contains(s2, p2, o2));
        
        
    }
    
    @Test
    public void testAddSPO() {

        Graph graph = GraphFactory.createGraphMem();

        dataset.addListener((patch) -> {
            //RDFPatchOps.write(System.out, patch);
            patch.apply(new RDFChangesApplyGraphUnwrap(graph));
        });

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();

        // --- invented nodes ---
        Node s = NodeFactory.createURI("urn:test:s");
        Node p = NodeFactory.createURI("urn:test:p");
        Node o = NodeFactory.createURI("urn:test:o");

        dataset.executeWrite(() -> {

            Node g = dataset.addGraph(Graph.emptyGraph, ctx);

            dataset.add(g, s, p, o, ctx);
        });

        // --- ASSERT ---
        assertEquals(1, graph.size());
        assertTrue(graph.contains(s, p, o));
    }

    @Test
    public void testAddManySPL() {

        Graph graph = GraphFactory.createGraphMem();

        dataset.addListener((patch) -> {
            //RDFPatchOps.write(System.out, patch);
            patch.apply(new RDFChangesApplyGraphUnwrap(graph));
        });

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();

        // --- keep generated triples for assertion ---
        List<Triple> expected = new ArrayList<>();

        dataset.executeWrite(() -> {

            Node g = dataset.addGraph(Graph.emptyGraph, ctx);
            SqliteAticGraph aticGraph = (SqliteAticGraph) dataset.getGraph(g, ctx);

            StreamRDF stream = aticGraph.asStreamRDF(ctx, 100, 100, -1);
            stream.start();

            // --- generate many triples ---
            for (int i = 0; i < 1000; i++) {
                Node s = NodeFactory.createURI("urn:test:s" + i);
                Node p = NodeFactory.createURI("urn:test:p" + i);
                Node o = NodeFactory.createLiteralString("v" + i);

                Triple t = Triple.create(s, p, o);
                expected.add(t);

                stream.triple(t);   
            }

            stream.finish();
        });

        // --- ASSERT ---
        assertEquals(expected.size(), graph.size());

        for (Triple t : expected) {
            assertTrue(graph.contains(t));
        }
    }
    
    @Test
    public void testAddManySPLAndRemoveAll() {

        Graph graph = GraphFactory.createGraphMem();

        dataset.addListener((patch) -> {
            //RDFPatchOps.write(System.out, patch);
            patch.apply(new RDFChangesApplyGraphUnwrap(graph));
        });

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();

        // --- keep generated triples for assertion ---
        List<Triple> expected = new ArrayList<>();
                
        Node g = dataset.calculateWrite(() -> {
            return dataset.addGraph(Graph.emptyGraph, ctx);
        });

        dataset.executeWrite(() -> {
            SqliteAticGraph aticGraph = (SqliteAticGraph) dataset.getGraph(g, ctx);

            StreamRDF stream = aticGraph.asStreamRDF(ctx, 100, 100, -1);
            stream.start();

            // --- generate many triples ---
            for (int i = 0; i < 10; i++) {
                Node s = NodeFactory.createURI("urn:test:s" + i);
                Node p = NodeFactory.createURI("urn:test:p" + i);
                Node o = NodeFactory.createLiteralString("v" + i);

                Triple t = Triple.create(s, p, o);
                expected.add(t);

                stream.triple(t);   
            }

            stream.finish();
        });
        
        // --- ASSERT ---
        assertEquals(expected.size(), graph.size());

        for (Triple t : expected) {
            assertTrue(graph.contains(t));
        }
        
        //uses aticgraph.remove so we check that the find works correctly
        dataset.executeWrite(() -> {
            dataset.deleteAny(g, Node.ANY, Node.ANY, Node.ANY, ctx);
        });

        // --- ASSERT ---
        assertEquals(0, graph.size());
    }

    @Test
    public void testAddAndDeleteSPL() {

        Graph graph = GraphFactory.createGraphMem();

        dataset.addListener((patch) -> {
            //RDFPatchOps.write(System.out, patch);
            patch.apply(new RDFChangesApplyGraphUnwrap(graph));
        });

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();

        // --- invented nodes ---
        Node s = NodeFactory.createURI("urn:test:s");
        Node p = NodeFactory.createURI("urn:test:p");
        Node o = NodeFactory.createLiteralString("value");

        Node g = dataset.calculateWrite(() -> {
            return dataset.addGraph(Graph.emptyGraph, ctx);
        });
        
        dataset.executeWrite(() -> {
            dataset.add(g, s, p, o, ctx);
        });
        
        dataset.executeWrite(() -> {
            dataset.delete(g, s, p, o, ctx);
        });

        // --- ASSERT ---
        assertEquals(0, graph.size());
        assertFalse(graph.contains(s, p, o));
    }

    
    private class RDFChangesApplyGraphUnwrap implements RDFChanges {

        private Graph graph;

        public RDFChangesApplyGraphUnwrap(Graph graph) {
            this.graph = graph;
        }

        @Override
        public void start() {
        }

        @Override
        public void finish() {
        }

        @Override
        public void segment() {
        }

        @Override
        public void header(String field, Node value) {
        }

        @Override
        public void add(Node g, Node s, Node p, Node o) {
            g = RDFPatchEmitterTransactional.unwrap(g);
            if(g.equals(RDFPatchEmitterTransactional.ATIC_GRAPH_PERMISSION) ||
               g.equals(RDFPatchEmitterTransactional.ATIC_RESOURCE_PERMISSION) ||
               g.equals(RDFPatchEmitterTransactional.ATIC_USER_GROUP)) {
                return;
            }
            
            s = RDFPatchEmitterTransactional.unwrap(s);
            p = RDFPatchEmitterTransactional.unwrap(p);
            o = RDFPatchEmitterTransactional.unwrap(o);
            graph.add(Triple.create(s, p, o));
        }

        @Override
        public void delete(Node g, Node s, Node p, Node o) {
            g = RDFPatchEmitterTransactional.unwrap(g);
            if(g.equals(RDFPatchEmitterTransactional.ATIC_GRAPH_PERMISSION) ||
               g.equals(RDFPatchEmitterTransactional.ATIC_RESOURCE_PERMISSION) ||
               g.equals(RDFPatchEmitterTransactional.ATIC_USER_GROUP)) {
                return;
            }
            
            s = RDFPatchEmitterTransactional.unwrap(s);
            p = RDFPatchEmitterTransactional.unwrap(p);
            o = RDFPatchEmitterTransactional.unwrap(o);
            graph.delete(Triple.create(s, p, o));
        }

        @Override
        public void addPrefix(Node gn, String prefix, String uriStr) {
        }

        @Override
        public void deletePrefix(Node gn, String prefix) {
        }

        @Override
        public void txnBegin() {
        }

        @Override
        public void txnCommit() {
        }

        @Override
        public void txnAbort() {
        }
    }
}
