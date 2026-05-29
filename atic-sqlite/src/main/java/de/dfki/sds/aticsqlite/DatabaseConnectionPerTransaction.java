package de.dfki.sds.aticsqlite;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.TxnType;
import org.apache.jena.sparql.JenaTransactionException;

/**
 * 
 * @deprecated use {@link DatabaseConnectionPool}
 */
@Deprecated
public class DatabaseConnectionPerTransaction implements Database {

    private final String url;
    private final int busyTimeoutMs;
    private final boolean enableWal;
    private final boolean enableForeignKeys;

    private final ThreadLocal<Connection> transConn = new ThreadLocal<>();
    private final ThreadLocal<TxnType> transType = new ThreadLocal<>();
    private final ThreadLocal<Boolean> transCommitted = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> transAborted = ThreadLocal.withInitial(() -> false);

    //TODO for logging it would be good to have the InvocationContext in every method
    //TODO for ResultSet read it would be good to intercept the close
    private QueryLogger queryLogger;

    private File folder;

    public DatabaseConnectionPerTransaction(DatabaseOptions options) {
        File f = new File(options.getDbFilePath());
        folder = f.getParentFile();

        this.url = "jdbc:sqlite:" + f.getAbsolutePath();
        this.busyTimeoutMs = options.getBusyTimeoutMs();
        this.enableWal = options.isEnableWal();
        this.enableForeignKeys = options.isEnableForeignKeys();

        queryLogger = new QueryLogger();
    }
    
    @Override
    public TransactionalNullIterator emptyIterator() {
        Connection conn = transConn.get();
        if (conn == null) {
            throw new org.apache.jena.sparql.JenaTransactionException("Not in a transaction");
        }
        return new TransactionalNullIterator(conn.hashCode(), this);
    }

    //TODO implement queryLogger also in the other database methods
    @Override
    public void enableQueryLogger(String dbFilePath) {
        queryLogger.enable(dbFilePath);
    }

    @Override
    public void disableQueryLogger() {
        queryLogger.disable();
    }

