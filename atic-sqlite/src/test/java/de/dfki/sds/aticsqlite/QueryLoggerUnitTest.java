

package de.dfki.sds.aticsqlite;
 
import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.TxnType;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.graph.GraphFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class QueryLoggerUnitTest {

    private SqliteAticDatasetGraph dataset;
    
    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);
        
        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();
        
        Path queryLogFile = tempDir.resolve("query_log.db");
        dataset.enableQueryLogger(queryLogFile.toString(), ctx);
        
        //System.out.println("query logger enabled: " + queryLogFile);
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

}