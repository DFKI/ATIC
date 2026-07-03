

package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.Agent;
import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.nio.file.Path;
import org.apache.jena.system.Txn;
import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
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
            dataset.enableAgent("alice", "de.dfki.sds.aticsqlite.agent.create", new JSONObject(), adminCtx);
        });
    }

    @Test
    public void testThatAliceIsAnAgent() {
        User aliceUser = Txn.calculateRead(dataset, () -> {
            return dataset.getUser("alice", InvocationContext.EMPTY);
        });
        Assertions.assertTrue(aliceUser.isAgent());
        
        Agent aliceAsAgent = (Agent) aliceUser;
        
        Assertions.assertEquals("de.dfki.sds.aticsqlite.agent.create", aliceAsAgent.getFactory());
        Assertions.assertEquals("{}", aliceAsAgent.getConfig());
        
        User bob = Txn.calculateRead(dataset, () -> {
            return dataset.getUser("bob", InvocationContext.EMPTY);
        });
        Assertions.assertFalse(bob.isAgent());
    }
}