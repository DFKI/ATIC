package de.dfki.sds.aticsqlite;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.jena.dboe.transaction.txn.TransactionException;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.TxnType;
import org.apache.jena.sparql.JenaTransactionException;
import org.sqlite.jdbc3.JDBC3PreparedStatement;

public class DatabaseLongLivedConnection implements Database {

    private final ThreadLocal<ConnectionContext> ctxLocal
            = ThreadLocal.withInitial(this::createContext);

    private final AtomicLong txCounter = new AtomicLong();

    private final File folder;

    //TODO for logging it would be good to have the InvocationContext in every method
    //TODO for ResultSet read it would be good to intercept the close
    private final QueryLogger queryLogger;

    private DatabaseOptions options;

    private final String jdbcUrl;
    private final Properties props;

    public DatabaseLongLivedConnection(DatabaseOptions options) {
        folder = new File(options.getDbFilePath()).getParentFile();

        this.options = options;

        this.jdbcUrl = "jdbc:sqlite:" + options.getDbFilePath();

        // SQLite driver properties
        props = new Properties();

        props.setProperty(
                "busy_timeout",
                String.valueOf(options.getBusyTimeoutMs()));

        props.setProperty(
                "journal_mode",
                options.isEnableWal() ? "WAL" : "DELETE");

        props.setProperty(
                "foreign_keys",
                options.isEnableForeignKeys() ? "true" : "false");

        //TODO add to options
        props.setProperty("synchronous", "NORMAL");
        props.setProperty("temp_store", "MEMORY");
        props.setProperty("cache_size", "-65536");
        props.setProperty("mmap_size", "1073741824");
        props.setProperty("page_size", "32768");
        props.setProperty("locking_mode", "NORMAL");
        props.setProperty("journal_size_limit", "67108864");

        queryLogger = new QueryLogger();
    }

    // ==========================================
    // Connection Context
    // ==========================================
    private static final class ConnectionContext {

        long transactionId;

        final Connection conn;

        Map<String, List<PooledPreparedStatement>> psCache = new ConcurrentHashMap<>();

        TxnType txnType;
        ReadWrite txnMode;

        boolean committed;
        boolean aborted;

        ConnectionContext(Connection conn) {
            this.conn = conn;
        }

        PooledPreparedStatement prepare(String sql)
                throws SQLException {

            List<PooledPreparedStatement> list
                    = psCache.computeIfAbsent(
                            sql,
                            k -> new ArrayList<>());

            // find reusable statement
            for (PooledPreparedStatement pps : list) {
                if (pps.isReusable() && !pps.isClosed()) {
                    return pps;
                }
            }

            // create new statement
            PreparedStatement ps
                    = conn.prepareStatement(sql);

            PooledPreparedStatement pps
                    = new PooledPreparedStatement(ps);

            list.add(pps);

            return pps;
        }

        void close() {

            // close pooled prepared statements
            for (List<PooledPreparedStatement> list
                    : psCache.values()) {

                for (PooledPreparedStatement pps : list) {
                    try {
                        pps.close();
                    } catch (Exception ignored) {
                    }
                }
            }

            psCache.clear();

            // close connection
            try {
                conn.close();
            } catch (Exception ignored) {
            }
        }
    }

