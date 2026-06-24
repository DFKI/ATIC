package de.dfki.sds.aticsqlite;

import java.io.File;
import java.sql.SQLException;
import org.apache.jena.query.TxnType;
import org.json.JSONArray;

/**
 *
 */
public class QueryLogger {

    private boolean enabled;

    private DatabaseConnectionPerTransaction db;

    public QueryLogger() {
        this.enabled = false;

    }

    private void bootstrapTables() {
        db.begin(TxnType.WRITE);
        try {

            db.writeQuery("create_table_query.sql");
            db.writeQuery("create_table_query_log.sql");

            db.commit();
        } catch (SQLException e) {
            db.abort();
            throw new RuntimeException("Failed to bootstrap tables", e);
        } finally {
            db.end();
        }
    }

    public void log(String scope, String sql, Object[] params, long start, long end, long numResults, Integer userId) {
        if (!enabled) {
            return;
        }

        String paramsJson = paramsToJson(params);
        long duration = end - start;

        try {
            // Begin transaction (assume TxnType.WRITE exists)
            db.begin(TxnType.WRITE);

            // Get or create query_id
            long queryId = getOrCreateQueryId(sql);

            // Insert into query_log
            db.write(
                    "INSERT INTO query_log(query_id, user_id, start_time, end_time, duration, scope, num_results, params) "
                    + "VALUES(?, ?, ?, ?, ?, ?, ?, ?);",
                    queryId,
                    userId,
                    start,
                    end,
                    duration,
                    scope,
                    numResults,
                    paramsJson
            );

            // Commit transaction
            db.commit();
        } catch (SQLException e) {
            db.abort();
            throw new RuntimeException(e);
        } finally {
            db.end();
        }
    }

// Convert parameters to JSON array string
    private String paramsToJson(Object[] params) {
        if (params == null || params.length == 0) {
            return "[]";
        }
        return new JSONArray(params).toString();
    }

// Get or create query_id using writeReturningId
    private long getOrCreateQueryId(String sql) throws SQLException {
        // Read the id
        Long queryId = db.read(
                "SELECT id FROM query WHERE sql = ?;",
                rs -> rs.next() ? rs.getLong("id") : null,
                sql
        );

        if (queryId == null) {
            // Insert if not exists
            queryId = db.writeReturningId("INSERT OR IGNORE INTO query(sql) VALUES(?);", sql);
        }
        return queryId;
    }

    //======================================
    public boolean isEnabled() {
        return enabled;
    }

    private void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void enable(String dbFilePath) {
        File f = new File(dbFilePath);
        File parent = f.getParentFile();
        if(parent != null) {
            parent.mkdirs();
        }
        DatabaseOptions options
                = new DatabaseOptions.Builder(dbFilePath)
                        .build();
        this.db = new DatabaseConnectionPerTransaction(options);
        bootstrapTables();
        setEnabled(true);
    }

    public void disable() {
        setEnabled(false);
        this.db = null;
    }
}
