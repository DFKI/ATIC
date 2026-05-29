package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.Permission;
import de.dfki.sds.atic.ac.PermissionDeniedException;
import de.dfki.sds.atic.ac.PrincipalPermission;
import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.TxnType;
import org.apache.jena.sparql.graph.GraphFactory;
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
public class GraphSharingUnitTest {

    private SqliteAticDatasetGraph dataset;

    @BeforeEach
    void setup(@TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);
    }

    @Test
    void testUserHasAccessToDefaultGraphViaEveryoneGroup() {

        String username = "alice";

        // --- create user ---
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser(
                    "Alice",
                    "Smith",
                    "alice@example.org",
                    username,
                    InvocationContext.EMPTY
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // --- load user ---
        User user;
        dataset.begin(TxnType.READ);
        try {
            user = dataset.getUser(username, InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }

        assertNotNull(user);

        // Invocation context for that user
        InvocationContext userCtx = new InvocationContext.Builder().fromUser(user).build();

        // --- verify access to default graph ---
        dataset.begin(TxnType.READ);
        try {

            AticGraph graph = dataset.getDefaultGraph(userCtx);

            assertNotNull(graph, "User in EVERYONE group should have access to default graph");

        } finally {
            dataset.end();
        }
    }

    @Test
    void shareGraphCreatesCorrectAclEntry() throws Exception {

        // load extra user and its group info
        dataset.begin(TxnType.READ);
        User adminUser = null;
        try {
            adminUser = dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }
        assertNotNull(adminUser, "Extra user should have been created");

        // Arrange: admin and extra user
        InvocationContext adminCtx = new InvocationContext.Builder().fromUser(adminUser).build();

        // create extra user
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "extraUser", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // load extra user
        User extraUser;
        dataset.begin(TxnType.READ);
        try {
            extraUser = dataset.getUser("extraUser", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }
        assertNotNull(extraUser, "Extra user should exist");

        String testGraphUri = "urn:test:sharedGraphOnlyAcl";
        Node testGraphNode = NodeFactory.createURI(testGraphUri);

        // admin adds named graph
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(testGraphNode,
                    org.apache.jena.sparql.graph.GraphFactory.createDefaultGraph(),
                    adminCtx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // Act: share graph with READ permission to the extra user's group
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareGraphs(
                    Set.of(testGraphUri),
                    Set.of(extraUser.getPrimaryGroup().getUri()),
                    Permission.READ,
                    adminCtx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // Assert: check that graph_acl contains the correct entry
        dataset.begin(TxnType.READ);
        try {
            boolean aclEntryExists = dataset.getDatabase().read(
                    "SELECT EXISTS(SELECT 1 FROM graph_acl a "
                    + "JOIN \"group\" g ON a.group_id = g.id "
                    + "JOIN graph gr ON a.graph_id = gr.id "
                    + "WHERE gr.uri = ? AND g.uri = ? AND a.permission = ?)",
                    rs -> {
                        rs.next();
                        return rs.getInt(1) == 1;
                    },
                    testGraphUri,
                    extraUser.getPrimaryGroup().getUri(),
                    Permission.READ.getCode()
            );

            assertTrue(aclEntryExists,
                    "graph_acl should contain an entry with the shared graph, group, and READ permission");

        } finally {
            dataset.end();
        }
    }

    @Test
    void adminSharesGraphWithExtraUserAndItBecomesVisible() throws Exception {

        // 1) create extra user and its group
        // username "user2", group "user2" (one group per user)
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "user2", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // load extra user and its group info
        dataset.begin(TxnType.READ);
        User extraUser = null;
        try {
            extraUser = dataset.getUser("user2", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }
        assertNotNull(extraUser, "Extra user should have been created");

        // build invocation context for extra user
        InvocationContext extraCtx = new InvocationContext.Builder().fromUser(extraUser).build();

        // load extra user and its group info
        dataset.begin(TxnType.READ);
        User adminUser = null;
        try {
            adminUser = dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }
        assertNotNull(adminUser, "Extra user should have been created");

        // Arrange: admin and extra user
        InvocationContext adminCtx = new InvocationContext.Builder().fromUser(adminUser).build();

        // 2) admin creates a new named graph
        String graphUri = "urn:test:sharedGraph";
        Node graphNode = NodeFactory.createURI(graphUri);

        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graphNode,
                    GraphFactory.createDefaultGraph(),
                    adminCtx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // verify that extra user DOES NOT see it yet
        dataset.begin(TxnType.READ);
        try {
            assertFalse(dataset.containsGraph(graphNode, extraCtx),
                    "Extra user should NOT see the graph before it is shared");
        } finally {
            dataset.end();
        }

        // 3) admin shares graph with extra user’s group with READ permission
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareGraphs(
                    Set.of(graphUri),
                    Set.of(extraUser.getPrimaryGroup().getUri()),
                    Permission.READ,
                    adminCtx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // 4) now the extra user should see the graph
        dataset.begin(TxnType.READ);
        try {
            assertTrue(dataset.containsGraph(graphNode, extraCtx),
                    "Extra user should see shared graph via containsGraph");

            List<String> visibleUris = new ArrayList<>();
            Iterator<Node> iter = dataset.listGraphNodes(extraCtx);
            iter.forEachRemaining(n -> visibleUris.add(n.getURI()));
            assertTrue(visibleUris.contains(graphUri),
                    "Extra user should see graph via listGraphNodes");
        } finally {
            dataset.end();
        }
    }

    @Test
    void testUserDoesNotSeeOthersGraphUntilShared() throws Exception {

        // --- Create user1 ---
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "user1", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        User user1;
        dataset.begin(TxnType.READ);
        try {
            user1 = dataset.getUser("user1", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }
        assertNotNull(user1);

        InvocationContext user1Ctx = new InvocationContext.Builder()
                .userId(user1.getId())
                .primaryGroupId(user1.getPrimaryGroup().getId())
                .groupIds(Set.of(user1.getPrimaryGroup().getId()))
                .build();

        // --- Create user2 ---
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "user2", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        User user2;
        dataset.begin(TxnType.READ);
        try {
            user2 = dataset.getUser("user2", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }
        assertNotNull(user2);

        InvocationContext user2Ctx = new InvocationContext.Builder()
                .userId(user2.getId())
                .primaryGroupId(user2.getPrimaryGroup().getId())
                .groupIds(Set.of(user2.getPrimaryGroup().getId()))
                .build();

        // --- user1 creates own graph ---
        String user1GraphUri = "urn:test:user1Graph";
        Node user1GraphNode = NodeFactory.createURI(user1GraphUri);

        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(user1GraphNode,
                    org.apache.jena.sparql.graph.GraphFactory.createDefaultGraph(),
                    user1Ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // --- user2 creates own graph ---
        String user2GraphUri = "urn:test:user2Graph";
        Node user2GraphNode = NodeFactory.createURI(user2GraphUri);

        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(user2GraphNode,
                    org.apache.jena.sparql.graph.GraphFactory.createDefaultGraph(),
                    user2Ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // --- initially, user1 should NOT see user2's graph ---
        dataset.begin(TxnType.READ);
        try {
            assertFalse(dataset.containsGraph(user2GraphNode, user1Ctx),
                    "user1 should not see user2's graph before sharing");
        } finally {
            dataset.end();
        }

        // user2 should likewise NOT see user1's graph
        dataset.begin(TxnType.READ);
        try {
            assertFalse(dataset.containsGraph(user1GraphNode, user2Ctx),
                    "user2 should not see user1's graph before sharing");
        } finally {
            dataset.end();
        }

        // --- user1 shares their graph with user2's group at READ permission ---
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareGraphs(
                    Set.of(user1GraphUri),
                    Set.of(user2.getPrimaryGroup().getUri()),
                    Permission.READ,
                    user1Ctx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // --- now user2 should see user1's graph ---
        dataset.begin(TxnType.READ);
        try {
            assertTrue(dataset.containsGraph(user1GraphNode, user2Ctx),
                    "user2 should see user1's graph after share");
        } finally {
            dataset.end();
        }
    }

    @Test
    void userWithReadCannotShareEditToAnotherUser(@TempDir Path tempDir) throws Exception {

        // --- Create User1 ---
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "user1", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        User user1;
        dataset.begin(TxnType.READ);
        try {
            user1 = dataset.getUser("user1", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }
        assertNotNull(user1);

        InvocationContext user1Ctx = new InvocationContext.Builder()
                .userId(user1.getId())
                .primaryGroupId(user1.getPrimaryGroup().getId())
                .groupIds(Set.of(user1.getPrimaryGroup().getId()))
                .build();

        // --- Create User2 ---
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "user2", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        User user2;
        dataset.begin(TxnType.READ);
        try {
            user2 = dataset.getUser("user2", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }
        assertNotNull(user2);

        InvocationContext user2Ctx = new InvocationContext.Builder()
                .userId(user2.getId())
                .primaryGroupId(user2.getPrimaryGroup().getId())
                .groupIds(Set.of(user2.getPrimaryGroup().getId()))
                .build();

        // --- Create User3 ---
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "user3", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        User user3;
        dataset.begin(TxnType.READ);
        try {
            user3 = dataset.getUser("user3", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }
        assertNotNull(user3);

        InvocationContext user3Ctx = new InvocationContext.Builder()
                .userId(user3.getId())
                .primaryGroupId(user3.getPrimaryGroup().getId())
                .groupIds(Set.of(user3.getPrimaryGroup().getId()))
                .build();

        // --- User1 creates a graph ---
        String user1GraphUri = "urn:test:user1GraphForShare";
        Node user1GraphNode = NodeFactory.createURI(user1GraphUri);

        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(user1GraphNode,
                    org.apache.jena.sparql.graph.GraphFactory.createDefaultGraph(),
                    user1Ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // --- User1 shares READ permission with user2 ---
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareGraphs(
                    Set.of(user1GraphUri),
                    Set.of(user2.getPrimaryGroup().getUri()),
                    Permission.READ,
                    user1Ctx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // --- Now user2 HAS only READ permission ---
        // user2 tries to share EDIT permission with user3 — should be denied
        dataset.begin(TxnType.WRITE);
        try {
            assertThrows(
                    PermissionDeniedException.class,
                    () -> dataset.shareGraphs(
                            Set.of(user1GraphUri),
                            Set.of(user3.getPrimaryGroup().getUri()),
                            Permission.EDIT,
                            user2Ctx
                    ),
                    "User2 with only READ permission should not be able to grant EDIT"
            );
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    @Test
    void adminCannotReduceOwnPermission() throws Exception {
        // create user and graph
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "u1", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        User u1;
        dataset.begin(TxnType.READ);
        try {
            u1 = dataset.getUser("u1", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }

        InvocationContext u1Ctx = new InvocationContext.Builder()
                .userId(u1.getId())
                .primaryGroupId(u1.getPrimaryGroup().getId())
                .groupIds(Set.of(u1.getPrimaryGroup().getId()))
                .build();

        String graphUri = "urn:test:adminGraph";
        Node graphNode = NodeFactory.createURI(graphUri);

        // u1 creates own graph
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graphNode, GraphFactory.createDefaultGraph(), u1Ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // Check u1 has ADMIN on own graph
        dataset.begin(TxnType.READ);
        try {
            assertTrue(dataset.containsGraph(graphNode, u1Ctx));
        } finally {
            dataset.end();
        }

        // u1 attempts to reduce own ADMIN permission
        dataset.begin(TxnType.WRITE);
        try {
            assertThrows(PermissionDeniedException.class,
                    () -> dataset.shareGraphs(
                            Set.of(graphUri),
                            Set.of(u1.getPrimaryGroup().getUri()),
                            Permission.EDIT,
                            u1Ctx
                    ),
                    "User should not be able to lower own ADMIN permission"
            );
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    @Test
    void unshareGraphRemovesAclAndMakesGraphInvisible() throws Exception {

        dataset.begin(TxnType.READ);
        User adminUser = null;
        try {
            adminUser = dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }
        assertNotNull(adminUser, "Extra user should have been created");

        InvocationContext adminCtx = new InvocationContext.Builder().fromUser(adminUser).build();

        // create extra user
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "user3", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // load extra user
        User extraUser;
        dataset.begin(TxnType.READ);
        try {
            extraUser = dataset.getUser("user3", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }
        assertNotNull(extraUser, "Extra user should exist");

        InvocationContext extraCtx = new InvocationContext.Builder()
                .userId(extraUser.getId())
                .primaryGroupId(extraUser.getPrimaryGroup().getId())
                .groupIds(Set.of(extraUser.getPrimaryGroup().getId()))
                .build();

        String graphUri = "urn:test:unshareGraph";
        Node graphNode = NodeFactory.createURI(graphUri);

        // admin creates a new named graph
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graphNode,
                    GraphFactory.createDefaultGraph(),
                    adminCtx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // before share, extra user should *not* see it
        dataset.begin(TxnType.READ);
        try {
            assertFalse(dataset.containsGraph(graphNode, extraCtx),
                    "Extra user should NOT see graph before sharing");
        } finally {
            dataset.end();
        }

        // share the graph with READ permission
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareGraphs(
                    Set.of(graphUri),
                    Set.of(extraUser.getPrimaryGroup().getUri()),
                    Permission.READ,
                    adminCtx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // after share, extra user should see it
        dataset.begin(TxnType.READ);
        try {
            assertTrue(dataset.containsGraph(graphNode, extraCtx),
                    "Extra user should see graph after sharing");
        } finally {
            dataset.end();
        }

        // now revoke/unshare
        dataset.begin(TxnType.WRITE);
        try {
            dataset.unshareGraphs(
                    Set.of(graphUri),
                    Set.of(extraUser.getPrimaryGroup().getUri()),
                    adminCtx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // after unshare, extra user should no longer see the graph
        dataset.begin(TxnType.READ);
        try {
            assertFalse(dataset.containsGraph(graphNode, extraCtx),
                    "Extra user should NOT see the graph after unsharing");

            List<String> visibleUris = new ArrayList<>();
            Iterator<Node> it = dataset.listGraphNodes(extraCtx);
            it.forEachRemaining(n -> visibleUris.add(n.getURI()));

            assertFalse(visibleUris.contains(graphUri),
                    "Graph URI should NOT be present for extra user after revoke");

        } finally {
            dataset.end();
        }
    }

    @Test
    void shareAndUnshareGraphReflectsInPrincipalPermissions() throws Exception {

        dataset.begin(TxnType.READ);
        User adminUser = null;
        try {
            adminUser = dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }
        assertNotNull(adminUser);

        InvocationContext adminCtx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();

        // create extra user
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "user3", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        User extraUser;
        dataset.begin(TxnType.READ);
        try {
            extraUser = dataset.getUser("user3", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }
        assertNotNull(extraUser);

        InvocationContext extraCtx = new InvocationContext.Builder()
                .userId(extraUser.getId())
                .primaryGroupId(extraUser.getPrimaryGroup().getId())
                .groupIds(Set.of(extraUser.getPrimaryGroup().getId()))
                .build();

        String graphUri = "urn:test:unshareGraph";
        Node graphNode = NodeFactory.createURI(graphUri);

        // create graph
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graphNode,
                    GraphFactory.createDefaultGraph(),
                    adminCtx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // BEFORE share → no permissions
        dataset.begin(TxnType.READ);
        try {
            Map<String, List<PrincipalPermission>> perms
                    = dataset.listPrincipalPermissions(Set.of(graphUri), true, extraCtx);

            assertTrue(perms.isEmpty(),
                    "No permissions should exist before sharing");
        } finally {
            dataset.end();
        }

        // SHARE (READ)
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareGraphs(
                    Set.of(graphUri),
                    Set.of(extraUser.getPrimaryGroup().getUri()),
                    Permission.READ,
                    adminCtx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // AFTER share → READ permission exists
        dataset.begin(TxnType.READ);
        try {
            Map<String, List<PrincipalPermission>> perms
                    = dataset.listPrincipalPermissions(Set.of(graphUri), true, extraCtx);

            assertFalse(perms.isEmpty(), "Permissions should exist after sharing");

            List<PrincipalPermission> list = perms.get(graphUri);
            assertNotNull(list);

            boolean hasRead = list.stream().anyMatch(pp
                    -> pp.getPrincipal().getName().equals(extraUser.getName())
                    && pp.getPermission() == Permission.READ
            );

            assertTrue(hasRead,
                    "Extra user's group should have READ permission after sharing");
        } finally {
            dataset.end();
        }

        // UNSHARE
        dataset.begin(TxnType.WRITE);
        try {
            dataset.unshareGraphs(
                    Set.of(graphUri),
                    Set.of(extraUser.getPrimaryGroup().getUri()),
                    adminCtx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // AFTER unshare → permission removed
        dataset.begin(TxnType.READ);
        try {
            Map<String, List<PrincipalPermission>> perms
                    = dataset.listPrincipalPermissions(Set.of(graphUri), true, extraCtx);

            List<PrincipalPermission> list = perms.get(graphUri);

            if (list != null) {
                boolean stillHasAccess = list.stream().anyMatch(pp
                        -> pp.getPrincipal().getName().equals(extraUser.getName())
                );

                assertFalse(stillHasAccess,
                        "Permissions should be removed after unsharing");
            }

        } finally {
            dataset.end();
        }
    }

    @Test
    void sharedGraphPermissionsAreVisibleToRecipient() throws Exception {

        // load admin
        dataset.begin(TxnType.READ);
        User adminUser;
        try {
            adminUser = dataset.getUser(
                    UserGroupManagement.ADMIN_USERNAME,
                    InvocationContext.EMPTY
            );
        } finally {
            dataset.end();
        }

        assertNotNull(adminUser);

        InvocationContext adminCtx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();

        // create alice + bob
        dataset.begin(TxnType.WRITE);
        try {

            dataset.addUser("", "", "", "alice", InvocationContext.EMPTY);
            dataset.addUser("", "", "", "bob", InvocationContext.EMPTY);

            dataset.commit();

        } finally {
            dataset.end();
        }

        // load alice + bob
        User alice;
        User bob;

        dataset.begin(TxnType.READ);
        try {

            alice = dataset.getUser("alice", InvocationContext.EMPTY);
            bob = dataset.getUser("bob", InvocationContext.EMPTY);

        } finally {
            dataset.end();
        }

        assertNotNull(alice);
        assertNotNull(bob);

        InvocationContext aliceCtx = new InvocationContext.Builder()
                .userId(alice.getId())
                .primaryGroupId(alice.getPrimaryGroup().getId())
                .groupIds(Set.of(alice.getPrimaryGroup().getId()))
                .build();

        InvocationContext bobCtx = new InvocationContext.Builder()
                .userId(bob.getId())
                .primaryGroupId(bob.getPrimaryGroup().getId())
                .groupIds(Set.of(bob.getPrimaryGroup().getId()))
                .build();

        String graphUri = "urn:test:aliceGraph";
        Node graphNode = NodeFactory.createURI(graphUri);

        // alice creates graph
        dataset.begin(TxnType.WRITE);
        try {

            dataset.addGraph(
                    graphNode,
                    GraphFactory.createDefaultGraph(),
                    aliceCtx
            );

            dataset.commit();

        } finally {
            dataset.end();
        }

        // alice shares graph with bob
        dataset.begin(TxnType.WRITE);
        try {

            dataset.shareGraphs(
                    Set.of(graphUri),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.READ,
                    aliceCtx
            );

            dataset.commit();

        } finally {
            dataset.end();
        }

        // bob asks for permissions
        dataset.begin(TxnType.READ);
        try {

            Map<String, List<PrincipalPermission>> perms
                    = dataset.listPrincipalPermissions(
                            Set.of(graphUri),
                            true,
                            bobCtx
                    );

            assertFalse(perms.isEmpty());

            List<PrincipalPermission> list = perms.get(graphUri);

            assertNotNull(list);

            // bob should see HIS permission
            boolean bobHasRead = list.stream().anyMatch(pp
                    -> pp.getPrincipal().getName().equals(bob.getName())
                    && pp.getPermission() == Permission.READ
            );

            assertTrue(
                    bobHasRead,
                    "Bob should see his READ permission"
            );

            // bob should ALSO see alice permission
            boolean alicePresent = list.stream().anyMatch(pp
                    -> pp.getPrincipal().getName().equals(alice.getName())
            );

            assertTrue(
                    alicePresent,
                    "Bob should also see Alice permissions"
            );

        } finally {
            dataset.end();
        }
    }

    @Test
    void userCannotUnshareOwnGraphPermission() throws Exception {

        // create user
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "alice", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // load user
        User alice;

        dataset.begin(TxnType.READ);
        try {
            alice = dataset.getUser("alice", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }

        assertNotNull(alice);

        InvocationContext aliceCtx = new InvocationContext.Builder()
                .userId(alice.getId())
                .primaryGroupId(alice.getPrimaryGroup().getId())
                .groupIds(Set.of(alice.getPrimaryGroup().getId()))
                .build();

        String graphUri = "urn:test:cannotUnshareOwnGraph";
        Node graphNode = NodeFactory.createURI(graphUri);

        // alice creates graph
        dataset.begin(TxnType.WRITE);
        try {

            dataset.addGraph(
                    graphNode,
                    GraphFactory.createDefaultGraph(),
                    aliceCtx
            );

            dataset.commit();

        } finally {
            dataset.end();
        }

        // sanity: alice can see own graph
        dataset.begin(TxnType.READ);
        try {

            assertTrue(
                    dataset.containsGraph(graphNode, aliceCtx),
                    "Alice should see her own graph"
            );

        } finally {
            dataset.end();
        }

        // alice attempts to unshare her OWN permission
        IllegalArgumentException thrown;

        dataset.begin(TxnType.WRITE);
        try {

            thrown = assertThrows(
                    IllegalArgumentException.class,
                    () -> dataset.unshareGraphs(
                            Set.of(graphUri),
                            Set.of(alice.getPrimaryGroup().getUri()),
                            aliceCtx
                    ),
                    "User should not be allowed to remove their own graph permission"
            );

            dataset.commit();

        } finally {
            dataset.end();
        }

        // optional message assertion
        assertTrue(
                thrown.getMessage().contains(alice.getPrimaryGroup().getUri()),
                "Exception message should reference group uri"
        );

        // graph should still remain visible
        dataset.begin(TxnType.READ);
        try {

            assertTrue(
                    dataset.containsGraph(graphNode, aliceCtx),
                    "Graph should still be visible after failed self-unshare"
            );

        } finally {
            dataset.end();
        }
    }

    @Test
    void unshareCascadeDoesNotRevokeDownstreamShares() throws Exception {

        // 1) Create users user2 and user3
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "user2", InvocationContext.EMPTY);
            dataset.addUser("", "", "", "user3", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // load users
        User user2, user3;
        dataset.begin(TxnType.READ);
        try {
            user2 = dataset.getUser("user2", InvocationContext.EMPTY);
            user3 = dataset.getUser("user3", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }

        assertNotNull(user2);
        assertNotNull(user3);

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext user1Ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        InvocationContext user2Ctx = new InvocationContext.Builder()
                .userId(user2.getId())
                .primaryGroupId(user2.getPrimaryGroup().getId())
                .groupIds(Set.of(user2.getPrimaryGroup().getId()))
                .build();

        InvocationContext user3Ctx = new InvocationContext.Builder()
                .userId(user3.getId())
                .primaryGroupId(user3.getPrimaryGroup().getId())
                .groupIds(Set.of(user3.getPrimaryGroup().getId()))
                .build();

        String graphUri = "urn:test:collaborativeCascade";
        Node graphNode = NodeFactory.createURI(graphUri);

        // 2) user1 creates graph
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graphNode,
                    GraphFactory.createDefaultGraph(),
                    user1Ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // 3) user1 shares graph to user2
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareGraphs(
                    Set.of(graphUri),
                    Set.of(user2.getPrimaryGroup().getUri()),
                    Permission.READ,
                    user1Ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // user2 now shares to user3
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareGraphs(
                    Set.of(graphUri),
                    Set.of(user3.getPrimaryGroup().getUri()),
                    Permission.READ,
                    user2Ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // Assert: user3 can see graph
        dataset.begin(TxnType.READ);
        try {
            assertTrue(dataset.containsGraph(graphNode, user3Ctx),
                    "User3 should see graph because user2 shared it");
        } finally {
            dataset.end();
        }

        // 4) user1 unshares from user2
        dataset.begin(TxnType.WRITE);
        try {
            dataset.unshareGraphs(
                    Set.of(graphUri),
                    Set.of(user2.getPrimaryGroup().getUri()),
                    user1Ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // Assert: user2 loses visibility...
        dataset.begin(TxnType.READ);
        try {
            assertFalse(dataset.containsGraph(graphNode, user2Ctx),
                    "User2 should no longer see graph after unsharing");
        } finally {
            dataset.end();
        }

        // ...but user3 still sees it
        dataset.begin(TxnType.READ);
        try {
            assertTrue(dataset.containsGraph(graphNode, user3Ctx),
                    "User3 should still see the graph because its share came from user2");
        } finally {
            dataset.end();
        }
    }

    @Test
    void user2CannotUnshareGraphThatUser1Granted() throws Exception {

        // Create user2 and load invocation contexts
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "user2", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        User user2;
        dataset.begin(TxnType.READ);
        try {
            user2 = dataset.getUser("user2", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }
        assertNotNull(user2);

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext user1Ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        InvocationContext user2Ctx = new InvocationContext.Builder()
                .userId(user2.getId())
                .primaryGroupId(user2.getPrimaryGroup().getId())
                .groupIds(Set.of(user2.getPrimaryGroup().getId()))
                .build();

        String graphUri = "urn:test:forbiddenUnshare";
        Node graphNode = NodeFactory.createURI(graphUri);

        // User1 creates a graph
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graphNode,
                    GraphFactory.createDefaultGraph(),
                    user1Ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // User1 shares with user2
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareGraphs(
                    Set.of(graphUri),
                    Set.of(user2.getPrimaryGroup().getUri()),
                    Permission.READ,
                    user1Ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // Sanity: user2 *can* see graph before attempting unshare
        dataset.begin(TxnType.READ);
        try {
            assertTrue(dataset.containsGraph(graphNode, user2Ctx),
                    "User2 should see the graph after it was shared by user1");
        } finally {
            dataset.end();
        }

        // Attempt: user2 tries to revoke the share
        IllegalArgumentException thrown;
        dataset.begin(TxnType.WRITE);
        try {
            thrown = assertThrows(
                    IllegalArgumentException.class,
                    () -> {
                        dataset.unshareGraphs(
                                Set.of(graphUri),
                                Set.of(user2.getPrimaryGroup().getUri()),
                                user2Ctx
                        );
                    },
                    "User2 should not be allowed to unshare a permission granted by user1"
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // Assert message context (optional)
        assertTrue(thrown.getMessage().contains(user2.getPrimaryGroup().getUri()),
                "PermissionDeniedException should refer to graph");

        // ACL remains: user2 still sees it
        dataset.begin(TxnType.READ);
        try {
            assertTrue(dataset.containsGraph(graphNode, user2Ctx),
                    "After failed unshare, user2 should still see the graph");
        } finally {
            dataset.end();
        }
    }

    @Test
    void user2CannotLowerOwnEditPermissionByShareGraphs() throws Exception {

        // 1) Create user2
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "user2", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // load user2
        User user2;
        dataset.begin(TxnType.READ);
        try {
            user2 = dataset.getUser("user2", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }
        assertNotNull(user2);

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext user1Ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        InvocationContext user2Ctx = new InvocationContext.Builder()
                .userId(user2.getId())
                .primaryGroupId(user2.getPrimaryGroup().getId())
                .groupIds(Set.of(user2.getPrimaryGroup().getId()))
                .build();

        String graphUri = "urn:test:cannotLowerPermission";
        Node graphNode = NodeFactory.createURI(graphUri);

        // 2) user1 creates graph
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graphNode,
                    GraphFactory.createDefaultGraph(),
                    user1Ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // 3) user1 grants EDIT to user2
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareGraphs(
                    Set.of(graphUri),
                    Set.of(user2.getPrimaryGroup().getUri()),
                    Permission.EDIT,
                    user1Ctx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // Sanity: user2 does have edit access
        dataset.begin(TxnType.READ);
        try {
            assertTrue(dataset.containsGraph(graphNode, user2Ctx),
                    "User2 should see the graph after being granted EDIT permission");
        } finally {
            dataset.end();
        }

        // 4) Attempt: user2 tries to lower its own permission from EDIT to READ
        dataset.begin(TxnType.WRITE);
        try {
            PermissionDeniedException thrown = assertThrows(
                    PermissionDeniedException.class,
                    () -> {
                        dataset.shareGraphs(
                                Set.of(graphUri),
                                Set.of(user2.getPrimaryGroup().getUri()),
                                Permission.READ,
                                user2Ctx
                        );
                    },
                    "User2 should not be allowed to lower its own EDIT permission to READ"
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // ACL remains: user2 still has full EDIT access
        dataset.begin(TxnType.READ);
        try {
            boolean stillEdit = dataset.getDatabase().read(
                    "SELECT EXISTS(SELECT 1 FROM graph_acl a "
                    + "JOIN \"group\" g ON a.group_id = g.id "
                    + "JOIN graph gr ON a.graph_id = gr.id "
                    + "WHERE gr.uri = ? AND g.uri = ? AND a.permission = ?)",
                    rs -> {
                        rs.next();
                        return rs.getInt(1) == 1;
                    },
                    graphUri,
                    user2.getPrimaryGroup().getUri(),
                    Permission.EDIT.getCode()
            );
            assertTrue(stillEdit, "User2 should still have EDIT permission after failed downgrade");
        } finally {
            dataset.end();
        }
    }

    @Test
    void cannotGrantHigherPermissionThanYouHave() throws Exception {

        // set up user2
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "user2", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        User user2;
        dataset.begin(TxnType.READ);
        try {
            user2 = dataset.getUser("user2", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext user1Ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        InvocationContext user2Ctx = new InvocationContext.Builder()
                .userId(user2.getId())
                .primaryGroupId(user2.getPrimaryGroup().getId())
                .groupIds(Set.of(user2.getPrimaryGroup().getId()))
                .build();

        String graphUri = "urn:test:insufficientGrant";
        Node graphNode = NodeFactory.createURI(graphUri);

        // user1 creates and grants READ to user2
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graphNode, GraphFactory.createDefaultGraph(), user1Ctx);
            dataset.shareGraphs(
                    Set.of(graphUri),
                    Set.of(user2.getPrimaryGroup().getUri()),
                    Permission.READ,
                    user1Ctx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // user2 tries to grant EDIT
        dataset.begin(TxnType.WRITE);
        try {
            assertThrows(PermissionDeniedException.class, () -> {
                dataset.shareGraphs(
                        Set.of(graphUri),
                        Set.of(user2.getPrimaryGroup().getUri()),
                        Permission.EDIT,
                        user2Ctx
                );
            });
            dataset.commit();
        } finally {
            dataset.end();
        }

        // verify user2 still only has READ
        dataset.begin(TxnType.READ);
        try {
            boolean onlyRead = dataset.getDatabase().read(
                    "SELECT EXISTS(SELECT 1 FROM graph_acl a "
                    + "JOIN \"group\" g ON a.group_id=g.id "
                    + "JOIN graph gr ON a.graph_id=gr.id "
                    + "WHERE gr.uri=? AND g.uri=? AND a.permission=?)",
                    rs -> {
                        rs.next();
                        return rs.getInt(1) == 1;
                    },
                    graphUri,
                    user2.getPrimaryGroup().getUri(),
                    Permission.READ.getCode()
            );
            assertTrue(onlyRead);
        } finally {
            dataset.end();
        }
    }

    @Test
    void unshareDoesNotRemoveOtherGroupGrantsWhenBothGrantProperly() throws Exception {

        // -----------------------
        // Arrange: create user2 and userX
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "user2", InvocationContext.EMPTY);
            dataset.addUser("", "", "", "userX", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        User user2, userX;
        dataset.begin(TxnType.READ);
        try {
            user2 = dataset.getUser("user2", InvocationContext.EMPTY);
            userX = dataset.getUser("userX", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext user1Ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        String graphUri = "urn:test:jointGrants";
        Node graphNode = NodeFactory.createURI(graphUri);

        // 1) user1 creates the graph
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graphNode,
                    GraphFactory.createDefaultGraph(),
                    user1Ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // Give userX read from user1 so userX can share later
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareGraphs(
                    Set.of(graphUri),
                    Set.of(userX.getPrimaryGroup().getUri()),
                    Permission.READ,
                    user1Ctx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // Build userX's context
        InvocationContext userXCtx = new InvocationContext.Builder()
                .userId(userX.getId())
                .primaryGroupId(userX.getPrimaryGroup().getId())
                .groupIds(Set.of(userX.getPrimaryGroup().getId()))
                .build();

        // -----------------------
        // Act: user1 and userX share READ with user2
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareGraphs(
                    Set.of(graphUri),
                    Set.of(user2.getPrimaryGroup().getUri()),
                    Permission.READ,
                    user1Ctx
            );
            dataset.shareGraphs(
                    Set.of(graphUri),
                    Set.of(user2.getPrimaryGroup().getUri()),
                    Permission.READ,
                    userXCtx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        InvocationContext user2Ctx = new InvocationContext.Builder()
                .userId(user2.getId())
                .primaryGroupId(user2.getPrimaryGroup().getId())
                .groupIds(Set.of(user2.getPrimaryGroup().getId()))
                .build();

        dataset.begin(TxnType.READ);
        try {
            assertTrue(dataset.containsGraph(graphNode, user2Ctx),
                    "User2 should have READ because userX and user1 granted it");
        } finally {
            dataset.end();
        }

        // -----------------------
        // user1 unshares
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.unshareGraphs(
                    Set.of(graphUri),
                    Set.of(user2.getPrimaryGroup().getUri()),
                    user1Ctx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Assert: user2 still has READ via userX's grant
        // -----------------------
        dataset.begin(TxnType.READ);
        try {
            assertTrue(dataset.containsGraph(graphNode, user2Ctx),
                    "User2 should retain READ because userX also granted it");
        } finally {
            dataset.end();
        }
    }

    @Test
    void repeatedShareUnshareIsIdempotent() throws Exception {

        // create user2
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "user2", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        User user2;
        dataset.begin(TxnType.READ);
        try {
            user2 = dataset.getUser("user2", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext user1Ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        InvocationContext user2Ctx = new InvocationContext.Builder()
                .userId(user2.getId())
                .primaryGroupId(user2.getPrimaryGroup().getId())
                .groupIds(Set.of(user2.getPrimaryGroup().getId()))
                .build();

        String graphUri = "urn:test:idempotent";
        Node graphNode = NodeFactory.createURI(graphUri);

        // create graph
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graphNode, GraphFactory.createDefaultGraph(), user1Ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // 1st share
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareGraphs(
                    Set.of(graphUri),
                    Set.of(user2.getPrimaryGroup().getUri()),
                    Permission.READ,
                    user1Ctx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // 1st unshare
        dataset.begin(TxnType.WRITE);
        try {
            dataset.unshareGraphs(
                    Set.of(graphUri),
                    Set.of(user2.getPrimaryGroup().getUri()),
                    user1Ctx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // 2nd unshare (should silently do nothing)
        dataset.begin(TxnType.WRITE);
        try {
            dataset.unshareGraphs(
                    Set.of(graphUri),
                    Set.of(user2.getPrimaryGroup().getUri()),
                    user1Ctx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // share again
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareGraphs(
                    Set.of(graphUri),
                    Set.of(user2.getPrimaryGroup().getUri()),
                    Permission.READ,
                    user1Ctx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // user2 sees graph
        dataset.begin(TxnType.READ);
        try {
            assertTrue(dataset.containsGraph(graphNode, user2Ctx));
        } finally {
            dataset.end();
        }
    }

    @Test
    void unshareNonExistentShareIsNoOp() throws Exception {

        // set up user2
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "user2", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        User user2;
        dataset.begin(TxnType.READ);
        try {
            user2 = dataset.getUser("user2", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext user1Ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        String graphUri = "urn:test:noShare";
        Node graphNode = NodeFactory.createURI(graphUri);

        // create graph
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graphNode, GraphFactory.createDefaultGraph(), user1Ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // unshare without prior share
        dataset.begin(TxnType.WRITE);
        try {
            dataset.unshareGraphs(
                    Set.of(graphUri),
                    Set.of(user2.getPrimaryGroup().getUri()),
                    user1Ctx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // user2 still does not see graph
        InvocationContext user2Ctx = new InvocationContext.Builder()
                .userId(user2.getId())
                .primaryGroupId(user2.getPrimaryGroup().getId())
                .groupIds(Set.of(user2.getPrimaryGroup().getId()))
                .build();

        dataset.begin(TxnType.READ);
        try {
            assertFalse(dataset.containsGraph(graphNode, user2Ctx));
        } finally {
            dataset.end();
        }
    }

    @Test
    void granterCanUpgradeAndDowngradePermissionsAndDbReflectsEffectiveGrant() throws Exception {

        // -----------------------
        // Arrange: create user2
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "user2", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        User user2;
        dataset.begin(TxnType.READ);
        try {
            user2 = dataset.getUser("user2", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext user1Ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        String graphUri = "urn:test:permissionUpgradeDowngrade";
        Node graphNode = NodeFactory.createURI(graphUri);

        // -----------------------
        // user1 creates graph
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(graphNode,
                    GraphFactory.createDefaultGraph(),
                    user1Ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // helper lambda to read effective permission
        java.util.function.Supplier<Integer> readPermission = () -> {
            try {
                return dataset.getDatabase().read(
                        "SELECT permission FROM graph_acl a "
                        + "JOIN graph g ON a.graph_id = g.id "
                        + "JOIN \"group\" gr ON a.group_id = gr.id "
                        + "WHERE g.uri = ? AND gr.uri = ? AND a.granted_by_group_id = ?",
                        rs -> rs.next() ? rs.getInt(1) : null,
                        graphUri,
                        user2.getPrimaryGroup().getUri(),
                        adminUser.getPrimaryGroupId()
                );
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        };

        // -----------------------
        // Grant READ
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareGraphs(
                    Set.of(graphUri),
                    Set.of(user2.getPrimaryGroup().getUri()),
                    Permission.READ,
                    user1Ctx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        dataset.begin(TxnType.READ);
        try {
            assertEquals(Permission.READ.getCode(), readPermission.get(),
                    "DB should store READ permission");
        } finally {
            dataset.end();
        }

        // -----------------------
        // Upgrade to EDIT
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareGraphs(
                    Set.of(graphUri),
                    Set.of(user2.getPrimaryGroup().getUri()),
                    Permission.EDIT,
                    user1Ctx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        dataset.begin(TxnType.READ);
        try {
            assertEquals(Permission.EDIT.getCode(), readPermission.get(),
                    "DB should store EDIT permission after upgrade");
        } finally {
            dataset.end();
        }

        // -----------------------
        // Upgrade to ADMIN
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareGraphs(
                    Set.of(graphUri),
                    Set.of(user2.getPrimaryGroup().getUri()),
                    Permission.ADMIN,
                    user1Ctx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        dataset.begin(TxnType.READ);
        try {
            assertEquals(Permission.ADMIN.getCode(), readPermission.get(),
                    "DB should store ADMIN permission after upgrade");
        } finally {
            dataset.end();
        }

        // -----------------------
        // Downgrade back to READ
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.shareGraphs(
                    Set.of(graphUri),
                    Set.of(user2.getPrimaryGroup().getUri()),
                    Permission.READ,
                    user1Ctx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        dataset.begin(TxnType.READ);
        try {
            assertEquals(Permission.READ.getCode(), readPermission.get(),
                    "DB should store READ permission after downgrade");
        } finally {
            dataset.end();
        }
    }

    @Test
    void adminSeesGraphCreatedByAnotherUser() throws Exception {

        // 1) create a normal user (non-admin)
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "userA", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // load normal user
        User userA;
        dataset.begin(TxnType.READ);
        try {
            userA = dataset.getUser("userA", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }
        assertNotNull(userA);

        InvocationContext userACtx
                = new InvocationContext.Builder().fromUser(userA).build();

        // load admin user
        User adminUser;
        dataset.begin(TxnType.READ);
        try {
            adminUser = dataset.getUser(
                    UserGroupManagement.ADMIN_USERNAME,
                    InvocationContext.EMPTY
            );
        } finally {
            dataset.end();
        }
        assertNotNull(adminUser);

        InvocationContext adminCtx
                = new InvocationContext.Builder().fromUser(adminUser).build();

        // 2) userA creates a graph
        String graphUri = "urn:test:userOwnedGraph";
        Node graphNode = NodeFactory.createURI(graphUri);

        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(
                    graphNode,
                    GraphFactory.createDefaultGraph(),
                    userACtx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // 3) verify: admin can see the graph immediately
        dataset.begin(TxnType.READ);
        try {
            assertTrue(dataset.containsGraph(graphNode, adminCtx),
                    "Admin should see all graphs, including those created by other users");

            List<String> visibleUris = new ArrayList<>();
            Iterator<Node> iter = dataset.listGraphNodes(adminCtx);
            iter.forEachRemaining(n -> visibleUris.add(n.getURI()));

            assertTrue(visibleUris.contains(graphUri),
                    "Admin should see the graph in listGraphNodes");
        } finally {
            dataset.end();
        }
    }

    @Test
    void adminCanRemoveGraphCreatedByAnotherUser() throws Exception {

        // 1) create a normal user
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addUser("", "", "", "userB", InvocationContext.EMPTY);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // load normal user
        User userB;
        dataset.begin(TxnType.READ);
        try {
            userB = dataset.getUser("userB", InvocationContext.EMPTY);
        } finally {
            dataset.end();
        }
        assertNotNull(userB);

        InvocationContext userBCtx
                = new InvocationContext.Builder().fromUser(userB).build();

        // load admin user
        User adminUser;
        dataset.begin(TxnType.READ);
        try {
            adminUser = dataset.getUser(
                    UserGroupManagement.ADMIN_USERNAME,
                    InvocationContext.EMPTY
            );
        } finally {
            dataset.end();
        }
        assertNotNull(adminUser);

        InvocationContext adminCtx
                = new InvocationContext.Builder().fromUser(adminUser).build();

        // 2) userB creates a graph
        String graphUri = "urn:test:userBCreatedGraph";
        Node graphNode = NodeFactory.createURI(graphUri);

        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(
                    graphNode,
                    GraphFactory.createDefaultGraph(),
                    userBCtx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // sanity check: graph exists
        dataset.begin(TxnType.READ);
        try {
            assertTrue(dataset.containsGraph(graphNode, adminCtx),
                    "Admin should see the graph before deletion");
        } finally {
            dataset.end();
        }

        // 3) admin removes the graph
        dataset.begin(TxnType.WRITE);
        try {
            dataset.removeGraph(graphNode, adminCtx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // 4) verify graph is gone (for admin)
        dataset.begin(TxnType.READ);
        try {
            assertFalse(dataset.containsGraph(graphNode, adminCtx),
                    "Graph should be removed by admin");

            List<String> visibleUris = new ArrayList<>();
            Iterator<Node> iter = dataset.listGraphNodes(adminCtx);
            iter.forEachRemaining(n -> visibleUris.add(n.getURI()));

            assertFalse(visibleUris.contains(graphUri),
                    "Removed graph should not appear in listGraphNodes for admin");
        } finally {
            dataset.end();
        }

        // 5) verify graph is also gone for original owner
        dataset.begin(TxnType.READ);
        try {
            assertFalse(dataset.containsGraph(graphNode, userBCtx),
                    "Graph should also be gone for the original creator");
        } finally {
            dataset.end();
        }
    }
}