    private void applyPragmas(Connection conn) throws SQLException {
        if (busyTimeoutMs > 0) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA busy_timeout = " + busyTimeoutMs + ";");
            }
        }
        if (enableWal) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode = WAL;");
            }
        }
        if (enableForeignKeys) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON;");
            }
        }
    }

    @Override
    public <T> T read(
            String sql,
            ResultSetMapper<T> resultSetMapper,
            Object... params
    ) throws SQLException {

        Connection conn = transConn.get();
        if (conn == null) {
            throw new org.apache.jena.sparql.JenaTransactionException("Not in a transaction");
        }

        // **prevent read from executing within an active write transaction**
        //if (transactionMode() == ReadWrite.WRITE) {
        //    throw new org.apache.jena.sparql.JenaTransactionException(
        //            "Cannot perform read while in a write transaction"
        //    );
        //}
        long start = System.nanoTime();

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                T result = resultSetMapper.map(rs);
                long end = System.nanoTime();

                queryLogger.log("read", sql, params, start, end, -1, null);

                return result;
            }
        }
    }

    @Override
    public TransactionalResultSet read(String sql, Object... params) throws SQLException {
        Connection conn = transConn.get();
        if (conn == null) {
            throw new org.apache.jena.sparql.JenaTransactionException("Not in a transaction");
        }

        // Prepare the statement, bind the parameters and return the live ResultSet.
        // The caller is responsible for closing the ResultSet (which will also close
        // the underlying PreparedStatement) when finished.
        PreparedStatement ps = conn.prepareStatement(sql);
        bindParams(ps, params);
        ResultSet rs = ps.executeQuery();
        
        return new TransactionalResultSet(rs, conn.hashCode(), new PooledPreparedStatement(ps), this);
    }
        
    @Override
    public void write(String sql, Object... params) throws SQLException {
        Connection conn = transConn.get();
        if (conn == null) {
            throw new org.apache.jena.sparql.JenaTransactionException("Not in a transaction");
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, params);
            ps.executeUpdate();
        }
    }

    @Override
    public void writeQuery(String sqlQueryResource, Object... params) throws SQLException {
        write(loadSql(sqlQueryResource), params);
    }

    @Override
    public long writeReturningId(String sql, Object... params) throws SQLException {

        Connection conn = transConn.get();
        if (conn == null) {
            throw new org.apache.jena.sparql.JenaTransactionException("Not in a transaction");
        }

        try (PreparedStatement ps
                = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            bindParams(ps, params);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }

        throw new SQLException("Insert did not return generated key");
    }

    @Override
    public void writeBatch(
            String sql,
            List<Object[]> batchParams,
            int batchSize
    ) throws SQLException {

        Connection conn = transConn.get();
        if (conn == null) {
            throw new org.apache.jena.sparql.JenaTransactionException("Not in a transaction");
        }

        try (PreparedStatement ps = conn.prepareStatement(sql)) {

            int count = 0;

            for (Object[] params : batchParams) {
                bindParams(ps, params);
                ps.addBatch();
                count++;

                if (count >= batchSize) {
                    ps.executeBatch();
                    count = 0;
                }
            }
            // execute remaining
            ps.executeBatch();
        }
    }

    @Override
    public List<Long> writeBatchReturningId(
            String sql,
            List<Object[]> batchParams,
            int batchSize
    ) throws SQLException {

        List<Long> returningIds = new ArrayList<>();

        Connection conn = transConn.get();
        if (conn == null) {
            throw new SQLException("Not in a transaction");
        }

        try (PreparedStatement stmt
                = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            int count = 0;

            for (Object[] params : batchParams) {
                bindParams(stmt, params);
                stmt.addBatch();
                count++;

                if (count == batchSize) {
                    executeAndCollect(stmt, returningIds);
                    count = 0;
                }
            }

            // remaining
            if (count > 0) {
                executeAndCollect(stmt, returningIds);
            }
        }

        return returningIds;
    }

    private void executeAndCollect(
            PreparedStatement stmt,
            List<Long> returningIds
    ) throws SQLException {

        stmt.executeBatch();

        try (ResultSet rs = stmt.getGeneratedKeys()) {
            while (rs.next()) {
                returningIds.add(rs.getLong(1));
            }
        }
    }

    // ---- Transactional API implementations ----
    @Override
    public void begin(TxnType type) {
        Connection conn2 = transConn.get();
        if (conn2 != null) {
            throw new JenaTransactionException();
        }

        try {
            // Build connection properties with transaction_mode
            Properties props = new Properties();
            props.setProperty("transaction_mode",
                    (type == TxnType.WRITE) ? "IMMEDIATE" : "DEFERRED");

            Connection conn = DriverManager.getConnection(url, props);
            applyPragmas(conn);

            // Automatically starts a transaction in the configured mode
            conn.setAutoCommit(false);

            transConn.set(conn);
            transType.set(type);

        } catch (SQLException e) {
            throw new JenaTransactionException("Unable to begin transaction", e);
        }
    }

    @Override
    public boolean promote(Promote mode) {
        Connection conn = transConn.get();
        if (conn == null) {
            return false;
        }
        TxnType current = transType.get();
        if (current == TxnType.WRITE) {
            return true;
        }
        if (current == TxnType.READ && mode == Promote.READ_COMMITTED) {
            return false;
        }
        if (current == TxnType.READ && mode == Promote.ISOLATED) {
            return false;
        }
        try (Statement stmt = conn.createStatement()) {
            // A promotion for SQLite is effectively marking a savepoint
            stmt.execute("SAVEPOINT promote_sp;");
            transType.set(TxnType.WRITE);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void commit() {
        Connection conn = transConn.get();
        if (conn == null) {
            throw new JenaTransactionException();
        }

        //twice check
        if (Boolean.TRUE.equals(transCommitted.get())) {
            throw new JenaTransactionException();
        }

        //commit vs abort
        if (Boolean.TRUE.equals(transAborted.get())) {
            throw new JenaTransactionException();
        }

        try {
            conn.commit();
            transCommitted.set(true);
        } catch (SQLException e) {
            throw new RuntimeException("Commit failed", e);
        }

        finish();
    }

    @Override
    public void abort() {
        Connection conn = transConn.get();
        if (conn == null) {
            throw new JenaTransactionException();
        }

        //twice check
        if (Boolean.TRUE.equals(transAborted.get())) {
            throw new JenaTransactionException();
        }

        //abort vs commit
        if (Boolean.TRUE.equals(transCommitted.get())) {
            throw new JenaTransactionException();
        }

        try {
            conn.rollback();
            transAborted.set(true);
        } catch (SQLException e) {
            throw new JenaTransactionException("Rollback failed", e);
        }

        finish();
    }

    private void finish() {
        Connection conn = transConn.get();
        if (conn == null) {
            throw new JenaTransactionException();
        }

        try {
            conn.close();
        } catch (SQLException ex) {
            throw new JenaTransactionException(ex);
        }

        transConn.remove();
        transType.remove();
        transCommitted.remove();
        transAborted.remove();
    }

    @Override
    public void end() {
        //nothing to do if abort or commit was called
        Connection conn = transConn.get();
        if (conn == null) {
            return;
        }

        //write but no commit and abort
        if ((transType.get() == TxnType.WRITE || transType.get() == TxnType.READ_PROMOTE)
                && Boolean.FALSE.equals(transCommitted.get())
                && Boolean.FALSE.equals(transAborted.get())) {
            throw new JenaTransactionException();
        }

        finish();

        /*
        try {
            if (Boolean.TRUE.equals(transCommitted.get())) {
                conn.commit();  // only commit if commit() was called
            } else {
                conn.rollback(); // otherwise roll back
            }
        } catch (SQLException ignored) {
            try {
                conn.rollback();
            } catch (SQLException ignored2) {
            }
        } finally {
            try {
                conn.close();
            } catch (SQLException ignored) {
            }
            transConn.remove();
            transType.remove();
            transCommitted.remove();
            transAborted.remove();
        }
         */
    }

    @Override
    public ReadWrite transactionMode() {
        TxnType type = transType.get();
        if (type == null) {
            return null;
        }
        return (type == TxnType.WRITE) ? ReadWrite.WRITE : ReadWrite.READ;
    }

    @Override
    public TxnType transactionType() {
        return transType.get();
    }

    @Override
    public boolean isInTransaction() {
        return transConn.get() != null && Boolean.FALSE.equals(transCommitted.get()) && Boolean.FALSE.equals(transAborted.get());
    }

    @Override
    public File getFolder() {
        return folder;
    }

    //for transaction identification
    public long getConnectionHashCode() {
        Connection conn = transConn.get();
        if (conn == null) {
            throw new JenaTransactionException();
        }
        return conn.hashCode();
    }

    public boolean isInTransaction(long transactionId) {
        return isInTransaction() && getConnectionHashCode() == transactionId;
    }

    @Override
    public void close() throws Exception {
        
    }

}
