package de.dfki.sds.aticsqlite;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.sqlite.SQLiteConnection;
import org.sqlite.core.DB;
import org.sqlite.jdbc3.JDBC3Connection;
import org.sqlite.jdbc4.JDBC4PreparedStatement;

/**
 *
 */
public class AticPreparedStatement extends JDBC4PreparedStatement {

    protected AticPreparedStatement(SQLiteConnection conn, String sql) throws SQLException {
        super(conn, sql);
    }

    public ResultSet executeQuery(boolean skipColsMeta) throws SQLException {
        checkOpen();

        if (columnCount == 0) {
            throw new SQLException("Query does not return results");
        }

        rs.close();
        pointer.safeRunConsume(DB::reset);
        exhaustedResults = false;

        if (this.conn instanceof JDBC3Connection) {
            ((JDBC3Connection) this.conn).tryEnforceTransactionMode();
        }

        return this.withConnectionTimeout(
                () -> {
                    boolean success = false;
                    try {
                        resultsWaiting = conn.getDatabase().execute(this, batch);
                        success = true;
                    } finally {
                        if (!success && !pointer.isClosed()) {
                            pointer.safeRunInt(DB::reset);
                        }
                    }
                    return getResultSet(skipColsMeta);
                });
    }
    
    public ResultSet getResultSet(boolean skipColsMeta) throws SQLException {
        checkOpen();

        if (exhaustedResults) return null;

        if (rs.isOpen()) {
            throw new SQLException("ResultSet already requested");
        }

        int columnCount = pointer.safeRunInt(DB::column_count);
        
        if (columnCount == 0) {
            return null;
        }

        if(skipColsMeta) {
            rs.colsMeta = new String[columnCount];
        } else {
            if(rs.colsMeta == null) {
                rs.colsMeta = pointer.safeRun(DB::column_names);
            }
        }

        rs.cols = rs.colsMeta;
        rs.emptyResultSet = !resultsWaiting;
        rs.open = true;
        resultsWaiting = false;

        return (ResultSet) rs;
    }

}
