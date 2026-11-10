package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.nio.file.Path;
import org.apache.jena.system.Txn;
import org.junit.jupiter.api.BeforeEach;

/**
 *
 */
public class BlobGraphUnitTest {

    private SqliteAticDatasetGraph dataset;

    private BlobGraph blobGraph;

    private InvocationContext adminCtx;
    private InvocationContext aliceCtx;
    private InvocationContext bobCtx;

    User bob;

    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);
        blobGraph = dataset.getBlobGraph();

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

        bob = dataset.calculateRead(()
                -> dataset.getUser("bob", InvocationContext.EMPTY)
        );

        adminCtx = new InvocationContext.Builder().fromUser(adminUser).build();
        aliceCtx = new InvocationContext.Builder().fromUser(alice).build();
        bobCtx = new InvocationContext.Builder().fromUser(bob).build();
    }

}
