package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.Group;
import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.nio.file.Path;
import java.util.Set;
import org.apache.jena.query.TxnType;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 *
 */
public class BootstrapUnitTest {

    @Test
    void testBootstrapCreatesAllTables(@TempDir Path tempDir) throws Exception {
        // constructing the dataset graph should trigger bootstrap()
        SqliteAticDatasetGraph dataset = TL.createDatasetGraph(tempDir);

        Set<String> expectedTables = Set.of(
                "user",
                "group",
                "user_group_assignment",
                "graph",
                "graph_acl",
                "resource",
                "resource_uri",
                "resource_acl",
                "resource_acl_effective",
                "resource_spo",
                "resource_spl",
                "property",
                "spog",
                "splg",
                "prefixmap"
        );

        dataset.begin(TxnType.READ);
        try {
            for (String table : expectedTables) {

                boolean exists = dataset.getDatabase().read(
                        "SELECT EXISTS(SELECT name FROM sqlite_master WHERE type='table' AND name=?)",
                        rs -> {
                            rs.next();
                            return rs.getInt(1) == 1;
                        },
                        table
                );

                assertTrue(exists, "Table should exist: " + table);
            }
        } finally {
            dataset.end();
        }
    }

    @Test
    void testBootstrapCreatesAdminUserWithGetUser(@TempDir Path tempDir) throws Exception {
        // bootstrap triggered by constructor
        SqliteAticDatasetGraph dataset = TL.createDatasetGraph(tempDir);

        // use getUser API on the dataset
        dataset.begin(TxnType.READ);
        User adminUser = dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        dataset.end();

        assertNotNull(adminUser, "Admin user should be returned by getUser");
        assertEquals(UserGroupManagement.ADMIN_USERNAME, adminUser.getUsername(),
                "Admin user's username should contain the admin username");

        // Also double‑check via direct query
        dataset.begin(TxnType.READ);
        try {
            boolean exists = dataset.getDatabase().read(
                    "SELECT EXISTS(SELECT 1 FROM user WHERE username = ?)",
                    rs -> {
                        rs.next();
                        return rs.getInt(1) == 1;
                    },
                    UserGroupManagement.ADMIN_USERNAME
            );

            assertTrue(exists, "Admin user should exist in the user table");
        } finally {
            dataset.end();
        }
    }

    @Test
    void testCannotCreateDuplicateAdminUser(@TempDir Path tempDir) throws Exception {
        // bootstrap triggered by constructor
        SqliteAticDatasetGraph dataset = TL.createDatasetGraph(tempDir);

        // Attempt to add the admin user again -> should fail
        assertThrows(RuntimeException.class, () -> {
            dataset.begin(TxnType.WRITE);
            try {
                dataset.addUser("", "", "", UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
            } finally {
                dataset.end();
            }
        });
    }

    @Test
    void testBootstrapCreatesEveryoneGroup(@TempDir Path tempDir) throws Exception {
        // bootstrap triggered by constructor
        SqliteAticDatasetGraph dataset = TL.createDatasetGraph(tempDir);

        // use getGroup API
        dataset.begin(TxnType.READ);
        Group everyoneGroup = dataset.getGroup(UserGroupManagement.EVERYONE_GROUP, InvocationContext.EMPTY);
        dataset.end();

        assertNotNull(everyoneGroup, "Everyone group should exist");
        assertEquals(UserGroupManagement.EVERYONE_GROUP, everyoneGroup.getGroupname(),
                "Group name should match EVERYONE_GROUP");

        // Optional: verify directly in DB as well
        dataset.begin(TxnType.READ);
        try {
            boolean exists = dataset.getDatabase().read(
                    "SELECT EXISTS(SELECT 1 FROM \"group\" WHERE groupname = ?)",
                    rs -> {
                        rs.next();
                        return rs.getInt(1) == 1;
                    },
                    UserGroupManagement.EVERYONE_GROUP
            );

            assertTrue(exists, "Everyone group should exist in the group table");
        } finally {
            dataset.end();
        }
    }
    
    
}
