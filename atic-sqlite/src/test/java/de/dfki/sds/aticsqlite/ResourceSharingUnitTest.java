package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.Group;
import de.dfki.sds.atic.ac.Permission;
import de.dfki.sds.atic.ac.PermissionDeniedException;
import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.system.Txn;
import org.apache.jena.util.iterator.ExtendedIterator;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 *
 */
public class ResourceSharingUnitTest {

    private SqliteAticDatasetGraph dataset;
    private InvocationContext adminCtx;

    // store users
    private User alice;
    private User bob;
    private User charlie;
    private User david;
    private User eve;

    private InvocationContext aliceCtx; //phoenix group
    private InvocationContext bobCtx; //phoenix group
    private InvocationContext charlieCtx; //phoenix group
    private InvocationContext davidCtx; //data group
    private InvocationContext eveCtx; //data group

    private Group phoenixGroup;
    private Group dataGroup;

    @BeforeEach
    void setup(@TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        adminCtx = new InvocationContext.Builder().fromUser(adminUser).build();

        Txn.executeWrite(dataset, () -> {
            dataset.addUser("Alice", "Doe", "alice.doe@example.com", "alice", InvocationContext.EMPTY);
            dataset.addUser("Bob", "Doe", "bob.doe@example.com", "bob", InvocationContext.EMPTY);
            dataset.addUser("Charlie", "Doe", "charlie.doe@example.com", "charlie", InvocationContext.EMPTY);
            dataset.addUser("David", "Doe", "david.doe@example.com", "david", InvocationContext.EMPTY);
            dataset.addUser("Eve", "Doe", "eve.doe@example.com", "eve", InvocationContext.EMPTY);

            dataset.addGroup("phoenix", InvocationContext.EMPTY);
            dataset.addGroup("data", InvocationContext.EMPTY);
        });

        // fetch and store users for reuse in tests
        Txn.executeRead(dataset, () -> {
            alice = dataset.getUser("alice", InvocationContext.EMPTY);
            bob = dataset.getUser("bob", InvocationContext.EMPTY);
            charlie = dataset.getUser("charlie", InvocationContext.EMPTY);
            david = dataset.getUser("david", InvocationContext.EMPTY);
            eve = dataset.getUser("eve", InvocationContext.EMPTY);

            phoenixGroup = dataset.getGroup("phoenix", InvocationContext.EMPTY);
            dataGroup = dataset.getGroup("data", InvocationContext.EMPTY);
        });

        aliceCtx = new InvocationContext.Builder()
                .userId(alice.getId())
                .primaryGroupId(alice.getPrimaryGroup().getId())
                .addGroupId(phoenixGroup.getId())
                .build();

        bobCtx = new InvocationContext.Builder()
                .userId(bob.getId())
                .primaryGroupId(bob.getPrimaryGroup().getId())
                .addGroupId(phoenixGroup.getId())
                .build();

        charlieCtx = new InvocationContext.Builder()
                .userId(charlie.getId())
                .primaryGroupId(charlie.getPrimaryGroup().getId())
                .addGroupId(phoenixGroup.getId())
                .build();

        davidCtx = new InvocationContext.Builder()
                .userId(david.getId())
                .primaryGroupId(david.getPrimaryGroup().getId())
                .addGroupId(dataGroup.getId())
                .build();

        eveCtx = new InvocationContext.Builder()
                .userId(eve.getId())
                .primaryGroupId(eve.getPrimaryGroup().getId())
                .addGroupId(dataGroup.getId())
                .build();

    }

