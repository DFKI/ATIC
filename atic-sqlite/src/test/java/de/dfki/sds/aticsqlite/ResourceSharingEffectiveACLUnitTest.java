package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.Group;
import de.dfki.sds.atic.ac.Permission;
import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.system.Txn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 *
 */
public class ResourceSharingEffectiveACLUnitTest {

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

        Txn.executeWrite(dataset, () -> {
            dataset.assignUserToGroup("alice", "phoenix", InvocationContext.EMPTY);
            dataset.assignUserToGroup("bob", "phoenix", InvocationContext.EMPTY);
            dataset.assignUserToGroup("charlie", "phoenix", InvocationContext.EMPTY);
            dataset.assignUserToGroup("david", "data", InvocationContext.EMPTY);
            dataset.assignUserToGroup("eve", "data", InvocationContext.EMPTY);
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
    void testReadAccessIsolationBetweenUsers() throws Exception {

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
        // 4) Verify effective ACLs
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {
            try {

                Database db = dataset.getDatabase();

                Integer aliceSubjectPermission = db.read(
                        """
                SELECT permission
                FROM resource_acl_effective
                WHERE resource_id = (
                    SELECT id
                    FROM resource_uri
                    WHERE uri = ?
                )
                AND user_id = ?
                """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        sAlice.getURI(),
                        alice.getId()
                );

                Integer bobSubjectPermission = db.read(
                        """
                SELECT permission
                FROM resource_acl_effective
                WHERE resource_id = (
                    SELECT id
                    FROM resource_uri
                    WHERE uri = ?
                )
                AND user_id = ?
                """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        sBob.getURI(),
                        bob.getId()
                );

                Integer aliceOnBobPermission = db.read(
                        """
                SELECT permission
                FROM resource_acl_effective
                WHERE resource_id = (
                    SELECT id
                    FROM resource_uri
                    WHERE uri = ?
                )
                AND user_id = ?
                """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        sBob.getURI(),
                        alice.getId()
                );

                Integer charlieOnAlicePermission = db.read(
                        """
                SELECT permission
                FROM resource_acl_effective
                WHERE resource_id = (
                    SELECT id
                    FROM resource_uri
                    WHERE uri = ?
                )
                AND user_id = ?
                """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        sAlice.getURI(),
                        charlie.getId()
                );

                assertEquals(Permission.ADMIN.getCode(), aliceSubjectPermission);
                assertEquals(Permission.ADMIN.getCode(), bobSubjectPermission);

                assertNull(aliceOnBobPermission);
                assertNull(charlieOnAlicePermission);

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
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

        Txn.executeRead(dataset, () -> {
            try {

                Database db = dataset.getDatabase();

                Integer alicePermission = db.read(
                        """
                SELECT permission
                FROM resource_acl_effective
                WHERE resource_id = (
                    SELECT id
                    FROM resource_uri
                    WHERE uri = ?
                )
                  AND user_id = ?
                """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        sAlice.getURI(),
                        alice.getId()
                );

                Integer bobPermission = db.read(
                        """
                SELECT permission
                FROM resource_acl_effective
                WHERE resource_id = (
                    SELECT id
                    FROM resource_uri
                    WHERE uri = ?
                )
                  AND user_id = ?
                """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        sAlice.getURI(),
                        bob.getId()
                );

                assertEquals(Permission.ADMIN.getCode(), alicePermission);
                assertEquals(Permission.READ.getCode(), bobPermission);

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Test
    void testAclUpdateFromEditToReadIsReflectedInEffectiveAcl() {

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
        // 3) Alice shares SUBJECT with Bob (EDIT)
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
        // 4) VERIFY: Bob has EDIT
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {
            try {

                Database db = dataset.getDatabase();

                Integer bobEditPermission = db.read(
                        """
                    SELECT permission
                    FROM resource_acl_effective
                    WHERE resource_id = (
                        SELECT id FROM resource_uri WHERE uri = ?
                    )
                    AND user_id = ?
                    """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        sAlice.getURI(),
                        bob.getId()
                );

                assertEquals(
                        Permission.EDIT.getCode(),
                        bobEditPermission,
                        "Bob should initially have EDIT permission"
                );

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });

        // ---------------------------------------
        // 5) Alice downgrades Bob to READ
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
        // 6) VERIFY: Bob is now READ (UPDATED EFFECTIVE ACL)
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {
            try {

                Database db = dataset.getDatabase();

                Integer bobUpdatedPermission = db.read(
                        """
                    SELECT permission
                    FROM resource_acl_effective
                    WHERE resource_id = (
                        SELECT id FROM resource_uri WHERE uri = ?
                    )
                    AND user_id = ?
                    """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        sAlice.getURI(),
                        bob.getId()
                );

                assertEquals(
                        Permission.READ.getCode(),
                        bobUpdatedPermission,
                        "Bob should be downgraded to READ permission"
                );

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Test
    void testUnassignUserFromGroupRemovesEffectivePermission() {

        Node graph = NodeFactory.createURI("urn:test:graph");

        Node sAlice = NodeFactory.createURI("urn:alice:s");
        Node p = NodeFactory.createURI("urn:p");
        Node oAlice = NodeFactory.createLiteralString("aliceValue");

        // ---------------------------------------
        // 1) Admin creates graph + shares EDIT with phoenix group
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), adminCtx);

            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(phoenixGroup.getUri()),
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
        // 3) Alice shares subject + object with phoenix group (READ)
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.shareResources(
                    Set.of(sAlice.getURI()),
                    Set.of(phoenixGroup.getUri()),
                    Permission.EDIT,
                    aliceCtx
            );
        });

        //dumpAclState("after alice shares EDIT with phoenixGroup");
        // ---------------------------------------
        // 3) VERIFY: Bob initially has EDIT via group membership
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {
            try {

                Database db = dataset.getDatabase();

                Integer bobInitial = db.read(
                        """
                    SELECT permission
                    FROM resource_acl_effective
                    WHERE resource_id = (
                        SELECT id FROM resource_uri WHERE uri = ?
                    )
                    AND user_id = ?
                    """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        sAlice.getURI(),
                        bob.getId()
                );

                assertEquals(
                        Permission.EDIT.getCode(),
                        bobInitial,
                        "Bob should initially inherit EDIT from phoenix group"
                );

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });

        // ---------------------------------------
        // 4) Remove Bob from phoenix group
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.unassignUserFromGroup(
                    bob.getUsername(),
                    phoenixGroup.getGroupname(),
                    InvocationContext.EMPTY
            );
        });

        //dumpAclState("after bob removed from phoenixGroup");
        // ---------------------------------------
        // 5) VERIFY: Bob loses permission
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {
            try {

                Database db = dataset.getDatabase();

                Integer bobAfterRemoval = db.read(
                        """
                    SELECT permission
                    FROM resource_acl_effective
                    WHERE resource_id = (
                        SELECT id FROM resource_uri WHERE uri = ?
                    )
                    AND user_id = ?
                    """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        sAlice.getURI(),
                        bob.getId()
                );

                assertNull(
                        bobAfterRemoval,
                        "Bob should lose effective permission after group removal"
                );

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Test
    void testEditAccessAllowsModifyTriple() {

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
        // 4) Verify effective ACLs after EDIT sharing + modification
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {
            try {

                Database db = dataset.getDatabase();

                Integer alicePermission = db.read(
                        """
                SELECT permission
                FROM resource_acl_effective
                WHERE resource_id = (
                    SELECT id
                    FROM resource_uri
                    WHERE uri = ?
                )
                AND user_id = ?
                """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        sAlice.getURI(),
                        alice.getId()
                );

                Integer bobPermission = db.read(
                        """
                SELECT permission
                FROM resource_acl_effective
                WHERE resource_id = (
                    SELECT id
                    FROM resource_uri
                    WHERE uri = ?
                )
                AND user_id = ?
                """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        sAlice.getURI(),
                        bob.getId()
                );

                // Alice must retain ADMIN rights (owner of resource)
                assertEquals(Permission.ADMIN.getCode(), alicePermission);

                // Bob must have EDIT rights due to explicit share
                assertEquals(Permission.EDIT.getCode(), bobPermission);

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Test
    void testGroupSharingAllowsMultipleUsersToSeeTriple() {

        Node graph = NodeFactory.createURI("urn:test:graph");

        Node sAlice = NodeFactory.createURI("urn:alice:s");
        Node p = NodeFactory.createURI("urn:p");
        Node oAlice = NodeFactory.createURI("urn:alice:o");

        // ---------------------------------------
        // 1) Admin creates graph + shares EDIT with phoenix + data groups
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), adminCtx);

            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(
                            phoenixGroup.getUri(),
                            dataGroup.getUri()
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
        // 3) Alice shares subject + object with phoenix group (READ)
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.shareResources(
                    Set.of(sAlice.getURI(), oAlice.getURI()),
                    Set.of(phoenixGroup.getUri()),
                    Permission.READ,
                    aliceCtx
            );
        });

        //dumpAclState("after dataset.shareResources");
        // ---------------------------------------
        // 4) Verify resource_acl_effective (Bob = phoenix group member)
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {
            try {

                Database db = dataset.getDatabase();

                Integer alicePerm = db.read(
                        """
                    SELECT permission
                    FROM resource_acl_effective
                    WHERE resource_id = (
                        SELECT id FROM resource_uri WHERE uri = ?
                    )
                    AND user_id = ?
                    """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        sAlice.getURI(),
                        alice.getId()
                );

                Integer bobPerm = db.read(
                        """
                    SELECT permission
                    FROM resource_acl_effective
                    WHERE resource_id = (
                        SELECT id FROM resource_uri WHERE uri = ?
                    )
                    AND user_id = ?
                    """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        sAlice.getURI(),
                        bob.getId()
                );

                Integer charliePerm = db.read(
                        """
                    SELECT permission
                    FROM resource_acl_effective
                    WHERE resource_id = (
                        SELECT id FROM resource_uri WHERE uri = ?
                    )
                    AND user_id = ?
                    """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        sAlice.getURI(),
                        charlie.getId()
                );

                Integer davidPerm = db.read(
                        """
                    SELECT permission
                    FROM resource_acl_effective
                    WHERE resource_id = (
                        SELECT id FROM resource_uri WHERE uri = ?
                    )
                    AND user_id = ?
                    """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        sAlice.getURI(),
                        david.getId()
                );

                // Alice owns the resource → ADMIN
                assertEquals(Permission.ADMIN.getCode(), alicePerm);

                // Bob is in phoenix group → READ (via shareResources)
                assertEquals(Permission.READ.getCode(), bobPerm);

                // Charlie is also in phoenix group → READ
                assertEquals(Permission.READ.getCode(), charliePerm);

                // David is in data group but NOT granted resource permission → NULL
                assertNull(davidPerm);

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Test
    void testShareResourcesAdminThenUnshareRemovesEffectivePermission() {

        Node graph = NodeFactory.createURI("urn:test:graph");

        Node sAlice = NodeFactory.createURI("urn:alice:s");
        Node p = NodeFactory.createURI("urn:p");
        Node oAlice = NodeFactory.createLiteralString("aliceValue");

        // ---------------------------------------
        // 1) Admin creates graph + grants EDIT on graph to phoenix group
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), adminCtx);

            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(phoenixGroup.getUri()),
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
        // 3) Alice shares RESOURCE with Bob group (ADMIN)
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.shareResources(
                    Set.of(sAlice.getURI()),
                    Set.of(phoenixGroup.getUri()),
                    Permission.ADMIN,
                    aliceCtx
            );
        });

        // ---------------------------------------
        // 4) VERIFY: Bob has ADMIN via effective ACL
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {
            try {

                Database db = dataset.getDatabase();

                Integer bobInitial = db.read(
                        """
                    SELECT permission
                    FROM resource_acl_effective
                    WHERE resource_id = (
                        SELECT id FROM resource_uri WHERE uri = ?
                    )
                    AND user_id = ?
                    """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        sAlice.getURI(),
                        bob.getId()
                );

                assertEquals(
                        Permission.ADMIN.getCode(),
                        bobInitial,
                        "Bob should initially have ADMIN via shareResources"
                );

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });

        // ---------------------------------------
        // 5) Alice unshares RESOURCE from Bob group
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.unshareResources(
                    Set.of(sAlice.getURI()),
                    Set.of(phoenixGroup.getUri()),
                    aliceCtx
            );
        });

        // ---------------------------------------
        // 6) VERIFY: Bob loses effective permission
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {
            try {

                Database db = dataset.getDatabase();

                Integer bobAfterUnshare = db.read(
                        """
                    SELECT permission
                    FROM resource_acl_effective
                    WHERE resource_id = (
                        SELECT id FROM resource_uri WHERE uri = ?
                    )
                    AND user_id = ?
                    """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        sAlice.getURI(),
                        bob.getId()
                );

                assertNull(
                        bobAfterUnshare,
                        "Bob should lose ADMIN permission after unshareResources"
                );

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Test
    void testShareWithPrimaryGroupThenUnshareRemovesEffectivePermission() {

        Node graph = NodeFactory.createURI("urn:test:graph");

        Node sAlice = NodeFactory.createURI("urn:alice:s");
        Node p = NodeFactory.createURI("urn:p");
        Node oAlice = NodeFactory.createLiteralString("aliceValue");

        String bobPrimaryGroupUri = bob.getPrimaryGroup().getUri();

        // ---------------------------------------
        // 1) Admin creates graph + grants EDIT to phoenix group
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), adminCtx);

            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(phoenixGroup.getUri()),
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
        // 3) Alice shares resource with Bob's PRIMARY GROUP (ADMIN)
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.shareResources(
                    Set.of(sAlice.getURI()),
                    Set.of(bobPrimaryGroupUri),
                    Permission.ADMIN,
                    aliceCtx
            );
        });

        // ---------------------------------------
        // 4) VERIFY: Bob has ADMIN via primary group mapping
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {
            try {

                Database db = dataset.getDatabase();

                Integer bobInitial = db.read(
                        """
                    SELECT permission
                    FROM resource_acl_effective
                    WHERE resource_id = (
                        SELECT id FROM resource_uri WHERE uri = ?
                    )
                    AND user_id = ?
                    """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        sAlice.getURI(),
                        bob.getId()
                );

                assertEquals(
                        Permission.ADMIN.getCode(),
                        bobInitial,
                        "Bob should inherit ADMIN via primary group sharing"
                );

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });

        // ---------------------------------------
        // 5) Alice unshares from Bob's PRIMARY GROUP
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.unshareResources(
                    Set.of(sAlice.getURI()),
                    Set.of(bobPrimaryGroupUri),
                    aliceCtx
            );
        });

        // ---------------------------------------
        // 6) VERIFY: Bob loses effective permission
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {
            try {

                Database db = dataset.getDatabase();

                Integer bobAfterUnshare = db.read(
                        """
                    SELECT permission
                    FROM resource_acl_effective
                    WHERE resource_id = (
                        SELECT id FROM resource_uri WHERE uri = ?
                    )
                    AND user_id = ?
                    """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        sAlice.getURI(),
                        bob.getId()
                );

                assertNull(
                        bobAfterUnshare,
                        "Bob should lose ADMIN after unshare from primary group"
                );

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Test
    void testFullPermissionChainWithCorrectAdminOwnershipFlow() {

        Node graph = NodeFactory.createURI("urn:test:graph");

        Node s = NodeFactory.createURI("urn:shared:s");
        Node p = NodeFactory.createURI("urn:p");
        Node o = NodeFactory.createLiteralString("value");

        String aliceGroup = alice.getPrimaryGroup().getUri();
        String bobGroup = bob.getPrimaryGroup().getUri();
        String charlieGroup = charlie.getPrimaryGroup().getUri();

        // ---------------------------------------
        // 1) Admin creates graph
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), adminCtx);
        });

        // ---------------------------------------
        // 2) Admin creates the resource (ADMIN ownership required)
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.add(graph, s, p, o, adminCtx);
        });

