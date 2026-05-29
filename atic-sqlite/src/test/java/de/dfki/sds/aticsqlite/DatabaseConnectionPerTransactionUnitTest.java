package de.dfki.sds.aticsqlite;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.jena.query.TxnType;
import org.apache.jena.sparql.JenaTransactionException;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DatabaseConnectionPerTransactionUnitTest {

    private DatabaseConnectionPerTransaction db;

    @BeforeEach
    public void setup(@TempDir Path tempDir) throws SQLException {
        // Build a new SQLite file in a temporary directory
        DatabaseOptions options
                = new DatabaseOptions.Builder(tempDir.resolve("txn.db").toString())
                        .build();

        db = new DatabaseConnectionPerTransaction(options);

        // Create a test table before each test
        db.begin(TxnType.WRITE);
        try {
            db.write("CREATE TABLE test (id INTEGER PRIMARY KEY, val TEXT)");
            db.commit();
        } finally {
            db.end();
        }
    }

    @Test
    public void testReadOutsideTransactionThrows() {
        assertThrows(JenaTransactionException.class, () -> {
            db.read("SELECT * FROM test", rs -> List.of());
        });
    }

    @Test
    public void testWriteOutsideTransactionThrows() {
        assertThrows(JenaTransactionException.class, () -> {
            db.write("INSERT INTO test(val) VALUES(?)", "x");
        });
    }

    @Test
    public void testWriteTransactionCommit() throws SQLException {
        db.begin(TxnType.WRITE);
        try {
            db.write("INSERT INTO test(val) VALUES(?)", "a");
            db.write("INSERT INTO test(val) VALUES(?)", "b");

            // commit should make the changes visible
            db.commit();
        } finally {
            db.end();
        }

        // New transaction to read back
        db.begin(TxnType.READ);
        try {
            List<String> values = db.read(
                    "SELECT val FROM test",
                    rs -> {
                        List<String> list = new java.util.ArrayList<>();
                        while (rs.next()) {
                            list.add(rs.getString("val"));
                        }
                        return list;
                    }
            );

            assertTrue(values.contains("a"));
            assertTrue(values.contains("b"));
        } finally {
            db.end();
        }
    }

    @Test
    public void testWriteTransactionAbort() throws SQLException {
        db.begin(TxnType.WRITE);
        try {
            db.write("INSERT INTO test(val) VALUES(?)", "x");
            // abort so value should not be visible
            db.abort();
        } finally {
            db.end();
        }

        db.begin(TxnType.READ);
        try {
            List<String> values = db.read(
                    "SELECT val FROM test",
                    rs -> {
                        List<String> list = new java.util.ArrayList<>();
                        while (rs.next()) {
                            list.add(rs.getString("val"));
                        }
                        return list;
                    }
            );
            assertFalse(values.contains("x"));
        } finally {
            db.end();
        }
    }

    @Test
    public void testReadThenPromoteToWrite() throws SQLException {
        db.begin(TxnType.READ_PROMOTE);
        try {
            // Read initially
            List<String> before = db.read(
                    "SELECT val FROM test",
                    rs -> {
                        List<String> list = new java.util.ArrayList<>();
                        while (rs.next()) {
                            list.add(rs.getString("val"));
                        }
                        return list;
                    }
            );

            // Should promote automatically when write is attempted  
            db.write("INSERT INTO test(val) VALUES(?)", "promoted");
            db.commit();
        } finally {
            db.end();
        }

        db.begin(TxnType.READ);
        try {
            List<String> after = db.read(
                    "SELECT val FROM test",
                    rs -> {
                        List<String> list = new java.util.ArrayList<>();
                        while (rs.next()) {
                            list.add(rs.getString("val"));
                        }
                        return list;
                    }
            );
            assertTrue(after.contains("promoted"));
        } finally {
            db.end();
        }
    }

    @Test
    void heavyConcurrentWritesTransactional(@TempDir Path tempDir) throws Exception {
        // create an SQLite file in the temporary directory
        Path dbFile = tempDir.resolve("test_tx.db");
        DatabaseOptions options
                = new DatabaseOptions.Builder(dbFile.toString())
                        .build();

        DatabaseConnectionPerTransaction db = new DatabaseConnectionPerTransaction(options);

        // create a simple table first (inside a transaction)
        db.begin(TxnType.WRITE);
        try {
            db.write(
                    "CREATE TABLE IF NOT EXISTS items (id INTEGER PRIMARY KEY AUTOINCREMENT, value TEXT)"
            );
            db.commit();
        } finally {
            db.end();
        }

        final int numThreads = 10;
        final int writesPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    // each thread does its writes inside one transaction
                    db.begin(TxnType.WRITE);
                    try {
                        for (int i = 0; i < writesPerThread; i++) {
                            db.write(
                                    "INSERT INTO items (value) VALUES (?)",
                                    "t-" + Thread.currentThread().getId() + "-" + i
                            );
                        }
                        db.commit();
                    } finally {
                        db.end();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all threads to finish
        assertDoesNotThrow(() -> latch.await());

        executor.shutdown();

        // Optionally verify row count
        db.begin(TxnType.READ);
        try {
            int count = db.read(
                    "SELECT COUNT(*) FROM items",
                    rs -> {
                        rs.next();
                        return rs.getInt(1);
                    }
            );
            assertEquals(numThreads * writesPerThread, count);
        } finally {
            db.end();
        }
    }

    @Test
    void beginTwiceMustNotNest(@TempDir Path tempDir) throws Exception {
        // Create database file
        Path dbFile = tempDir.resolve("nested.db");
        DatabaseOptions options
                = new DatabaseOptions.Builder(dbFile.toString())
                        .build();

        DatabaseConnectionPerTransaction db = new DatabaseConnectionPerTransaction(options);

        // Prepare table
        db.begin(TxnType.WRITE);
        try {
            db.write("CREATE TABLE test (id INTEGER PRIMARY KEY, value TEXT)");
            db.commit();
        } finally {
            db.end();
        }

        // First transaction
        db.begin(TxnType.WRITE);
        try {
            db.write("INSERT INTO test(value) VALUES(?)", "first");

            // Attempt to begin again without ending the first
            assertThrows(
                    org.apache.jena.sparql.JenaTransactionException.class,
                    () -> db.begin(TxnType.WRITE),
                    "begin() twice in a row should not be allowed without committing/ending the first"
            );

            db.commit();
        } finally {
            db.end();
        }

        // After ending the first, starting another must work
        db.begin(TxnType.WRITE);
        try {
            db.write("INSERT INTO test(value) VALUES(?)", "second");
            db.commit();
        } finally {
            db.end();
        }

        // Check results
        db.begin(TxnType.READ);
        try {
            int count = db.read(
                    "SELECT COUNT(*) FROM test",
                    rs -> {
                        rs.next();
                        return rs.getInt(1);
                    }
            );
            assertEquals(2, count);
        } finally {
            db.end();
        }
    }

    /*
    @Disabled(value = "maybe it is ok to read while in write")
    @Test
    void testTransactionVisibilityIsolation() throws Exception {
        // BEGIN a transaction that inserts but does not commit yet
        db.begin(TxnType.WRITE);
        try {
            db.write("INSERT INTO test(val) VALUES(?)", "isolation-test");
            // In another concurrent context without committing:
            assertThrows(
                    JenaTransactionException.class,
                    () -> {
                        // New read outside any transaction should throw
                        db.read("SELECT val FROM test", rs -> List.of());
                    }
            );
        } finally {
            db.abort();
            db.end();
        }
    }
    */
    
    @Test
    void testRollbackDoesNotLeakLocks() throws Exception {
        // Start a write, insert, then rollback
        db.begin(TxnType.WRITE);
        try {
            db.write("INSERT INTO test(val) VALUES(?)", "rollback-test");
            db.abort();
        } finally {
            db.end();
        }

        // After rollback, start another write transaction to ensure lock is cleared
        db.begin(TxnType.WRITE);
        try {
            db.write("INSERT INTO test(val) VALUES(?)", "after-rollback");
            db.commit();
        } finally {
            db.end();
        }

        // Check the committed row exists
        db.begin(TxnType.READ);
        try {
            List<String> rows = db.read(
                    "SELECT val FROM test",
                    rs -> {
                        List<String> list = new ArrayList<>();
                        while (rs.next()) {
                            list.add(rs.getString("val"));
                        }
                        return list;
                    }
            );
            assertTrue(rows.contains("after-rollback"));
            assertFalse(rows.contains("rollback-test"));
        } finally {
            db.end();
        }
    }

    @Test
    void testReadCommittedIsolationBetweenTransactions() throws Exception {
        // Write and commit
        db.begin(TxnType.WRITE);
        try {
            db.write("INSERT INTO test(val) VALUES(?)", "commit-ok");
            db.commit();
        } finally {
            db.end();
        }

        // Start a READ transaction
        db.begin(TxnType.READ);
        try {
            // Insert a new row in a separate write transaction
            Thread writer = new Thread(() -> {
                db.begin(TxnType.WRITE);
                try {
                    assertDoesNotThrow(() -> db.write("INSERT INTO test(val) VALUES(?)", "concurrent-write"));
                    db.commit();
                } finally {
                    db.end();
                }
            });
            writer.start();
            writer.join();

            // Because this READ transaction began before commit, 
            // it may or may not see the newly committed row depending on isolation semantics.
            // Assert not throwing and handling is correct.
            List<String> rows = db.read(
                    "SELECT val FROM test",
                    rs -> {
                        List<String> list = new ArrayList<>();
                        while (rs.next()) {
                            list.add(rs.getString("val"));
                        }
                        return list;
                    }
            );
            assertTrue(rows.contains("commit-ok"));
            assertTrue(rows.contains("concurrent-write"));
        } finally {
            db.end();
        }
    }

    @Test
    void testTransactionRollbackOnException() {
        db.begin(TxnType.WRITE);
        try {
            //the write works
            db.write("INSERT INTO test(val) VALUES(?)", "will-fail");
            //but after that an error occurs
            throw new SQLException("simulates an error");
        } catch (SQLException ex) {
            db.abort();
        } finally {
            // ensure end is called regardless
            db.end();
        }

        // After the error, transaction should be cleaned up
        db.begin(TxnType.READ);
        try {
            assertDoesNotThrow(() -> {
                List<String> rows = db.read(
                        "SELECT val FROM test",
                        rs -> {
                            List<String> list = new ArrayList<>();
                            while (rs.next()) {
                                list.add(rs.getString("val"));
                            }
                            return list;
                        }
                );
                assertFalse(rows.contains("will-fail"));
            });
        } finally {
            db.end();
        }
    }

    @Test
    void concurrentReadsDuringWriteBlockAndDoNotSeeUncommitted(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("concurrent_reads.db");
        DatabaseOptions options
                = new DatabaseOptions.Builder(file.toString())
                        .build();

        DatabaseConnectionPerTransaction reader = new DatabaseConnectionPerTransaction(options);
        DatabaseConnectionPerTransaction writer = new DatabaseConnectionPerTransaction(options);

        // Setup
        writer.begin(TxnType.WRITE);
        try {
            writer.write("CREATE TABLE foo (val TEXT)");
            writer.commit();
        } finally {
            writer.end();
        }

        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);

        // Writer thread holds a long write transaction
        Thread w = new Thread(() -> {
            writer.begin(TxnType.WRITE);
            try {
                writer.write("INSERT INTO foo (val) VALUES (?)", "pending");
                started.countDown();
                // simulate long work
                Thread.sleep(500);
                writer.commit();
            } catch (Exception ignored) {
            } finally {
                writer.end();
                finished.countDown();
            }
        });
        w.start();

        // Wait until writer has written but not committed yet
        started.await();

        // Reader should start a transaction while writer is still open
        reader.begin(TxnType.READ);
        try {
            List<String> values = reader.read("SELECT val FROM foo", rs -> {
                List<String> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(rs.getString("val"));
                }
                return list;
            });
            // Reader should not see uncommitted writer changes
            assertFalse(values.contains("pending"));
        } finally {
            reader.end();
        }

        finished.await();
    }

    @Test
    void concurrentWriteTransactionsSerializeAndAllCommitsSucceed(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("serialize.db");
        
        DatabaseOptions options
                = new DatabaseOptions.Builder(file.toString())
                        .build();
        
        DatabaseConnectionPerTransaction db1 = new DatabaseConnectionPerTransaction(options);
        DatabaseConnectionPerTransaction db2 = new DatabaseConnectionPerTransaction(options);

        // Create table first
        db1.begin(TxnType.WRITE);
        try {
            db1.write("CREATE TABLE bar (val TEXT)");
            db1.commit();
        } finally {
            db1.end();
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);

        // Two threads attempt writes concurrently
        pool.submit(() -> {
            db1.begin(TxnType.WRITE);
            try {
                assertDoesNotThrow(() -> db1.write("INSERT INTO bar (val) VALUES (?)", "one"));
                db1.commit();
            } finally {
                db1.end();
            }
            latch.countDown();
        });

        pool.submit(() -> {
            db2.begin(TxnType.WRITE);
            try {
                assertDoesNotThrow(() -> db2.write("INSERT INTO bar (val) VALUES (?)", "two"));
                db2.commit();
            } finally {
                db2.end();
            }
            latch.countDown();
        });

        assertDoesNotThrow(() -> latch.await());

        pool.shutdown();

        // Both commits should be visible
        db1.begin(TxnType.READ);
        try {
            List<String> rows = db1.read("SELECT val FROM bar", rs -> {
                List<String> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(rs.getString("val"));
                }
                return list;
            });
            assertTrue(rows.contains("one"));
            assertTrue(rows.contains("two"));
        } finally {
            db1.end();
        }
    }

    @Test
    void rollbackInOneThreadDoesNotAffectOtherThread(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("rollback_isolation.db");
        
        DatabaseOptions options
                = new DatabaseOptions.Builder(file.toString())
                        .build();
        
        DatabaseConnectionPerTransaction dbA = new DatabaseConnectionPerTransaction(options);
        DatabaseConnectionPerTransaction dbB = new DatabaseConnectionPerTransaction(options);

        // Setup
        dbA.begin(TxnType.WRITE);
        try {
            dbA.write("CREATE TABLE baz (val TEXT)");
            dbA.commit();
        } finally {
            dbA.end();
        }

        CountDownLatch latch = new CountDownLatch(2);

        // Thread A does a failing transaction
        new Thread(() -> {
            dbA.begin(TxnType.WRITE);
            try {
                dbA.write("INSERT INTO baz (val) VALUES (?)", "failval");
                throw new RuntimeException("should rollback");
            } catch (Throwable ignored) {
                dbA.abort();
            } finally {
                dbA.end();
                latch.countDown();
            }
        }).start();

        // Thread B writes successfully
        new Thread(() -> {
            dbB.begin(TxnType.WRITE);
            try {
                assertDoesNotThrow(() -> dbB.write("INSERT INTO baz (val) VALUES (?)", "goodval"));
                dbB.commit();
            } finally {
                dbB.end();
                latch.countDown();
            }
        }).start();

        assertDoesNotThrow(() -> latch.await());

        // Only goodval should appear
        dbB.begin(TxnType.READ);
        try {
            List<String> rows = dbB.read("SELECT val FROM baz", rs -> {
                List<String> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(rs.getString("val"));
                }
                return list;
            });
            assertFalse(rows.contains("failval"));
            assertTrue(rows.contains("goodval"));
        } finally {
            dbB.end();
        }
    }

}
