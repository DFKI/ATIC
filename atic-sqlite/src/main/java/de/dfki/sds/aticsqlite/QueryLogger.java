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

            String durationText = formatDuration(duration);
            String expandedSql = expandSql(sql, params);

            db.write(
                    "INSERT INTO query_log(query_id, user_id, start_time, end_time, duration, duration_text, scope, num_results, params, sql_expanded) "
                    + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?);",
                    queryId,
                    userId,
                    start,
                    end,
                    duration,
                    durationText,
                    scope,
                    numResults,
                    paramsJson,
                    expandedSql
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

    private static String formatDuration(long nanos) {
        long minutes = nanos / 60_000_000_000L;
        long seconds = (nanos % 60_000_000_000L) / 1_000_000_000L;
        long millis = (nanos % 1_000_000_000L) / 1_000_000L;

        return String.format("%02d:%02d:%03d", minutes, seconds, millis);
    }

    private String expandSql(String sql, Object[] params) {
        if (params == null || params.length == 0) {
            return sql;
        }

        StringBuilder out = new StringBuilder();
        int p = 0;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if (c == '?' && p < params.length) {
                out.append(toSqlLiteral(params[p++]));
            } else {
                out.append(c);
            }
        }

        return out.toString();
    }

    private String toSqlLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }

        String s = value.toString().replace("'", "''");
        return "'" + s + "'";
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
        if (parent != null) {
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
