package de.dfki.sds.aticsqlite;

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
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.TxnType;
import org.apache.jena.sparql.graph.GraphFactory;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 *
 */
public class GraphUnitTest {

    private SqliteAticDatasetGraph dataset;

    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);
    }

    @Test
    void getDefaultGraphWithAdminGroupShouldSucceed() throws Exception {

        User user = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });
        
        InvocationContext ctx = new InvocationContext.Builder().fromUser(user).build();

        dataset.begin(TxnType.READ);
        try {
            AticGraph graph = dataset.getDefaultGraph(ctx);
            assertNotNull(graph, "Admin should be able to read the default graph");
        } finally {
            dataset.end();
        }
    }
    
    //strange behavior: on debug same object, when full run different objects
    //@Disabled
    @Test
    void getDefaultGraphHasSameHashCode() throws Exception {

        User user = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });
        
        InvocationContext ctx = new InvocationContext.Builder().fromUser(user).build();

        dataset.begin(TxnType.READ);
        try {
            AticGraph graph1 = dataset.getDefaultGraph(ctx);
            assertNotNull(graph1, "Admin should be able to read the default graph");
            
            AticGraph graph2 = dataset.getDefaultGraph(ctx);
            assertNotNull(graph1, "Admin should be able to read the default graph");
            
            assertEquals(graph1, graph2);
            
        } finally {
            dataset.end();
        }
    }

    @Test
    void getDefaultGraphWithNonAdminGroupShouldFail() throws Exception {

        InvocationContext ctx = new InvocationContext.Builder()
                .userId(2)
                .primaryGroupId(10)
                .groupIds(Set.of(10))
                .build();

        dataset.begin(TxnType.READ);
        try {

            PermissionDeniedException ex = assertThrows(
                    PermissionDeniedException.class,
                    () -> dataset.getDefaultGraph(ctx),
                    "Non-admin user should get PermissionDeniedException"
            );

            assertEquals(Permission.READ, ex.getRequiredPermission());

            assertTrue(
                    ex.getActualPermissions().isEmpty()
                    || ex.getActualPermissions()
                            .stream()
                            .noneMatch(p -> p.getCode() >= Permission.READ.getCode())
            );

            assertTrue(
                    ex.getUri().contains(org.apache.jena.sparql.core.Quad.defaultGraphIRI.getURI())
            );

        } finally {
            dataset.end();
        }
    }

    @Test
    void adminFullGraphLifecycle(@TempDir Path tempDir) throws Exception {
        // Arrange: admin invocation context
        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext adminCtx = new InvocationContext.Builder().fromUser(adminUser).build();

        String testGraphUri = "urn:test:graph1";
        Node testGraphNode = NodeFactory.createURI(testGraphUri);

        // 1) Add graph
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(testGraphNode, GraphFactory.createDefaultGraph(), adminCtx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // 2) List graphs => should include newly added
        dataset.begin(TxnType.READ);
        try {
            Iterator<Node> iter = dataset.listGraphNodes(adminCtx);
            List<String> uris = new ArrayList<>();
            while (iter.hasNext()) {
                uris.add(iter.next().getURI());
            }
            assertTrue(uris.contains(testGraphUri),
                    "Added graph should be present in listGraphNodes");
        } finally {
            dataset.end();
        }

        // 3) containsGraph => must be true
        dataset.begin(TxnType.READ);
        try {
            assertTrue(dataset.containsGraph(testGraphNode, adminCtx),
                    "containsGraph should return true for added graph");
        } finally {
            dataset.end();
        }

        // 4) Remove graph
        dataset.begin(TxnType.WRITE);
        try {
            dataset.removeGraph(testGraphNode, adminCtx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // 5) After removal, containsGraph => false
        dataset.begin(TxnType.READ);
        try {
            assertFalse(dataset.containsGraph(testGraphNode, adminCtx),
                    "After removal, containsGraph must return false");
        } finally {
            dataset.end();
        }

        // 6) After removal, listGraphNodes should not include it
        dataset.begin(TxnType.READ);
        try {
            Iterator<Node> iter2 = dataset.listGraphNodes(adminCtx);
            List<String> urisAfter = new ArrayList<>();
            while (iter2.hasNext()) {
                urisAfter.add(iter2.next().getURI());
            }
            assertFalse(urisAfter.contains(testGraphUri),
                    "After removal, listGraphNodes should not include the removed graph");
        } finally {
            dataset.end();
        }
    }

    @Test
    void nonAdminSeesNoGraphsEvenAfterAdminAddsOne(@TempDir Path tempDir) throws Exception {
        // Arrange: admin user context
        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext adminCtx = new InvocationContext.Builder().fromUser(adminUser).build();

        // Arrange: non‑admin user context
        InvocationContext nonAdminCtx = new InvocationContext.Builder()
                .userId(2)
                .primaryGroupId(10)
                .groupIds(Set.of(10))
                .build();

        String testGraphUri = "urn:test:adminGraph";
        Node testGraphNode = NodeFactory.createURI(testGraphUri);

        // Admin adds a graph
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(testGraphNode, org.apache.jena.sparql.graph.GraphFactory.createDefaultGraph(), adminCtx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // 1) listGraphNodes as non‑admin => should be empty
        dataset.begin(TxnType.READ);
        try {
            Iterator<Node> iter = dataset.listGraphNodes(nonAdminCtx);
            assertFalse(iter.hasNext(), "Non‑admin should see no graphs");
        } finally {
            dataset.end();
        }

        // 2) containsGraph as non‑admin => should be false
        dataset.begin(TxnType.READ);
        try {
            assertFalse(dataset.containsGraph(testGraphNode, nonAdminCtx),
                    "Non‑admin should not see that the graph exists");
        } finally {
            dataset.end();
        }
    }

    @Test
    void nonAdminCannotRemoveGraphsCreatedByAdmin(@TempDir Path tempDir) throws Exception {
        // Arrange: admin user context
        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext adminCtx = new InvocationContext.Builder().fromUser(adminUser).build();

        // Arrange: non‑admin user context
        InvocationContext nonAdminCtx = new InvocationContext.Builder()
                .userId(2)
                .primaryGroupId(10)
                .groupIds(Set.of(10))
                .build();

        String testGraphUri = "urn:test:adminRemoveGraph";
        Node testGraphNode = NodeFactory.createURI(testGraphUri);

        // Admin adds a graph
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(testGraphNode, org.apache.jena.sparql.graph.GraphFactory.createDefaultGraph(), adminCtx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // Non‑admin tries to remove it
        dataset.begin(TxnType.WRITE);
        try {
            assertThrows(PermissionDeniedException.class,
                    () -> dataset.removeGraph(testGraphNode, nonAdminCtx),
                    "Non‑admin should not be allowed to remove graphs");
            dataset.commit();
        } finally {
            dataset.end();
        }

        // double‑check that the graph still exists for admin
        dataset.begin(TxnType.READ);
        try {
            assertTrue(dataset.containsGraph(testGraphNode, adminCtx),
                    "After non‑admin remove attempt, graph still exists for admin");
        } finally {
            dataset.end();
        }
    }

    @Test
    void userWithReadPermissionCannotDeleteGraph() throws Exception {

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

        InvocationContext user2Ctx = new InvocationContext.Builder()
                .userId(user2.getId())
                .primaryGroupId(user2.getPrimaryGroup().getId())
                .groupIds(Set.of(user2.getPrimaryGroup().getId()))
                .build();

        String graphUri = "urn:test:deleteDenied";
        Node graphNode = NodeFactory.createURI(graphUri);

        // -----------------------
        // user1 creates graph
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(
                    graphNode,
                    GraphFactory.createDefaultGraph(),
                    user1Ctx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // user1 grants READ to user2
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

        // -----------------------
        // Act + Assert:
        // user2 attempts to delete graph -> should fail
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            assertThrows(
                    PermissionDeniedException.class,
                    () -> dataset.removeGraph(graphNode, user2Ctx),
                    "User with READ permission should not be allowed to delete the graph"
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Verify graph still exists
        // -----------------------
        dataset.begin(TxnType.READ);
        try {
            assertTrue(
                    dataset.containsGraph(graphNode, user1Ctx),
                    "Graph should still exist after failed delete attempt"
            );
        } finally {
            dataset.end();
        }
    }

    @Test
    void userWithAdminPermissionCanDeleteGraph() throws Exception {

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

        InvocationContext user2Ctx = new InvocationContext.Builder()
                .userId(user2.getId())
                .primaryGroupId(user2.getPrimaryGroup().getId())
                .groupIds(Set.of(user2.getPrimaryGroup().getId()))
                .build();

        String graphUri = "urn:test:adminDeleteAllowed";
        Node graphNode = NodeFactory.createURI(graphUri);

        // -----------------------
        // user1 creates graph
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.addGraph(
                    graphNode,
                    GraphFactory.createDefaultGraph(),
                    user1Ctx
            );
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // user1 grants ADMIN to user2
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

        // -----------------------
        // Act: user2 deletes graph
        // -----------------------
        dataset.begin(TxnType.WRITE);
        try {
            dataset.removeGraph(graphNode, user2Ctx);
            dataset.commit();
        } finally {
            dataset.end();
        }

        // -----------------------
        // Assert: graph no longer exists
        // -----------------------
        dataset.begin(TxnType.READ);
        try {
            assertFalse(
                    dataset.containsGraph(graphNode, user1Ctx),
                    "Graph should be deleted by user2 with ADMIN permission"
            );
        } finally {
            dataset.end();
        }
    }
    
}
