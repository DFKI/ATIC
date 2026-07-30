package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.nio.file.Path;
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

/**
 *
 */
public class SystemGraphUnitTest {

    private SqliteAticDatasetGraph dataset;

    private SystemAticGraph systemGraph;

    private InvocationContext adminCtx;
    private InvocationContext aliceCtx;
    private InvocationContext bobCtx;

    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);
        systemGraph = (SystemAticGraph) dataset.getSystemGraph();

        Txn.executeWrite(dataset, () -> {
            dataset.addUser("Alice", "Doe", "alice.doe@example.com", "alice", InvocationContext.EMPTY);
            dataset.addUser("Bob", "Doe", "bob.doe@example.com", "bob", InvocationContext.EMPTY);
        });

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        User alice = dataset.calculateRead(()
                -> dataset.getUser("alice", InvocationContext.EMPTY)
        );

        User bob = dataset.calculateRead(()
                -> dataset.getUser("bob", InvocationContext.EMPTY)
        );

        adminCtx = new InvocationContext.Builder().fromUser(adminUser).build();
        aliceCtx = new InvocationContext.Builder().fromUser(alice).build();
        bobCtx = new InvocationContext.Builder().fromUser(bob).build();
    }

    @Test
    void aliceCanCreateAndReadSystemTriple() throws Exception {
        Node subject = NodeFactory.createURI("urn:resource:123");
        Node predicate = NodeFactory.createURI("urn:comem:memoryBuoyancy");
        Node object = NodeFactory.createLiteralByValue(0.67);

        Txn.executeWrite(dataset, () -> {
            systemGraph.add(
                    Triple.create(subject, predicate, object),
                    aliceCtx);
        });

        dataset.executeRead(() -> {
            ExtendedIterator<Triple> it = systemGraph.find(
                    subject,
                    Node.ANY,
                    Node.ANY,
                    aliceCtx);

            try {
                assertTrue(it.hasNext(), "Alice should be able to read her system triple");

                Triple triple = it.next();

                assertEquals(subject, triple.getSubject());
                assertEquals(predicate, triple.getPredicate());
                assertEquals(object, triple.getObject());

                assertFalse(it.hasNext(), "Only one matching system triple expected");
            } finally {
                it.close();
            }
        });
    }

    @Test
    void usersOnlySeeTheirOwnSystemTriplesForSameSubject() throws Exception {
        Node subject = NodeFactory.createURI("urn:resource:123");
        Node predicate = NodeFactory.createURI("urn:comem:memoryBuoyancy");

        Node aliceValue = NodeFactory.createLiteralByValue(0.67);
        Node bobValue = NodeFactory.createLiteralByValue(0.42);

        Txn.executeWrite(dataset, () -> {
            systemGraph.add(
                    Triple.create(subject, predicate, aliceValue),
                    aliceCtx);

            systemGraph.add(
                    Triple.create(subject, predicate, bobValue),
                    bobCtx);
        });

        dataset.executeRead(() -> {
            ExtendedIterator<Triple> aliceIt = systemGraph.find(
                    subject,
                    predicate,
                    Node.ANY,
                    aliceCtx);

            try {
                assertTrue(aliceIt.hasNext(), "Alice should see her own system triple");

                Triple triple = aliceIt.next();

                assertEquals(subject, triple.getSubject());
                assertEquals(predicate, triple.getPredicate());
                assertEquals(aliceValue, triple.getObject());

                assertFalse(aliceIt.hasNext(), "Alice should only see her own triple");
            } finally {
                aliceIt.close();
            }
        });

        dataset.executeRead(() -> {
            ExtendedIterator<Triple> bobIt = systemGraph.find(
                    subject,
                    predicate,
                    Node.ANY,
                    bobCtx);

            try {
                assertTrue(bobIt.hasNext(), "Bob should see his own system triple");

                Triple triple = bobIt.next();

                assertEquals(subject, triple.getSubject());
                assertEquals(predicate, triple.getPredicate());
                assertEquals(bobValue, triple.getObject());

                assertFalse(bobIt.hasNext(), "Bob should only see his own triple");
            } finally {
                bobIt.close();
            }
        });
    }

    @Test
    void containsAndSizeWorkCorrectlyForUserSystemTriples() throws Exception {
        Node subject = NodeFactory.createURI("urn:resource:123");
        Node predicate = NodeFactory.createURI("urn:comem:memoryBuoyancy");
        Node object = NodeFactory.createLiteralByValue(0.67);

        // Initially there should be no triples
        dataset.executeRead(() -> {
            assertFalse(
                    systemGraph.contains(subject, predicate, object, aliceCtx),
                    "Alice should not have the triple before insertion");

            assertEquals(
                    0,
                    systemGraph.size(aliceCtx),
                    "Alice should have no system triples before insertion");
        });

        // Insert Alice's triple
        Txn.executeWrite(dataset, () -> {
            systemGraph.add(
                    Triple.create(subject, predicate, object),
                    aliceCtx);
        });

        // Verify contains and size after insertion
        dataset.executeRead(() -> {
            assertTrue(
                    systemGraph.contains(subject, predicate, object, aliceCtx),
                    "Alice should find her inserted triple");

            assertEquals(
                    1,
                    systemGraph.size(aliceCtx),
                    "Alice should have exactly one system triple");

            assertFalse(
                    systemGraph.contains(subject, predicate, object, bobCtx),
                    "Bob should not see Alice's system triple");

            assertEquals(
                    0,
                    systemGraph.size(bobCtx),
                    "Bob should have no system triples");
        });

        // Insert Bob's triple with same subject/predicate but different value
        Node bobObject = NodeFactory.createLiteralByValue(0.42);

        Txn.executeWrite(dataset, () -> {
            systemGraph.add(
                    Triple.create(subject, predicate, bobObject),
                    bobCtx);
        });

        // Verify isolation after both users inserted triples
        dataset.executeRead(() -> {
            assertTrue(
                    systemGraph.contains(subject, predicate, object, aliceCtx),
                    "Alice should still find her triple");

            assertFalse(
                    systemGraph.contains(subject, predicate, bobObject, aliceCtx),
                    "Alice should not see Bob's triple");

            assertEquals(
                    1,
                    systemGraph.size(aliceCtx),
                    "Alice should still have only one system triple");

            assertTrue(
                    systemGraph.contains(subject, predicate, bobObject, bobCtx),
                    "Bob should find his triple");

            assertFalse(
                    systemGraph.contains(subject, predicate, object, bobCtx),
                    "Bob should not see Alice's triple");

            assertEquals(
                    1,
                    systemGraph.size(bobCtx),
                    "Bob should have only one system triple");
        });
    }

    @Test
    void aliceCanRemoveAllMemoryBuoyancyValuesWithAnyObject() throws Exception {
        Node subject = NodeFactory.createURI("urn:resource:123");
        Node predicate = NodeFactory.createURI("urn:comem:memoryBuoyancy");

        Node value1 = NodeFactory.createLiteralByValue(0.67);
        Node value2 = NodeFactory.createLiteralByValue(0.42);

        // Create two triples for the same subject and predicate
        Txn.executeWrite(dataset, () -> {
            systemGraph.add(
                    Triple.create(subject, predicate, value1),
                    aliceCtx);

            systemGraph.add(
                    Triple.create(subject, predicate, value2),
                    aliceCtx);
        });

        dataset.executeRead(() -> {
            assertEquals(
                    2,
                    systemGraph.size(aliceCtx),
                    "Alice should have two system triples");

            assertTrue(
                    systemGraph.contains(subject, predicate, value1, aliceCtx),
                    "First value should exist");

            assertTrue(
                    systemGraph.contains(subject, predicate, value2, aliceCtx),
                    "Second value should exist");
        });

        // Remove all values for the subject/predicate combination
        Txn.executeWrite(dataset, () -> {
            systemGraph.remove(
                    subject,
                    predicate,
                    Node.ANY,
                    aliceCtx);
        });

        dataset.executeRead(() -> {
            ExtendedIterator<Triple> it = systemGraph.find(
                    subject,
                    predicate,
                    Node.ANY,
                    aliceCtx);

            try {
                assertFalse(
                        it.hasNext(),
                        "No memoryBuoyancy values should remain after removal");
            } finally {
                it.close();
            }

            assertFalse(
                    systemGraph.contains(subject, predicate, value1, aliceCtx),
                    "First value should have been removed");

            assertFalse(
                    systemGraph.contains(subject, predicate, value2, aliceCtx),
                    "Second value should have been removed");

            assertEquals(
                    0,
                    systemGraph.size(aliceCtx),
                    "Alice should have no system triples after removal");
        });
    }

    @Test
    void setReplacesExistingMemoryBuoyancyValue() throws Exception {
        Node subject = NodeFactory.createURI("urn:resource:123");
        Node predicate = NodeFactory.createURI("urn:comem:memoryBuoyancy");

        Node oldValue = NodeFactory.createLiteralByValue(0.67);
        Node newValue = NodeFactory.createLiteralByValue(0.91);

        // Create initial value
        Txn.executeWrite(dataset, () -> {
            systemGraph.add(
                    Triple.create(subject, predicate, oldValue),
                    aliceCtx);
        });

        dataset.executeRead(() -> {
            assertTrue(
                    systemGraph.contains(subject, predicate, oldValue, aliceCtx),
                    "Old value should exist before set");

            assertEquals(
                    1,
                    systemGraph.size(aliceCtx),
                    "Alice should have one system triple before set");
        });

        // Replace value using set (internally remove + add)
        Txn.executeWrite(dataset, () -> {
            systemGraph.set(
                    subject,
                    predicate,
                    newValue,
                    aliceCtx);
        });

        dataset.executeRead(() -> {
            assertFalse(
                    systemGraph.contains(subject, predicate, oldValue, aliceCtx),
                    "Old value should have been removed");

            assertTrue(
                    systemGraph.contains(subject, predicate, newValue, aliceCtx),
                    "New value should exist after set");

            assertEquals(
                    1,
                    systemGraph.size(aliceCtx),
                    "Alice should have exactly one memoryBuoyancy value after set");

            ExtendedIterator<Triple> it = systemGraph.find(
                    subject,
                    predicate,
                    Node.ANY,
                    aliceCtx);

            try {
                assertTrue(it.hasNext());

                Triple triple = it.next();

                assertEquals(subject, triple.getSubject());
                assertEquals(predicate, triple.getPredicate());
                assertEquals(newValue, triple.getObject());

                assertFalse(
                        it.hasNext(),
                        "Only the replaced value should remain");
            } finally {
                it.close();
            }
        });
    }
}
