package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.Agent;
import de.dfki.sds.atic.ac.Permission;
import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.agent.Session;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static java.util.concurrent.TimeUnit.SECONDS;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.system.Txn;
import static org.awaitility.Awaitility.await;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 *
 */
public class AgentUnitTest {

    private SqliteAticDatasetGraph dataset;
    private InvocationContext adminCtx;

    // store users
    private User alice;
    private User bob;
    private User charlie;

    private InvocationContext aliceCtx;
    private InvocationContext bobCtx;
    private InvocationContext charlieCtx;

    private static final String DUMMY_FACTORY = "de.dfki.sds.aticsqlite.agent.DummyAgentProgram.create";

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
        });

        // fetch and store users for reuse in tests
        Txn.executeRead(dataset, () -> {
            alice = dataset.getUser("alice", InvocationContext.EMPTY);
            bob = dataset.getUser("bob", InvocationContext.EMPTY);
            charlie = dataset.getUser("charlie", InvocationContext.EMPTY);
        });

        aliceCtx = new InvocationContext.Builder().fromUser(alice).build();
        bobCtx = new InvocationContext.Builder().fromUser(bob).build();
        charlieCtx = new InvocationContext.Builder().fromUser(charlie).build();

        //alice becomes an agent
        Txn.executeWrite(dataset, () -> {
            dataset.enableAgent("alice", DUMMY_FACTORY, new JSONObject(), adminCtx);
        });
    }

    @Test
    public void testThatAliceIsAnAgent() {
        User aliceUser = Txn.calculateRead(dataset, () -> {
            return dataset.getUser("alice", InvocationContext.EMPTY);
        });
        Assertions.assertTrue(aliceUser.isAgent());

        Agent aliceAsAgent = (Agent) aliceUser;

        Assertions.assertEquals(DUMMY_FACTORY, aliceAsAgent.getFactory());
        Assertions.assertEquals("{}", aliceAsAgent.getConfig());

        User bob = Txn.calculateRead(dataset, () -> {
            return dataset.getUser("bob", InvocationContext.EMPTY);
        });
        Assertions.assertFalse(bob.isAgent());
    }

    @Test
    public void testSessions() throws InterruptedException {
        Node graph = NodeFactory.createURI("urn:test:graph");

        Node sBob = NodeFactory.createURI("urn:bob:s");
        Node p = NodeFactory.createURI("urn:p");
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
        // 2) Bob inserts triple
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {
            dataset.add(graph, sBob, p, oBob, bobCtx);
        });

        String sessionId = UUID.randomUUID().toString();

        // ---------------------------------------
        // 3) Bob shares SUBJECT with Alice (READ only)
        // ---------------------------------------
        Txn.executeWrite(dataset, () -> {

            dataset.shareResources(
                    Set.of(sBob.getURI()),
                    Set.of(alice.getPrimaryGroup().getUri()),
                    Permission.READ,
                    "this is a dummy message",
                    sessionId,
                    bobCtx
            );
        });

        List<Session> sessions = dataset.getAgentSessionManager().listSessions(bobCtx);

        List<Session> aliceSessions = dataset.getAgentSessionManager().listSessions(aliceCtx);

        Assertions.assertEquals(1, sessions.size());
        Assertions.assertEquals(0, aliceSessions.size());

        Session sessionA = sessions.get(0);

        Assertions.assertEquals(sessionId, sessionA.getSessionId());
        Assertions.assertEquals(alice.getUsername(), sessionA.getAgent().getUsername());
        Assertions.assertEquals((int) bobCtx.getUserId(), sessionA.getPrincipal().getId());
        
        await()
                .atMost(5, SECONDS)
                .untilAsserted(() -> assertEquals(2, sessionA.getMessages().size()));

        await()
                .atMost(5, SECONDS)
                .pollInterval(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    assertFalse(sessionA.getLogRecords().isEmpty());
                });

        //-----------------
        Txn.executeWrite(dataset, () -> {

            dataset.shareResources(
                    Set.of(sBob.getURI()),
                    Set.of(alice.getPrimaryGroup().getUri()),
                    Permission.READ,
                    "this is another message in same session",
                    sessionId,
                    bobCtx
            );
        });

        sessions = dataset.getAgentSessionManager().listSessions(bobCtx);
        Assertions.assertEquals(1, sessions.size());
        Session sessionB = sessions.get(0);
        Assertions.assertEquals(sessionId, sessionB.getSessionId());
        Assertions.assertEquals(alice.getUsername(), sessionB.getAgent().getUsername());
        Assertions.assertEquals((int) bobCtx.getUserId(), sessionB.getPrincipal().getId());

        await()
                .atMost(5, SECONDS)
                .untilAsserted(() -> assertEquals(4, sessionB.getMessages().size()));
    }
}