    @Test
    void adminSeesAndControlsAllDataAcrossUsersWithoutExplicitSharing() {

        Node graphNode = NodeFactory.createURI("urn:test:adminFullAccessGraph");

        Node p = NodeFactory.createURI("urn:p");

        Node sAlice = NodeFactory.createURI("urn:alice:s");
        Node oAlice = NodeFactory.createLiteralString("aliceValue");

        Node sBob = NodeFactory.createURI("urn:bob:s");
        Node oBob = NodeFactory.createLiteralString("bobValue");

        // ---------------------------------------
        // 1) Admin creates graph and shares with users (admin does NOT need sharing)
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.addGraph(graphNode, GraphFactory.createDefaultGraph(), adminCtx);

            dataset.shareGraphs(
                    Set.of(graphNode.getURI()),
                    Set.of(
                            alice.getPrimaryGroup().getUri(),
                            bob.getPrimaryGroup().getUri()
                    ),
                    Permission.EDIT,
                    adminCtx
            );
        });

        // ---------------------------------------
        // 2) Alice writes
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.add(graphNode, sAlice, p, oAlice, aliceCtx);
        });

        // ---------------------------------------
        // 3) Bob writes
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.add(graphNode, sBob, p, oBob, bobCtx);
        });

        // ---------------------------------------
        // 4) Admin: find → sees BOTH triples
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            AticGraph graph = dataset.getGraph(graphNode, adminCtx);
            assertNotNull(graph);

            ExtendedIterator<Triple> it = graph.find(
                    Node.ANY,
                    Node.ANY,
                    Node.ANY,
                    adminCtx
            );

            List<Triple> results = it.toList();

            assertEquals(2, results.size(),
                    "Admin should see all triples from all users");

            Set<Node> subjects = results.stream()
                    .map(Triple::getSubject)
                    .collect(Collectors.toSet());

            assertTrue(subjects.contains(sAlice));
            assertTrue(subjects.contains(sBob));
        });

        // ---------------------------------------
        // 5) Admin: contains → works for both
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            AticGraph graph = dataset.getGraph(graphNode, adminCtx);

            assertTrue(graph.contains(sAlice, p, oAlice, adminCtx),
                    "Admin should see Alice's triple");

            assertTrue(graph.contains(sBob, p, oBob, adminCtx),
                    "Admin should see Bob's triple");
        });

        // ---------------------------------------
        // 6) Admin: size → counts everything
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            AticGraph graph = dataset.getGraph(graphNode, adminCtx);

            int size = graph.size(adminCtx);

            assertEquals(2, size,
                    "Admin size() should count all triples regardless of owner");
        });

        // ---------------------------------------
        // 7) Admin: remove → can delete any triple
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            AticGraph graph = dataset.getGraph(graphNode, adminCtx);

            graph.remove(sAlice, p, oAlice, adminCtx);
        });

        // ---------------------------------------
        // 8) verify removal
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            AticGraph graph = dataset.getGraph(graphNode, adminCtx);

            assertFalse(graph.contains(sAlice, p, oAlice, adminCtx),
                    "Admin should be able to remove Alice's triple");

            assertTrue(graph.contains(sBob, p, oBob, adminCtx),
                    "Bob's triple should still exist");

            int size = graph.size(adminCtx);
            assertEquals(1, size,
                    "Size should reflect removal by admin");
        });
    }

    @Test
    void testReadAccessIsolationBetweenUsers() {

        Node graph = NodeFactory.createURI("urn:test:graph");

        Node sAlice = NodeFactory.createURI("urn:alice:s");
        Node sBob = NodeFactory.createURI("urn:bob:s");
        Node p = NodeFactory.createURI("urn:p");
        Node oAlice = NodeFactory.createLiteralString("aliceValue");
        Node oBob = NodeFactory.createLiteralString("bobValue");

        // ---------------------------------------
        // 1) Admin creates graph + shares READ
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), adminCtx);

            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(
                            alice.getPrimaryGroup().getUri(),
                            bob.getPrimaryGroup().getUri(),
                            charlie.getPrimaryGroup().getUri()
                    ),
                    Permission.EDIT,
                    adminCtx
            );
        });

        // ---------------------------------------
        // 2) Alice inserts triple
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.add(graph, sAlice, p, oAlice, aliceCtx);
        });

        // ---------------------------------------
        // 3) Bob inserts triple
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.add(graph, sBob, p, oBob, bobCtx);
        });

        // ---------------------------------------
        // 4) Alice reads → only her triple
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            Iterator<Quad> it = dataset.find(
                    graph,
                    Node.ANY,
                    Node.ANY,
                    Node.ANY,
                    aliceCtx
            );

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(1, results.size());
            assertEquals(sAlice, results.get(0).getSubject());
        });

        // ---------------------------------------
        // 5) Bob reads → only his triple
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            Iterator<Quad> it = dataset.find(
                    graph,
                    Node.ANY,
                    Node.ANY,
                    Node.ANY,
                    bobCtx
            );

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(1, results.size());
            assertEquals(sBob, results.get(0).getSubject());
        });

        // ---------------------------------------
        // 6) Charlie reads → sees nothing
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            Iterator<Quad> it = dataset.find(
                    graph,
                    Node.ANY,
                    Node.ANY,
                    Node.ANY,
                    charlieCtx
            );

            assertFalse(it.hasNext());
        });
    }

    @Test
    void testCannotDeleteWithOnlyReadAccessOnSubject() {

        Node graph = NodeFactory.createURI("urn:test:graph");

        Node sAlice = NodeFactory.createURI("urn:alice:s");
        Node p = NodeFactory.createURI("urn:p");
        Node oAlice = NodeFactory.createLiteralString("aliceValue");

        // ---------------------------------------
        // 1) Admin creates graph + shares EDIT
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), adminCtx);

            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(
                            alice.getPrimaryGroup().getUri(),
                            bob.getPrimaryGroup().getUri()
                    ),
                    Permission.EDIT,
                    adminCtx
            );
        });

        // ---------------------------------------
        // 2) Alice inserts triple
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.add(graph, sAlice, p, oAlice, aliceCtx);
        });

        // ---------------------------------------
        // 3) Alice shares SUBJECT with Bob (READ only)
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.shareResources(
                    Set.of(sAlice.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );
        });

        // ---------------------------------------
        // 4) Bob tries to DELETE → must fail
        // ---------------------------------------
        assertThrows(PermissionDeniedException.class, () -> {
            Txn.executeWrite(dataset, () -> {
                dataset.delete(graph, sAlice, p, oAlice, bobCtx);
            });
        });

        // ---------------------------------------
        // 5) Bob can READ the triple
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            Iterator<Quad> it = dataset.find(
                    graph,
                    Node.ANY,
                    Node.ANY,
                    Node.ANY,
                    bobCtx
            );

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(1, results.size(), "Bob should see Alice's triple");
            assertEquals(sAlice, results.get(0).getSubject());
        });

        // ---------------------------------------
        // 6) Triple still exists (not deleted)
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            boolean exists = dataset.contains(graph, sAlice, p, oAlice, aliceCtx);
            assertTrue(exists, "Triple must still exist after failed delete");
        });
    }

    @Test
    void testEditAccessAllowsModifyTriple() {

        Node graph = NodeFactory.createURI("urn:test:graph");

        Node sAlice = NodeFactory.createURI("urn:alice:s");
        Node p = NodeFactory.createURI("urn:p");
        Node oAlice = NodeFactory.createLiteralString("aliceValue");
        Node oBobUpdated = NodeFactory.createLiteralString("bobUpdatedValue");

        // ---------------------------------------
        // 1) Admin creates graph + shares EDIT
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), adminCtx);

            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(
                            alice.getPrimaryGroup().getUri(),
                            bob.getPrimaryGroup().getUri()
                    ),
                    Permission.EDIT,
                    adminCtx
            );
        });

        // ---------------------------------------
        // 2) Alice inserts triple
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.add(graph, sAlice, p, oAlice, aliceCtx);
        });

        // ---------------------------------------
        // 3) Alice shares SUBJECT + OBJECT with Bob (EDIT!)
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.shareResources(
                    Set.of(sAlice.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.EDIT,
                    aliceCtx
            );
        });

        // ---------------------------------------
        // 4) Bob modifies triple (delete + add)
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.delete(graph, sAlice, p, oAlice, bobCtx);

            dataset.add(graph, sAlice, p, oBobUpdated, bobCtx);
        });

        // ---------------------------------------
        // 5) Verify old triple is gone
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            boolean existsOld = dataset.contains(graph, sAlice, p, oAlice, aliceCtx);
            assertFalse(existsOld, "Old triple should be deleted");
        });

        // ---------------------------------------
        // 6) Verify new triple exists
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            boolean existsNew = dataset.contains(graph, sAlice, p, oBobUpdated, aliceCtx);
            assertTrue(existsNew, "Updated triple should exist");
        });

        // ---------------------------------------
        // 7) Bob sees updated triple
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            Iterator<Quad> it = dataset.find(
                    graph,
                    Node.ANY,
                    Node.ANY,
                    Node.ANY,
                    bobCtx
            );

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(1, results.size());
            assertEquals(oBobUpdated, results.get(0).getObject());
        });
    }

    @Test
    void testDeleteAnyDoesNotBypassReadOnlyPermissions() {

        Node graph = NodeFactory.createURI("urn:test:graph");

        Node sAlice = NodeFactory.createURI("urn:alice:s");
        Node sBob = NodeFactory.createURI("urn:bob:s");

        Node p = NodeFactory.createURI("urn:p");

        Node oAlice = NodeFactory.createLiteralString("aliceValue");
        Node oBob = NodeFactory.createLiteralString("bobValue");

        // ---------------------------------------
        // 1) Admin creates graph + shares EDIT
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), adminCtx);

            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(
                            alice.getPrimaryGroup().getUri(),
                            bob.getPrimaryGroup().getUri()
                    ),
                    Permission.EDIT,
                    adminCtx
            );
        });

        // ---------------------------------------
        // 2) Alice inserts triple
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.add(graph, sAlice, p, oAlice, aliceCtx);
        });

        // ---------------------------------------
        // 3) Bob inserts his own triple
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.add(graph, sBob, p, oBob, bobCtx);
        });

        // ---------------------------------------
        // 4) Alice shares her resource with Bob as READ only
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.shareResources(
                    Set.of(sAlice.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );
        });

        // ---------------------------------------
        // 5) Bob attempts wildcard delete
        // ---------------------------------------
        //bob gets admin access to graph
        Txn.executeWrite(dataset, () -> {
            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(
                            bob.getPrimaryGroup().getUri()
                    ),
                    Permission.ADMIN,
                    adminCtx
            );
        });

        Txn.executeWrite(dataset, () -> {

            dataset.deleteAny(
                    graph,
                    Node.ANY,
                    Node.ANY,
                    Node.ANY,
                    bobCtx
            );
        });

        // ---------------------------------------
        // 6) Verify Alice's triple STILL exists
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            boolean existsAlice = dataset.contains(graph, sAlice, p, oAlice, aliceCtx);
            assertTrue(existsAlice, "Alice's triple must NOT be deleted");
        });

        // ---------------------------------------
        // 7) Verify Bob's triple is deleted
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            boolean existsBob = dataset.contains(graph, sBob, p, oBob, bobCtx);
            assertFalse(existsBob, "Bob's own triple should be deleted");
        });

        // ---------------------------------------
        // 8) Bob now only sees Alice's triple
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            Iterator<Quad> it = dataset.find(
                    graph,
                    Node.ANY,
                    Node.ANY,
                    Node.ANY,
                    bobCtx
            );

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(1, results.size());
            assertEquals(sAlice, results.get(0).getSubject());
        });
    }

    @Test
    void testMissingObjectReadPermissionHidesTriple() {

        Node graph = NodeFactory.createURI("urn:test:graph");

        Node sAlice = NodeFactory.createURI("urn:alice:s");
        Node p = NodeFactory.createURI("urn:p");

        // object is now a URI (important!)
        Node oAlice = NodeFactory.createURI("urn:alice:o");

        // ---------------------------------------
        // 1) Admin creates graph + shares READ
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), adminCtx);

            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(
                            alice.getPrimaryGroup().getUri(),
                            bob.getPrimaryGroup().getUri()
                    ),
                    Permission.EDIT,
                    adminCtx
            );
        });

        // ---------------------------------------
        // 2) Alice inserts triple (S P O[URI])
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.add(graph, sAlice, p, oAlice, aliceCtx);
        });

        // ---------------------------------------
        // 3) Alice shares ONLY subject with Bob
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.shareResources(
                    Set.of(sAlice.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );

            // NOTE: object NOT shared!
        });

        // ---------------------------------------
        // 4) Bob tries to read → sees NOTHING
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            Iterator<Quad> it = dataset.find(
                    graph,
                    Node.ANY,
                    Node.ANY,
                    Node.ANY,
                    bobCtx
            );

            assertFalse(it.hasNext(),
                    "Bob must NOT see the triple because object is not readable");
        });

        // ---------------------------------------
        // 5) Sanity check: Alice still sees it
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            boolean exists = dataset.contains(graph, sAlice, p, oAlice, aliceCtx);
            assertTrue(exists, "Alice should still see her triple");
        });
    }

    @Test
    void testGroupSharingAllowsMultipleUsersToSeeTriple() {

        Node graph = NodeFactory.createURI("urn:test:graph");

        Node sAlice = NodeFactory.createURI("urn:alice:s");
        Node p = NodeFactory.createURI("urn:p");
        Node oAlice = NodeFactory.createURI("urn:alice:o");

        // ---------------------------------------
        // 1) Admin creates graph + shares EDIT with phoenix group
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), adminCtx);

            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(phoenixGroup.getUri(), dataGroup.getUri()),
                    Permission.EDIT,
                    adminCtx
            );
        });

        // ---------------------------------------
        // 2) Alice inserts triple
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.add(graph, sAlice, p, oAlice, aliceCtx);
        });

        // ---------------------------------------
        // 3) Alice shares S and O with phoenix group
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.shareResources(
                    Set.of(sAlice.getURI(), oAlice.getURI()),
                    Set.of(phoenixGroup.getUri()),
                    Permission.READ,
                    aliceCtx
            );
        });

        // ---------------------------------------
        // 4) Bob reads → sees triple
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            Iterator<Quad> it = dataset.find(
                    graph,
                    Node.ANY,
                    Node.ANY,
                    Node.ANY,
                    bobCtx
            );

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(1, results.size());
            assertEquals(sAlice, results.get(0).getSubject());
            assertEquals(oAlice, results.get(0).getObject());
        });

        // ---------------------------------------
        // 5) Charlie reads → also sees triple
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            Iterator<Quad> it = dataset.find(
                    graph,
                    Node.ANY,
                    Node.ANY,
                    Node.ANY,
                    charlieCtx
            );

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(1, results.size());
            assertEquals(sAlice, results.get(0).getSubject());
        });

        // ---------------------------------------
        // 6) David (different group) sees NOTHING
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            Iterator<Quad> it = dataset.find(
                    graph,
                    Node.ANY,
                    Node.ANY,
                    Node.ANY,
                    davidCtx
            );

            assertFalse(it.hasNext(),
                    "User outside phoenix group must not see the triple");
        });
    }

    @Test
    void testCannotExtendSubjectWithReadOnlyAccess() {

        Node graph = NodeFactory.createURI("urn:test:graph");

        Node sAlice = NodeFactory.createURI("urn:alice:s");
        Node p = NodeFactory.createURI("urn:p");

        Node oAlice = NodeFactory.createLiteralString("aliceValue");
        Node oBob = NodeFactory.createLiteralString("bobValue");

        // ---------------------------------------
        // 1) Admin creates graph + shares EDIT
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), adminCtx);

            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(
                            alice.getPrimaryGroup().getUri(),
                            bob.getPrimaryGroup().getUri()
                    ),
                    Permission.EDIT,
                    adminCtx
            );
        });

        // ---------------------------------------
        // 2) Alice creates initial triple
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.add(graph, sAlice, p, oAlice, aliceCtx);
        });

        // ---------------------------------------
        // 3) Alice shares SUBJECT with READ only
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.shareResources(
                    Set.of(sAlice.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );
        });

        // ---------------------------------------
        // 4) Bob tries to extend subject (should FAIL)
        // ---------------------------------------
        assertThrows(PermissionDeniedException.class, () -> {
            Txn.executeWrite(dataset, () -> {
                dataset.add(graph, sAlice, p, oBob, bobCtx);
            });
        });

        // ---------------------------------------
        // 5) Verify original triple still exists
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            boolean exists = dataset.contains(graph, sAlice, p, oAlice, aliceCtx);
            assertTrue(exists, "Alice's original triple must still exist");
        });

        // ---------------------------------------
        // 6) Verify Bob's triple was NOT added
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            boolean exists = dataset.contains(graph, sAlice, p, oBob, aliceCtx);
            assertFalse(exists, "Bob must NOT be able to extend subject with only READ");
        });

        // ---------------------------------------
        // 7) Bob only sees Alice’s original triple
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            Iterator<Quad> it = dataset.find(
                    graph,
                    Node.ANY,
                    Node.ANY,
                    Node.ANY,
                    bobCtx
            );

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(1, results.size());
            assertEquals(oAlice, results.get(0).getObject());
        });
    }

    @Test
    void testReferPermissionDoesNotAllowLiteralModification() {

        Node graph = NodeFactory.createURI("urn:test:graph");

        Node sAlice = NodeFactory.createURI("urn:alice:s");
        Node p = NodeFactory.createURI("urn:p");

        Node oOriginal = NodeFactory.createLiteralString("originalValue");
        Node oModified = NodeFactory.createLiteralString("modifiedValue");

        // ---------------------------------------
        // 1) Admin creates graph + shares EDIT
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), adminCtx);

            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(
                            alice.getPrimaryGroup().getUri(),
                            bob.getPrimaryGroup().getUri()
                    ),
                    Permission.EDIT,
                    adminCtx
            );
        });

        // ---------------------------------------
        // 2) Alice inserts triple
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.add(graph, sAlice, p, oOriginal, aliceCtx);
        });

        // ---------------------------------------
        // 3) Alice shares SUBJECT with Bob as REFER
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.shareResources(
                    Set.of(sAlice.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.REFER,
                    aliceCtx
            );
        });

        // ---------------------------------------
        // 4) Bob attempts to MODIFY literal → must fail
        //    (delete old + add new)
        // ---------------------------------------
        assertThrows(PermissionDeniedException.class, () -> {
            Txn.executeWrite(dataset, () -> {
                dataset.delete(graph, sAlice, p, oOriginal, bobCtx);
            });
        });

        assertThrows(PermissionDeniedException.class, () -> {
            Txn.executeWrite(dataset, () -> {
                dataset.add(graph, sAlice, p, oModified, bobCtx);
            });
        });

        // ---------------------------------------
        // 5) Original triple must still exist
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            boolean exists = dataset.contains(graph, sAlice, p, oOriginal, aliceCtx);
            assertTrue(exists, "Original literal must NOT be modified");
        });

        // ---------------------------------------
        // 6) Modified triple must NOT exist
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            boolean exists = dataset.contains(graph, sAlice, p, oModified, aliceCtx);
            assertFalse(exists, "REFER must not allow literal modification");
        });

        // ---------------------------------------
        // 7) Bob should not see a modified value
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            Iterator<Quad> it = dataset.find(
                    graph,
                    Node.ANY,
                    Node.ANY,
                    Node.ANY,
                    bobCtx
            );

            List<Quad> results = new ArrayList<>();
            it.forEachRemaining(results::add);

            assertEquals(1, results.size());
            assertEquals(oOriginal, results.get(0).getObject());
        });
    }

    @Test
    void testReferPermissionDoesNotAllowCreatingTripleBetweenResources() {

        Node graph = NodeFactory.createURI("urn:test:graph");

        Node sAlice1 = NodeFactory.createURI("urn:alice:s1");
        Node sAlice2 = NodeFactory.createURI("urn:alice:s2");
        Node p = NodeFactory.createURI("urn:p");

        Node o1 = NodeFactory.createLiteralString("value1");
        Node o2 = NodeFactory.createLiteralString("value2");

        // ---------------------------------------
        // 1) Admin creates graph + shares EDIT
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), adminCtx);

            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(
                            alice.getPrimaryGroup().getUri(),
                            bob.getPrimaryGroup().getUri()
                    ),
                    Permission.EDIT,
                    adminCtx
            );
        });

        // ---------------------------------------
        // 2) Alice creates two independent resources
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.add(graph, sAlice1, p, o1, aliceCtx);
            dataset.add(graph, sAlice2, p, o2, aliceCtx);
        });

        // ---------------------------------------
        // 3) Alice shares BOTH resources with Bob as REFER
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.shareResources(
                    Set.of(sAlice1.getURI(), sAlice2.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.REFER,
                    aliceCtx
            );
        });

        // ---------------------------------------
        // 4) Bob tries to create a triple linking them → must FAIL
        //    (sAlice1 p sAlice2)
        // ---------------------------------------
        assertThrows(PermissionDeniedException.class, () -> {
            Txn.executeWrite(dataset, () -> {
                dataset.add(graph, sAlice1, p, sAlice2, bobCtx);
            });
        });

        // ---------------------------------------
        // 5) Verify NO linking triple exists
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            boolean exists = dataset.contains(graph, sAlice1, p, sAlice2, aliceCtx);
            assertFalse(exists, "REFER must not allow creating triples between resources");
        });

        // ---------------------------------------
        // 6) Original triples must still exist
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            assertTrue(dataset.contains(graph, sAlice1, p, o1, aliceCtx));
            assertTrue(dataset.contains(graph, sAlice2, p, o2, aliceCtx));
        });

        // ---------------------------------------
        // 7) Bob cannot see any unintended new triples
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            Iterator<Quad> it = dataset.find(
                    graph,
                    sAlice1,
                    p,
                    sAlice2,
                    bobCtx
            );

            assertFalse(it.hasNext(),
                    "Bob must not be able to create or see a linking triple");
        });
    }

    @Test
    void testEditAllowsLinkingToAndFromReferResource() {

        Node graph = NodeFactory.createURI("urn:test:graph");

        Node sAlice = NodeFactory.createURI("urn:alice:s");   // REFER resource
        Node sBob = NodeFactory.createURI("urn:bob:s");       // Bob-owned / editable
        Node p = NodeFactory.createURI("urn:p");

        Node oAlice = NodeFactory.createLiteralString("aliceValue");

        // ---------------------------------------
        // 1) Admin creates graph + shares EDIT
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), adminCtx);

            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(
                            alice.getPrimaryGroup().getUri(),
                            bob.getPrimaryGroup().getUri()
                    ),
                    Permission.EDIT,
                    adminCtx
            );
        });

        // ---------------------------------------
        // 2) Alice creates a resource
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.add(graph, sAlice, p, oAlice, aliceCtx);
        });

        // ---------------------------------------
        // 3) Bob creates his own resource
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.add(graph, sBob, p, NodeFactory.createLiteralString("bobValue"), bobCtx);
        });

        // ---------------------------------------
        // 4) Alice shares her resource as REFER
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.shareResources(
                    Set.of(sAlice.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.REFER,
                    aliceCtx
            );
        });

        // ---------------------------------------
        // 5) Bob creates links TO and FROM REFER node
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            // Bob → Alice (object position)
            dataset.add(graph, sBob, p, sAlice, bobCtx);

            // Alice → Bob (subject position)
            dataset.add(graph, sAlice, p, sBob, bobCtx);
        });

        // ---------------------------------------
        // 6) Verify links exist
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            assertTrue(dataset.contains(graph, sBob, p, sAlice, bobCtx),
                    "Bob should be able to link TO REFER resource");

            assertTrue(dataset.contains(graph, sAlice, p, sBob, bobCtx),
                    "Bob should be able to link FROM REFER resource");
        });

        // ---------------------------------------
        // 7) Bob deletes both links
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.delete(graph, sBob, p, sAlice, bobCtx);
            dataset.delete(graph, sAlice, p, sBob, bobCtx);
        });

        // ---------------------------------------
        // 8) Verify links are removed
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            assertFalse(dataset.contains(graph, sBob, p, sAlice, bobCtx),
                    "Link TO REFER resource should be deletable");

            assertFalse(dataset.contains(graph, sAlice, p, sBob, bobCtx),
                    "Link FROM REFER resource should be deletable");
        });

        // ---------------------------------------
        // 9) Original Alice triple still intact
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            assertTrue(dataset.contains(graph, sAlice, p, oAlice, aliceCtx),
                    "REFER must not affect original data");
        });
    }
}
