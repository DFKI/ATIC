package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Random;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.TxnType;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.util.iterator.ExtendedIterator;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class LimitOffsetUnitTest {

    private SqliteAticDatasetGraph dataset;

    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);
    }

    @Test
    void createLargeDatasetForLimitOffset() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:limitGraph");
        Node subject = NodeFactory.createURI("urn:test:subject");

        Random random = new Random();

        // ---- create graph ----
        dataset.begin(TxnType.WRITE);
        try {

            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);

            // ---- create 1000 SPO triples ----
            for (int i = 0; i < 1000; i++) {

                Node predicate = NodeFactory.createURI("urn:p:" + i);

                Node object = NodeFactory.createURI(
                        "urn:o:" + random.nextInt(1_000_000)
                );

                dataset.add(graph, subject, predicate, object, ctx);
            }

            // ---- create 1000 SPL triples ----
            for (int i = 0; i < 1000; i++) {

                Node predicate = NodeFactory.createURI("urn:pl:" + i);

                Node literal = NodeFactory.createLiteralString(
                        "literal-" + random.nextInt(1_000_000)
                );

                dataset.add(graph, subject, predicate, literal, ctx);
            }

            dataset.commit();

        } finally {
            dataset.end();
        }

        // ---- verify full dataset ----
        dataset.begin(TxnType.READ);
        try {

            Iterator<Quad> it = dataset.find(graph, subject, Node.ANY, Node.ANY, ctx);

            int count = 0;
            while (it.hasNext()) {
                it.next();
                count++;
            }

            assertEquals(2000, count, "Expected exactly 2000 triples");

        } finally {
            dataset.end();
        }

        // ---- test SPO pagination ----
        dataset.begin(TxnType.READ);
        SqliteAticGraph aticGraph = (SqliteAticGraph) dataset.getGraph(graph, ctx);

        try {

            ExtendedIterator<Triple> it = aticGraph.findSPO(
                    subject,
                    Node.ANY,
                    Node.ANY,
                    10,
                    0,
                    false,
                    null,
                    ctx
            );

            int count = 0;
            while (it.hasNext()) {
                it.next();
                count++;
            }

            assertEquals(10, count, "SPO limit should return exactly 10 triples");

        } finally {
            dataset.end();
        }

        // ---- test SPL pagination ----
        dataset.begin(TxnType.READ);
        try {

            ExtendedIterator<Triple> it = aticGraph.findSPL(
                    subject,
                    Node.ANY,
                    Node.ANY,
                    10,
                    0,
                    false,
                    null,
                    ctx
            );

            int count = 0;
            while (it.hasNext()) {
                it.next();
                count++;
            }

            assertEquals(10, count, "SPL limit should return exactly 10 triples");

        } finally {
            dataset.end();
        }

        // ---- test offset behavior ----
        dataset.begin(TxnType.READ);
        try {

            ExtendedIterator<Triple> it = aticGraph.findSPO(
                    subject,
                    Node.ANY,
                    Node.ANY,
                    10,
                    10,
                    false,
                    null,
                    ctx
            );

            int count = 0;
            while (it.hasNext()) {
                it.next();
                count++;
            }

            assertEquals(10, count, "SPO limit+offset should still return 10");

        } finally {
            dataset.end();
        }
    }

    @Test
    void testFindWithSeparateSpoSplPagination() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:limitGraph2");
        Node subject = NodeFactory.createURI("urn:test:subject");

        Random random = new Random();

        // ---- create dataset ----
        dataset.begin(TxnType.WRITE);
        try {

            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);

            // 1000 SPO
            for (int i = 0; i < 1000; i++) {

                Node predicate = NodeFactory.createURI("urn:p:" + i);
                Node object = NodeFactory.createURI("urn:o:" + random.nextInt(1_000_000));

                dataset.add(graph, subject, predicate, object, ctx);
            }

            // 1000 SPL
            for (int i = 0; i < 1000; i++) {

                Node predicate = NodeFactory.createURI("urn:pl:" + i);
                Node literal = NodeFactory.createLiteralString("literal-" + random.nextInt(1_000_000));

                dataset.add(graph, subject, predicate, literal, ctx);
            }

            dataset.commit();

        } finally {
            dataset.end();
        }

        // ---- page through SPO ----
        int spoTotal = 0;

        for (int offset = 0; offset < 1000; offset += 50) {

            dataset.begin(TxnType.READ);
            try {

                ExtendedIterator<Triple> it
                        = ((SqliteAticGraph) dataset.getGraph(graph, ctx))
                                .find(subject, Node.ANY, Node.ANY,
                                        50, offset, // SPO paging
                                        0, 0, // SPL disabled
                                        ctx);

                int pageCount = 0;

                while (it.hasNext()) {
                    it.next();
                    pageCount++;
                }

                assertTrue(pageCount <= 50);

                spoTotal += pageCount;

            } finally {
                dataset.end();
            }
        }

        assertEquals(1000, spoTotal, "Expected to page through all SPO triples");

        // ---- page through SPL ----
        int splTotal = 0;

        for (int offset = 0; offset < 1000; offset += 50) {

            dataset.begin(TxnType.READ);
            try {

                ExtendedIterator<Triple> it
                        = ((SqliteAticGraph) dataset.getGraph(graph, ctx))
                                .find(subject, Node.ANY, Node.ANY,
                                        0, 0, // SPO disabled
                                        50, offset, // SPL paging
                                        ctx);

                int pageCount = 0;

                while (it.hasNext()) {
                    it.next();
                    pageCount++;
                }

                assertTrue(pageCount <= 50);

                splTotal += pageCount;

            } finally {
                dataset.end();
            }
        }

        assertEquals(1000, splTotal, "Expected to page through all SPL triples");
    }

    @Test
    void testFindSPOAndSPLWithHasMorePagination() throws Exception {

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Node graph = NodeFactory.createURI("urn:test:limitGraph3");
        Node subject = NodeFactory.createURI("urn:test:subject");

        Random random = new Random();

        // ---- create dataset ----
        dataset.begin(TxnType.WRITE);
        try {

            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), ctx);

            // 1000 SPO
            for (int i = 0; i < 1000; i++) {
                Node predicate = NodeFactory.createURI("urn:p:" + i);
                Node object = NodeFactory.createURI("urn:o:" + random.nextInt(1_000_000));
                dataset.add(graph, subject, predicate, object, ctx);
            }

            // 1000 SPL
            for (int i = 0; i < 1000; i++) {
                Node predicate = NodeFactory.createURI("urn:pl:" + i);
                Node literal = NodeFactory.createLiteralString("literal-" + random.nextInt(1_000_000));
                dataset.add(graph, subject, predicate, literal, ctx);
            }

            dataset.commit();

        } finally {
            dataset.end();
        }

        // ---- test findSPO pagination with hasMore ----
        int spoAccumulated = 0;
        int pageSize = 50;

        for (int offset = 0; offset < 1000; offset += pageSize) {

            dataset.begin(TxnType.READ);
            try {

                ExtendedIterator<Triple> iter
                        = ((SqliteAticGraph) dataset.getGraph(graph, ctx))
                                .findSPO(subject, Node.ANY, Node.ANY,
                                        pageSize, offset, false, null, ctx);

                assertTrue(iter instanceof PagedTripleIterator,
                        "Iterator should be a PagedTripleIterator");

                PagedTripleIterator pit = (PagedTripleIterator) iter;

                int pageCount = 0;
                while (pit.hasNext()) {
                    pit.next();
                    pageCount++;
                }

                assertTrue(pageCount <= pageSize,
                        "Page should return no more than limit");

                boolean expectedHasMore = offset < (1000 - pageSize);
                assertEquals(expectedHasMore, pit.hasMore(),
                        "hasMore() mismatch at offset " + offset);

                spoAccumulated += pageCount;

            } finally {
                dataset.end();
            }
        }

        assertEquals(1000, spoAccumulated,
                "Total returned by findSPO should equal total SPO triples");

        // ---- test findSPL pagination with hasMore ----
        int splAccumulated = 0;

        for (int offset = 0; offset < 1000; offset += pageSize) {

            dataset.begin(TxnType.READ);
            try {

                ExtendedIterator<Triple> iter
                        = ((SqliteAticGraph) dataset.getGraph(graph, ctx))
                                .findSPL(subject, Node.ANY, Node.ANY,
                                        pageSize, offset, false, null, ctx);

                assertTrue(iter instanceof PagedTripleIterator,
                        "Iterator should be a PagedTripleIterator");

                PagedTripleIterator pit = (PagedTripleIterator) iter;

                int pageCount = 0;
                while (pit.hasNext()) {
                    pit.next();
                    pageCount++;
                }

                assertTrue(pageCount <= pageSize,
                        "Page should return no more than limit");

                boolean expectedHasMore = offset < (1000 - pageSize);
                assertEquals(expectedHasMore, pit.hasMore(),
                        "hasMore() mismatch at offset " + offset);

                splAccumulated += pageCount;

            } finally {
                dataset.end();
            }
        }

        assertEquals(1000, splAccumulated,
                "Total returned by findSPL should equal total SPL triples");
    }
    
}