    private ConnectionContext createContext() {
        try {
            Connection conn = DriverManager.getConnection(
                    jdbcUrl,
                    props);
            conn.setAutoCommit(true);
            ConnectionContext ctx = new ConnectionContext(conn);
            return ctx;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private ConnectionContext requireCtx() {
        ConnectionContext ctx = ctxLocal.get();
        if (ctx.txnType == null) {
            throw new JenaTransactionException(
                    "Not in transaction");
        }
        return ctx;
    }

    // ==========================================
    // Transaction lifecycle
    // ==========================================
    @Override
    public void begin(TxnType type) {
        ConnectionContext ctx = ctxLocal.get();
        if (ctx == null) {
            throw new JenaTransactionException(
                    "No connection context");
        }

        if (ctx.txnType != null) {
            throw new JenaTransactionException(
                    "Already in transaction");
        }

        try (Statement st
                = ctx.conn.createStatement()) {

            // SQLite-native transaction handling
            if (type == TxnType.WRITE) {

                st.execute("BEGIN IMMEDIATE");
                ctx.txnMode = ReadWrite.WRITE;

            } else {

                st.execute("BEGIN DEFERRED");

                ctx.txnMode = ReadWrite.READ;
            }

            ctx.transactionId = txCounter.incrementAndGet();

            ctx.txnType = type;
            ctx.committed = false;
            ctx.aborted = false;
        } catch (SQLException e) {
            throw new JenaTransactionException(e);
        }
    }

    @Override
    public void commit() {
        ConnectionContext ctx = requireCtx();

        if (ctx.txnType == null) {
            throw new JenaTransactionException(
                    "Not in transaction");
        }

        if (ctx.committed) {
            throw new JenaTransactionException(
                    "Already committed");
        }

        if (ctx.aborted) {
            throw new JenaTransactionException(
                    "Already aborted");
        }

        try (Statement st = ctx.conn.createStatement()) {
            st.execute("COMMIT");
            ctx.committed = true;
        } catch (SQLException e) {
            throw new JenaTransactionException(e);
        } finally {
            clearTxn(ctx);
        }
    }

    @Override
    public void abort() {
        ConnectionContext ctx = requireCtx();
        if (ctx.txnType == null) {
            throw new JenaTransactionException(
                    "Not in transaction");
        }
        if (ctx.aborted) {
            throw new JenaTransactionException(
                    "Already aborted");
        }
        if (ctx.committed) {
            throw new JenaTransactionException(
                    "Already committed");
        }
        try (Statement st
                = ctx.conn.createStatement()) {
            st.execute("ROLLBACK");
            ctx.aborted = true;
        } catch (SQLException e) {
            throw new JenaTransactionException(e);
        } finally {
            clearTxn(ctx);
        }
    }

    private void clearTxn(ConnectionContext ctx) {
        ctx.txnType = null;
        ctx.txnMode = null;
        ctx.committed = false;
        ctx.aborted = false;
    }

    @Override
    public void end() {

        ConnectionContext ctx = ctxLocal.get();

        if (ctx == null || ctx.txnType == null) {
            return;
        }

        try {

            // unfinished WRITE txn -> error
            if ((ctx.txnType == TxnType.WRITE
                    || ctx.txnType == TxnType.READ_PROMOTE
                    || ctx.txnType == TxnType.READ_COMMITTED_PROMOTE)
                    && !ctx.committed
                    && !ctx.aborted) {

                try (Statement st
                        = ctx.conn.createStatement()) {

                    st.execute("ROLLBACK");
                }

                throw new TransactionException(
                        "Write transaction ended without commit/abort");
            }

            // unfinished READ txn -> auto cleanup
            if (ctx.txnType == TxnType.READ
                    && !ctx.committed
                    && !ctx.aborted) {

                try (Statement st
                        = ctx.conn.createStatement()) {

                    st.execute("ROLLBACK");
                }
            }

        } catch (SQLException e) {

            throw new TransactionException(e);

        } finally {

            clearTxn(ctx);
        }
    }

    @Override
    public boolean promote(Promote mode) {

        ConnectionContext ctx = ctxLocal.get();

        if (ctx == null || ctx.txnType == null) {
            return false;
        }

        // already promoted
        if (ctx.txnType == TxnType.WRITE
                || ctx.txnType == TxnType.READ_PROMOTE
                || ctx.txnType == TxnType.READ_COMMITTED_PROMOTE) {
            ctx.txnMode = ReadWrite.WRITE;
            return true;
        }

        if (ctx.txnType != TxnType.READ) {
            return false;
        }

        try (Statement st = ctx.conn.createStatement()) {

            // THIS is the real upgrade
            st.execute("BEGIN IMMEDIATE");

            // IMPORTANT: MUST become WRITE-capable logically
            ctx.txnType = TxnType.WRITE;
            ctx.txnMode = ReadWrite.WRITE;

            return true;

        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public ReadWrite transactionMode() {

        ConnectionContext ctx = ctxLocal.get();

        if (ctx == null) {
            throw new JenaTransactionException("Not in transaction");
        }

        return ctx.txnMode;
    }

    @Override
    public TxnType transactionType() {

        ConnectionContext ctx = ctxLocal.get();

        if (ctx == null) {
            throw new JenaTransactionException("Not in transaction");
        }

        return ctx.txnType;
    }

    @Override
    public boolean isInTransaction() {

        ConnectionContext ctx = ctxLocal.get();

        return ctx != null
                && ctx.txnType != null
                && !ctx.committed
                && !ctx.aborted;
    }

    //read ===================================================
    @Override
    public TransactionalResultSet read(
            String sql,
            Object... params)
            throws SQLException {

        ConnectionContext ctx = requireCtx();
        
        long start = System.nanoTime();

        PooledPreparedStatement pps = ctx.prepare(sql);

        synchronized (pps) {

            PreparedStatement ps = pps.getPreparedStatement();

            // important because statement reused
            ps.clearParameters();

            bindParams(ps, params);
            
            ResultSet rs = ((JDBC3PreparedStatement) ps).executeQuery(true);

            // register active result set
            pps.registerResultSet(rs);

            TransactionalResultSet txnResultSet = new TransactionalResultSet(
                    rs,
                    ctx.transactionId,
                    pps,
                    this);
            
            if(queryLogger.isEnabled()) {
                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                String callerMethod = stack[2].getMethodName();
                
                txnResultSet.addListener(new AutoCloseable() {
                    @Override
                    public void close() throws Exception {
                        long end = System.nanoTime();
                        queryLogger.log(callerMethod, sql, params, start, end, -1, -1);
                    }
                });
            }
            
            return txnResultSet;
        }
    }

    @Override
    public <T> T read(
            String sql,
            ResultSetMapper<T> mapper,
            Object... params)
            throws SQLException {

        ConnectionContext ctx = requireCtx();

        PooledPreparedStatement pps = ctx.prepare(sql);

        synchronized (pps) {

            PreparedStatement ps = pps.getPreparedStatement();

            ps.clearParameters();

            bindParams(ps, params);
            
            try (ResultSet rs
                    = ps.executeQuery()) {

                return mapper.map(rs);
            }
        }
    }

    //write ==================================================
    @Override
    public void write(
            String sql,
            Object... params)
            throws SQLException {

        ConnectionContext ctx = requireCtx();

        PooledPreparedStatement pps = ctx.prepare(sql);

        synchronized (pps) {

            PreparedStatement ps = pps.getPreparedStatement();

            ps.clearParameters();

            bindParams(ps, params);

            ps.executeUpdate();
        }
    }

    @Override
    public void writeQuery(String sqlQueryResource, Object... params)
            throws SQLException {

        write(loadSql(sqlQueryResource), params);
    }

    @Override
    public void writeBatch(
            String sql,
            List<Object[]> batchParams,
            int batchSize
    ) throws SQLException {

        ConnectionContext ctx = requireCtx();

        PooledPreparedStatement pps = ctx.prepare(sql);

        synchronized (pps) {

            PreparedStatement ps = pps.getPreparedStatement();

            int count = 0;

            for (Object[] params : batchParams) {

                ps.clearParameters();
                bindParams(ps, params);

                ps.addBatch();
                count++;

                if (count >= batchSize) {
                    ps.executeBatch();
                    count = 0;
                }
            }

            if (count > 0) {
                ps.executeBatch();
            }
        }
    }

    @Override
    public long writeReturningId(String sql, Object... params)
            throws SQLException {

        ConnectionContext ctx = requireCtx();

        PooledPreparedStatement pps = ctx.prepare(sql);

        synchronized (pps) {

            PreparedStatement ps = pps.getPreparedStatement();

            ps.clearParameters();
            bindParams(ps, params);

            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {

                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }

        throw new SQLException(
                "Insert did not return generated key");
    }

    @Override
    public List<Long> writeBatchReturningId(
            String sql,
            List<Object[]> batchParams,
            int batchSize
    ) throws SQLException {

        List<Long> ids = new ArrayList<>();

        ConnectionContext ctx = requireCtx();

        PooledPreparedStatement pps = ctx.prepare(sql);

        synchronized (pps) {

            PreparedStatement ps = pps.getPreparedStatement();

            int count = 0;

            for (Object[] params : batchParams) {

                ps.clearParameters();
                bindParams(ps, params);

                ps.addBatch();
                count++;

                if (count >= batchSize) {

                    executeAndCollect(ps, ids);
                    count = 0;
                }
            }

            if (count > 0) {
                executeAndCollect(ps, ids);
            }
        }

        return ids;
    }

    private void executeAndCollect(
            PreparedStatement stmt,
            List<Long> ids
    ) throws SQLException {

        stmt.executeBatch();

        try (ResultSet rs = stmt.getGeneratedKeys()) {

            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
    }

    @Override
    public void close() {

        ConnectionContext ctx = ctxLocal.get();

        if (ctx != null) {

            ctx.close();

            ctxLocal.remove();
        }

        //ds.close();
    }

    @Override
    public File getFolder() {
        return folder;
    }

    //special =================================================
    @Override
    public void enableQueryLogger(String dbFilePath) {
        queryLogger.enable(dbFilePath);
    }

    @Override
    public void disableQueryLogger() {
        queryLogger.disable();
    }

    @Override
    public boolean isInTransaction(long transactionId) {
        ConnectionContext ctx = ctxLocal.get();

        if (ctx == null
                || ctx.txnType == null
                || ctx.transactionId != transactionId) {

            return false;
        }

        return true;
    }

    @Override
    public TransactionalNullIterator emptyIterator() {
        ConnectionContext ctx = requireCtx();
        return new TransactionalNullIterator(ctx.transactionId, this);
    }

}
