package de.dfki.sds.aticsqlite;

import java.nio.file.Path;
import java.sql.PreparedStatement;
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

public class DatabaseLongLivedConnectionUnitTest {

    private DatabaseLongLivedConnection db;

    @BeforeEach
    public void setup(@TempDir Path tempDir) throws SQLException {
        // Build a new SQLite file in a temporary directory
        DatabaseOptions options
                = new DatabaseOptions.Builder(tempDir.resolve("txn.db").toString())
                        .build();

        db = new DatabaseLongLivedConnection(options);

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
    public void testPreparedStatementReuseAndParallelResultSets()
            throws Exception {

        db.begin(TxnType.WRITE);

        try {

            db.write(
                    "INSERT INTO test(val) VALUES (?)",
                    "a");

            db.write(
                    "INSERT INTO test(val) VALUES (?)",
                    "b");

            db.commit();

        } finally {

            db.end();
        }

        db.begin(TxnType.READ);

        try {

            String sql
                    = "SELECT * FROM test ORDER BY id";

            // first query
            TransactionalResultSet trs1
                    = db.read(sql);

            PreparedStatement ps1
                    = trs1.getResultSet()
                            .getStatement()
                            .unwrap(PreparedStatement.class);

            int psHash1
                    = System.identityHashCode(ps1);

            // second query while first ResultSet still open
            TransactionalResultSet trs2
                    = db.read(sql);

            PreparedStatement ps2
                    = trs2.getResultSet()
                            .getStatement()
                            .unwrap(PreparedStatement.class);

            int psHash2
                    = System.identityHashCode(ps2);

            // must NOT reuse same statement
            // because first ResultSet still active
            assertNotEquals(psHash1, psHash2);

            // close first ResultSet
            trs1.close();

            // third query
            TransactionalResultSet trs3
                    = db.read(sql);

            PreparedStatement ps3
                    = trs3.getResultSet()
                            .getStatement()
                            .unwrap(PreparedStatement.class);

            int psHash3
                    = System.identityHashCode(ps3);

            // now statement reuse should happen
            assertTrue(
                    psHash3 == psHash1
                    || psHash3 == psHash2);

            trs2.close();
            trs3.close();

        } finally {

            db.end();
        }
    }

    @Test
    public void testTransactionIdChangesBetweenTransactions()
            throws Exception {

        // ---------- first transaction ----------
        db.begin(TxnType.READ);

        long txId1;

        try {

            TransactionalResultSet trs
                    = db.read("SELECT * FROM test");

            txId1 = trs.getTransactionId();

            assertTrue(txId1 > 0);

            trs.close();

        } finally {

            db.end();
        }

        // ---------- second transaction ----------
        db.begin(TxnType.READ);

        long txId2;

        try {

            TransactionalResultSet trs
                    = db.read("SELECT * FROM test");

            txId2 = trs.getTransactionId();

            assertTrue(txId2 > 0);

            trs.close();

        } finally {

            db.end();
        }

        // transaction ids must differ
        assertNotEquals(txId1, txId2);
    }

    @Test
    public void testTransactionIdConsistencyWithinTransaction()
            throws Exception {

        // ---------- first transaction ----------
        db.begin(TxnType.READ);

        long txId1a;
        long txId1b;

        try {

            TransactionalResultSet trs1
                    = db.read("SELECT * FROM test");

            TransactionalResultSet trs2
                    = db.read("SELECT * FROM test");

            txId1a = trs1.getTransactionId();
            txId1b = trs2.getTransactionId();

            // same transaction -> same id
            assertEquals(txId1a, txId1b);

            assertTrue(txId1a > 0);

            trs1.close();
            trs2.close();

        } finally {

            db.end();
        }

        // ---------- second transaction ----------
        db.begin(TxnType.READ);

        long txId2;

        try {

            TransactionalResultSet trs3
                    = db.read("SELECT * FROM test");

            txId2 = trs3.getTransactionId();

            assertTrue(txId2 > 0);

            trs3.close();

        } finally {

            db.end();
        }

        // different transaction -> different id
        assertNotEquals(txId1a, txId2);
    }

    @Test
    public void testPreparedStatementPoolParallelSafety()
            throws Exception {

        db.begin(TxnType.WRITE);

        try {
            db.write("INSERT INTO test(val) VALUES (?)", "a");
            db.write("INSERT INTO test(val) VALUES (?)", "b");
            db.commit();
        } finally {
            db.end();
        }

        db.begin(TxnType.READ);

        try {

            String sql = "SELECT * FROM test ORDER BY id";

            // first ResultSet
            TransactionalResultSet rs1
                    = db.read(sql);

            PooledPreparedStatement pps1
                    = rs1.getPooledPreparedStatement();

            int stmt1
                    = System.identityHashCode(
                            pps1.getPreparedStatement());

            // second ResultSet BEFORE closing first
            TransactionalResultSet rs2
                    = db.read(sql);

            PooledPreparedStatement pps2
                    = rs2.getPooledPreparedStatement();

            int stmt2
                    = System.identityHashCode(
                            pps2.getPreparedStatement());

            // MUST be different statements (pool safety rule)
            assertNotEquals(stmt1, stmt2);

            // close first ResultSet -> statement becomes reusable
            rs1.close();

            TransactionalResultSet rs3
                    = db.read(sql);

            PooledPreparedStatement pps3
                    = rs3.getPooledPreparedStatement();

            int stmt3
                    = System.identityHashCode(
                            pps3.getPreparedStatement());

            // now reuse is allowed (same or one of previous)
            assertTrue(
                    stmt3 == stmt1 || stmt3 == stmt2);

            rs2.close();
            rs3.close();

        } finally {
            db.end();
        }
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
    void beginTwiceMustNotNest(@TempDir Path tempDir) throws Exception {
        // Create database file
        Path dbFile = tempDir.resolve("nested.db");
        DatabaseOptions options
                = new DatabaseOptions.Builder(dbFile.toString())
                        .build();

        DatabaseLongLivedConnection db = new DatabaseLongLivedConnection(options);

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

        DatabaseLongLivedConnection reader = new DatabaseLongLivedConnection(options);
        DatabaseLongLivedConnection writer = new DatabaseLongLivedConnection(options);

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

        DatabaseLongLivedConnection db1 = new DatabaseLongLivedConnection(options);
        DatabaseLongLivedConnection db2 = new DatabaseLongLivedConnection(options);

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

        DatabaseLongLivedConnection dbA = new DatabaseLongLivedConnection(options);
        DatabaseLongLivedConnection dbB = new DatabaseLongLivedConnection(options);

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
