package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.AticVirtualGraph;
import de.dfki.sds.atic.jenatic.AticVirtualGraphResponse;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.vkg.LocalFilesystemVirtualGraph;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.GraphEventManager;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.TransactionHandler;
import org.apache.jena.graph.Triple;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.json.JSONObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 *
 */
public class VirtualGraphUnitTest {

    private SqliteAticDatasetGraph dataset;

    @BeforeEach
    void setup(@TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);
    }

    @Test
    public void testAddVirtualGraph() {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ictx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graphName = NodeFactory.createURI("urn:atic:virtual-graph");

        JSONObject config = new JSONObject();
        config.put("test", true);

        dataset.executeWrite(() -> {
            dataset.addVirtualGraph(
                    graphName,
                    VirtualGraphUnitTest.class.getName() + ".mockFactory",
                    config,
                    ictx
            );
        });

        // graph should now exist
        dataset.executeRead(() -> {
            AticGraph graph = dataset.getGraph(graphName, ictx);

            assertNotNull(graph, "Virtual graph should be retrievable");

            ExtendedIterator<Triple> it = graph.find(Node.ANY, Node.ANY, Node.ANY, ictx);
            assertTrue(it.hasNext());
            Triple t = it.next();
            assertEquals("urn:virtual:marker", t.getSubject().getURI());
        });
    }

    @Test
    public void testRemoveVirtualGraph() {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ictx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graphName = NodeFactory.createURI("urn:atic:virtual-graph");

        JSONObject config = new JSONObject();
        config.put("test", true);

        // ------------------------------------------------
        // create virtual graph
        // ------------------------------------------------
        dataset.executeWrite(() -> {
            dataset.addVirtualGraph(
                    graphName,
                    VirtualGraphUnitTest.class.getName() + ".mockFactory",
                    config,
                    ictx
            );
        });

        // ------------------------------------------------
        // verify graph exists
        // ------------------------------------------------
        dataset.executeRead(() -> {
            assertTrue(
                    dataset.containsGraph(graphName, ictx),
                    "Graph should not exist after removal"
            );
        });

        // ------------------------------------------------
        // remove graph
        // ------------------------------------------------
        dataset.executeWrite(() -> {
            dataset.removeGraph(graphName, ictx);
        });

        // ------------------------------------------------
        // verify graph is gone
        // ------------------------------------------------
        dataset.executeRead(() -> {
            assertFalse(
                    dataset.containsGraph(graphName, ictx),
                    "Graph should not exist after removal"
            );
        });
    }
    
    @Test
    public void testLocalFilesystemVirtualGraph(@TempDir Path tempDir) throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ictx = new InvocationContext.Builder().fromUser(adminUser).build();

        // ------------------------------------------------
        // create a real file in temp dir
        // ------------------------------------------------
        Path folder = Files.createDirectory(tempDir.resolve("data"));
        Path file = Files.createFile(folder.resolve("test.txt"));

        Files.writeString(file, "hello world");

        // ------------------------------------------------
        // register VKG
        // ------------------------------------------------
        Node graphName = NodeFactory.createURI("urn:atic:vkg-files");

        JSONObject config = new JSONObject();
        config.put("host", "urn:atic:file");

        dataset.executeWrite(() -> {
            dataset.addVirtualGraph(
                    graphName,
                    LocalFilesystemVirtualGraph.class.getName() + ".create",
                    config,
                    ictx
            );
        });

        // ------------------------------------------------
        // query the file through the VKG
        // ------------------------------------------------
        dataset.executeRead(() -> {

            AticGraph graph = dataset.getGraph(graphName, ictx);
            assertNotNull(graph);

            // build the VKG URI
            String fileUri = "urn:atic:file" + file.toAbsolutePath().toString();

            Node s = NodeFactory.createURI(fileUri);

            ExtendedIterator<Triple> it = graph.find(s, Node.ANY, Node.ANY, ictx);

            assertTrue(it.hasNext(), "Expected triples for file in filesystem VKG");

            Triple t = it.next();
            assertEquals(fileUri, t.getSubject().getURI());
        });
    }

    @Test
    public void testLocalFilesystemVirtualGraphReload(@TempDir Path tempDir) throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ictx = new InvocationContext.Builder().fromUser(adminUser).build();

        // database path (same file will be reused)
        //Path dbFile = tempDir.resolve("test-123.db");

        // ------------------------------------------------
        // create a real file in temp dir
        // ------------------------------------------------
        Path folder = Files.createDirectory(tempDir.resolve("data"));
        Path file = Files.createFile(folder.resolve("test.txt"));
        Files.writeString(file, "hello world");

        Node graphName = NodeFactory.createURI("urn:atic:vkg-files");

        JSONObject config = new JSONObject();
        config.put("host", "urn:atic:file");

        // ------------------------------------------------
        // first dataset instance: create VKG
        // ------------------------------------------------
        SqliteAticDatasetGraph dataset1 = TL.createDatasetGraph(tempDir);

        dataset1.executeWrite(() -> {
            dataset1.addVirtualGraph(
                    graphName,
                    LocalFilesystemVirtualGraph.class.getName() + ".create",
                    config,
                    ictx
            );
        });

        // close first dataset
        dataset1.close();

        // ------------------------------------------------
        // second dataset instance: reopen DB
        // ------------------------------------------------
        SqliteAticDatasetGraph dataset2 = TL.createDatasetGraph(tempDir);

        // ------------------------------------------------
        // verify VKG still works
        // ------------------------------------------------
        dataset2.executeRead(() -> {

            AticGraph graph = dataset2.getGraph(graphName, ictx);
            assertNotNull(graph);

            String fileUri = "urn:atic:file" + file.toAbsolutePath().toString();

            Node s = NodeFactory.createURI(fileUri);

            ExtendedIterator<Triple> it = graph.find(s, Node.ANY, Node.ANY, ictx);

            assertTrue(it.hasNext(), "Expected triples after dataset restart");

            Triple t = it.next();
            assertEquals(fileUri, t.getSubject().getURI());
        });

        dataset2.close();
    }

    public static AticVirtualGraph mockFactory(String uri, String config, SqliteAticDatasetGraph parent) {
        return new AticVirtualGraph() {

            private static final Graph MARKER_GRAPH;
            private static final Triple MARKER_TRIPLE;

            static {
                MARKER_GRAPH = GraphFactory.createDefaultGraph();

                Node s = NodeFactory.createURI("urn:virtual:marker");
                Node p = NodeFactory.createURI("urn:virtual:isGraph");
                Node o = NodeFactory.createLiteralString("true");

                MARKER_TRIPLE = Triple.create(s, p, o);
                MARKER_GRAPH.add(MARKER_TRIPLE);
            }

            @Override
            public ExtendedIterator<Triple> find(Triple m, InvocationContext ctx) {
                return find(m.getSubject(), m.getPredicate(), m.getObject(), ctx);
            }

            @Override
            public ExtendedIterator<Triple> find(Node s, Node p, Node o, InvocationContext ctx) {

                // delegate to the in-memory graph
                return MARKER_GRAPH.find(
                        s == null ? Node.ANY : s,
                        p == null ? Node.ANY : p,
                        o == null ? Node.ANY : o
                );
            }

            @Override
            public boolean dependsOn(Graph other, InvocationContext ctx) {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public TransactionHandler getTransactionHandler() {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public GraphEventManager getEventManager() {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public PrefixMapping getPrefixMapping(InvocationContext ctx) {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public void add(Triple t, InvocationContext ctx) {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public void delete(Triple t, InvocationContext ctx) {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public boolean isIsomorphicWith(Graph g, InvocationContext ctx) {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public boolean contains(Node s, Node p, Node o, InvocationContext ctx) {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public boolean contains(Triple t, InvocationContext ctx) {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public void clear(InvocationContext ctx) {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public void remove(Node s, Node p, Node o, InvocationContext ctx) {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public void close(InvocationContext ctx) {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public boolean isEmpty(InvocationContext ctx) {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public int size(InvocationContext ctx) {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public boolean isClosed(InvocationContext ctx) {
                throw new UnsupportedOperationException("Not supported yet.");
            }

            @Override
            public AticVirtualGraphResponse handleRequest(String method, String path, Map<String, List<String>> queryParamMap, InvocationContext ctx) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        };
    }

}