        // ---------------------------------------
        // 3) Admin shares RESOURCE with Alice + Bob (ADMIN)
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.shareResources(
                    Set.of(s.getURI()),
                    Set.of(aliceGroup, bobGroup),
                    Permission.ADMIN,
                    adminCtx
            );
        });

        // ---------------------------------------
        // 4) Alice shares with Charlie (READ)
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.shareResources(
                    Set.of(s.getURI()),
                    Set.of(charlieGroup),
                    Permission.READ,
                    aliceCtx
            );
        });

        // ---------------------------------------
        // 5) Bob shares with Charlie (EDIT)
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.shareResources(
                    Set.of(s.getURI()),
                    Set.of(charlieGroup),
                    Permission.EDIT,
                    bobCtx
            );
        });

        // ---------------------------------------
        // 6) VERIFY: Charlie = EDIT
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {
            try {

                Database db = dataset.getDatabase();

                Integer perm = db.read(
                        """
                    SELECT permission
                    FROM resource_acl_effective
                    WHERE resource_id = (
                        SELECT id FROM resource_uri WHERE uri = ?
                    )
                    AND user_id = ?
                    """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        s.getURI(),
                        charlie.getId()
                );

                assertEquals(Permission.EDIT.getCode(), perm);

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });

        // ---------------------------------------
        // 7) Bob revokes his EDIT
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.unshareResources(
                    Set.of(s.getURI()),
                    Set.of(charlieGroup),
                    bobCtx
            );
        });

        // ---------------------------------------
        // 8) VERIFY: Charlie drops to READ
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {
            try {

                Database db = dataset.getDatabase();

                Integer perm = db.read(
                        """
                    SELECT permission
                    FROM resource_acl_effective
                    WHERE resource_id = (
                        SELECT id FROM resource_uri WHERE uri = ?
                    )
                    AND user_id = ?
                    """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        s.getURI(),
                        charlie.getId()
                );

                assertEquals(Permission.READ.getCode(), perm);

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });

        // ---------------------------------------
        // 9) Alice unshares READ (removes last source)
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.unshareResources(
                    Set.of(s.getURI()),
                    Set.of(charlieGroup),
                    aliceCtx
            );
        });

        // ---------------------------------------
        // 10) VERIFY: Charlie loses access completely
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {
            try {

                Database db = dataset.getDatabase();

                Integer perm = db.read(
                        """
                    SELECT permission
                    FROM resource_acl_effective
                    WHERE resource_id = (
                        SELECT id FROM resource_uri WHERE uri = ?
                    )
                    AND user_id = ?
                    """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        s.getURI(),
                        charlie.getId()
                );

                assertNull(perm);

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Test
    void testMultiGroupOverlapResolvesToMaxPermission() {

        Node graph = NodeFactory.createURI("urn:test:graph");

        Node s = NodeFactory.createURI("urn:resource:s");
        Node p = NodeFactory.createURI("urn:p");
        Node o = NodeFactory.createLiteralString("value");

        // ---------------------------------------
        // 1) Setup base data
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), adminCtx);

            dataset.addUser("multi", "user", "multi@example.com", "multi", InvocationContext.EMPTY);

            dataset.addGroup("group_read", InvocationContext.EMPTY);
            dataset.addGroup("group_edit", InvocationContext.EMPTY);
        });

        // ---------------------------------------
        // 2) Resolve user + groups (correct snapshot isolation)
        // ---------------------------------------
        final User user = dataset.calculateRead(()
                -> dataset.getUser("multi", InvocationContext.EMPTY)
        );

        final Group groupRead = dataset.calculateRead(()
                -> dataset.getGroup("group_read", InvocationContext.EMPTY)
        );

        final Group groupEdit = dataset.calculateRead(()
                -> dataset.getGroup("group_edit", InvocationContext.EMPTY)
        );

        final InvocationContext userCtx = new InvocationContext.Builder()
                .fromUser(user)
                .build();

        // ---------------------------------------
        // 3) Assign user to BOTH groups (this is the critical fix)
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.assignUserToGroup(
                    user.getUsername(),
                    groupRead.getGroupname(),
                    InvocationContext.EMPTY
            );

            dataset.assignUserToGroup(
                    user.getUsername(),
                    groupEdit.getGroupname(),
                    InvocationContext.EMPTY
            );
        });

        // ---------------------------------------
        // 4) Admin creates resource
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.add(graph, s, p, o, adminCtx);
        });

        // ---------------------------------------
        // 5) Share READ via group_read
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.shareResources(
                    Set.of(s.getURI()),
                    Set.of(groupRead.getShareUri()),
                    Permission.READ,
                    adminCtx
            );
        });

        // ---------------------------------------
        // 6) Share EDIT via group_edit
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.shareResources(
                    Set.of(s.getURI()),
                    Set.of(groupEdit.getShareUri()),
                    Permission.EDIT,
                    adminCtx
            );
        });

        // ---------------------------------------
        // 7) VERIFY: EDIT wins over READ
        // ---------------------------------------
        Txn.executeRead(dataset, () -> {

            try {

                Database db = dataset.getDatabase();

                Integer perm = db.read(
                        """
                    SELECT permission
                    FROM resource_acl_effective
                    WHERE resource_id = (
                        SELECT id FROM resource_uri WHERE uri = ?
                    )
                    AND user_id = ?
                    """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        s.getURI(),
                        user.getId()
                );

                assertEquals(
                        Permission.EDIT.getCode(),
                        perm,
                        "User in multiple groups must get MAX(permission)"
                );

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Test
    void testMixedDirectGroupAndPrimaryGroupPermissionPriority() {

        Node graph = NodeFactory.createURI("urn:test:graph");

        Node s = NodeFactory.createURI("urn:resource:s");
        Node p = NodeFactory.createURI("urn:p");
        Node o = NodeFactory.createLiteralString("value");

        // -------------------------------------------------
        // resolve users + groups via calculateRead (final)
        // -------------------------------------------------
        final User alice = dataset.calculateRead(()
                -> dataset.getUser("alice", InvocationContext.EMPTY)
        );

        final User bob = dataset.calculateRead(()
                -> dataset.getUser("bob", InvocationContext.EMPTY)
        );

        Txn.executeWrite(dataset, () -> {
            dataset.addGroup("groupRead", InvocationContext.EMPTY);
            dataset.addGroup("groupEdit", InvocationContext.EMPTY);
        });

        final Group groupRead = dataset.calculateRead(()
                -> dataset.getGroup("groupRead", InvocationContext.EMPTY)
        );

        final Group groupEdit = dataset.calculateRead(()
                -> dataset.getGroup("groupEdit", InvocationContext.EMPTY)
        );

        final InvocationContext aliceCtx
                = new InvocationContext.Builder().fromUser(alice).build();

        // -------------------------------------------------
        // IMPORTANT: assign bob to BOTH groups
        // -------------------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.assignUserToGroup(
                    bob.getUsername(),
                    groupRead.getGroupname(),
                    InvocationContext.EMPTY
            );

            dataset.assignUserToGroup(
                    bob.getUsername(),
                    groupEdit.getGroupname(),
                    InvocationContext.EMPTY
            );
        });

        // -------------------------------------------------
        // 1) Setup graph + base graph permission
        // -------------------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.addGraph(graph, GraphFactory.createDefaultGraph(), aliceCtx);

            dataset.shareGraphs(
                    Set.of(graph.getURI()),
                    Set.of(groupRead.getUri(), groupEdit.getUri()),
                    Permission.READ,
                    aliceCtx
            );
        });

        // -------------------------------------------------
        // 2) Alice creates resource (OWNER => ADMIN)
        // -------------------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.add(graph, s, p, o, aliceCtx);
        });

        // -------------------------------------------------
        // 4) GROUP grants EDIT and READ
        // -------------------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.shareResources(
                    Set.of(s.getURI()),
                    Set.of(groupEdit.getUri()),
                    Permission.EDIT,
                    aliceCtx
            );
        });
        
        Txn.executeWrite(dataset, () -> {
            dataset.shareResources(
                    Set.of(s.getURI()),
                    Set.of(groupRead.getUri()),
                    Permission.READ,
                    aliceCtx
            );
        });

        // -------------------------------------------------
        // 5) DIRECT share to Bob (highest priority)
        // -------------------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.shareResources(
                    Set.of(s.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    Permission.ADMIN,
                    aliceCtx
            );
        });

        // -------------------------------------------------
        // 6) VERIFY initial state
        // -------------------------------------------------
        Txn.executeRead(dataset, () -> {
            try {
                Database db = dataset.getDatabase();

                Integer bobPerm = db.read(
                        """
                    SELECT permission
                    FROM resource_acl_effective
                    WHERE resource_id = (SELECT id FROM resource_uri WHERE uri = ?)
                      AND user_id = ?
                    """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        s.getURI(),
                        bob.getId()
                );

                assertEquals(Permission.ADMIN.getCode(), bobPerm);

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });

        // -------------------------------------------------
        // 7) Remove DIRECT permission
        // -------------------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.unshareResources(
                    Set.of(s.getURI()),
                    Set.of(bob.getPrimaryGroup().getUri()),
                    aliceCtx
            );
        });

        // -------------------------------------------------
        // 8) VERIFY group EDIT now applies
        // -------------------------------------------------
        Txn.executeRead(dataset, () -> {
            try {
                Database db = dataset.getDatabase();

                Integer bobPerm = db.read(
                        """
                    SELECT permission
                    FROM resource_acl_effective
                    WHERE resource_id = (SELECT id FROM resource_uri WHERE uri = ?)
                      AND user_id = ?
                    """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        s.getURI(),
                        bob.getId()
                );

                assertEquals(Permission.EDIT.getCode(), bobPerm);

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });

        // -------------------------------------------------
        // 9) Remove GROUP EDIT
        // -------------------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.unshareResources(
                    Set.of(s.getURI()),
                    Set.of(groupEdit.getUri()),
                    aliceCtx
            );
        });
        
        //dumpAclState("after unshareResources groupEdit");

        // -------------------------------------------------
        // 10) VERIFY fallback to READ group
        // -------------------------------------------------
        Txn.executeRead(dataset, () -> {
            try {
                Database db = dataset.getDatabase();

                Integer bobFinal = db.read(
                        """
                    SELECT permission
                    FROM resource_acl_effective
                    WHERE resource_id = (SELECT id FROM resource_uri WHERE uri = ?)
                      AND user_id = ?
                    """,
                        rs -> rs.next() ? rs.getInt(1) : null,
                        s.getURI(),
                        bob.getId()
                );

                assertEquals(Permission.READ.getCode(), bobFinal);

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    //======================================================
    //helper
    private void dumpAclState(String label) {
        Txn.executeRead(dataset, () -> {
            try {

                Database db = dataset.getDatabase();

                System.out.println("\n================ " + label + " ================");

                // ---------------------------------------
                // resource_acl (source of truth)
                // ---------------------------------------
                System.out.println("\n--- resource_acl ---");
                db.read(
                        "SELECT resource_id, group_id, permission FROM resource_acl ORDER BY resource_id, group_id",
                        rs -> {
                            while (rs.next()) {
                                System.out.printf(
                                        "resource=%d group=%d perm=%d%n",
                                        rs.getInt(1),
                                        rs.getInt(2),
                                        rs.getInt(3)
                                );
                            }
                            return null;
                        }
                );

                // ---------------------------------------
                // resource_acl_effective (materialized view)
                // ---------------------------------------
                System.out.println("\n--- resource_acl_effective ---");
                db.read(
                        "SELECT resource_id, user_id, permission FROM resource_acl_effective ORDER BY resource_id, user_id",
                        rs -> {
                            while (rs.next()) {
                                System.out.printf(
                                        "resource=%d user=%d perm=%d%n",
                                        rs.getInt(1),
                                        rs.getInt(2),
                                        rs.getInt(3)
                                );
                            }
                            return null;
                        }
                );

                // ---------------------------------------
                // users
                // ---------------------------------------
                System.out.println("\n--- user ---");
                db.read(
                        "SELECT id, username, uri FROM user ORDER BY id",
                        rs -> {
                            while (rs.next()) {
                                System.out.printf(
                                        "user=%d username=%s uri=%s%n",
                                        rs.getInt(1),
                                        rs.getString(2),
                                        rs.getString(3)
                                );
                            }
                            return null;
                        }
                );

                // ---------------------------------------
                // groups (including primary group ownership)
                // ---------------------------------------
                System.out.println("\n--- group (ownership mapping) ---");
                db.read(
                        "SELECT id, groupname, user_id FROM \"group\" ORDER BY id",
                        rs -> {
                            while (rs.next()) {
                                System.out.printf(
                                        "group=%d name=%s owner_user=%s%n",
                                        rs.getInt(1),
                                        rs.getString(2),
                                        rs.getObject(3)
                                );
                            }
                            return null;
                        }
                );

                // ---------------------------------------
                // explicit group membership
                // ---------------------------------------
                System.out.println("\n--- user_group_assignment ---");
                db.read(
                        "SELECT user_id, group_id FROM user_group_assignment ORDER BY user_id, group_id",
                        rs -> {
                            while (rs.next()) {
                                System.out.printf(
                                        "user=%d -> group=%d%n",
                                        rs.getInt(1),
                                        rs.getInt(2)
                                );
                            }
                            return null;
                        }
                );

                // ---------------------------------------
                // resolved membership view (VERY IMPORTANT DEBUG)
                // ---------------------------------------
                System.out.println("\n--- resolved user → group mapping ---");
                db.read(
                        """
                    SELECT u.id AS user_id, uga.group_id
                    FROM user u
                    LEFT JOIN user_group_assignment uga ON uga.user_id = u.id

                    UNION

                    SELECT g.user_id AS user_id, g.id AS group_id
                    FROM "group" g
                    WHERE g.user_id IS NOT NULL

                    ORDER BY user_id, group_id
                    """,
                        rs -> {
                            while (rs.next()) {
                                System.out.printf(
                                        "user=%s -> group=%s%n",
                                        rs.getObject(1),
                                        rs.getObject(2)
                                );
                            }
                            return null;
                        }
                );

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });
    }

}
