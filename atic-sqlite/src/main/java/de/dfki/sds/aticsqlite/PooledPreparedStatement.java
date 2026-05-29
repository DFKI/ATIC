package de.dfki.sds.aticsqlite;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 */
public class PooledPreparedStatement {

    private final PreparedStatement preparedStatement;

    private final Set<ResultSet> openResultSets;

    public PooledPreparedStatement(
            PreparedStatement preparedStatement) {

        if (preparedStatement == null) {
            throw new IllegalArgumentException("preparedStatement must not be null");
        }

        this.preparedStatement = preparedStatement;

        this.openResultSets = ConcurrentHashMap.newKeySet();
    }

    /**
     * Returns the wrapped PreparedStatement.
     */
    public PreparedStatement getPreparedStatement() {

        return preparedStatement;
    }

    /**
     * Registers a ResultSet as active.
     */
    public void registerResultSet(
            ResultSet rs) {

        if (rs != null) {
            openResultSets.add(rs);
        }
    }

    /**
     * Unregisters a ResultSet after close().
     */
    public void unregisterResultSet(
            ResultSet rs) {

        if (rs != null) {
            openResultSets.remove(rs);
        }
    }

    /**
     * Returns true if no ResultSet is active
     * and the statement may safely be reused.
     */
    public boolean isReusable() {

        return openResultSets.isEmpty();
    }

    /**
     * Returns the number of currently open ResultSets.
     */
    public int getOpenResultSetCount() {

        return openResultSets.size();
    }

    /**
     * Returns true if the underlying statement
     * has already been closed.
     */
    public boolean isClosed()
            throws SQLException {

        return preparedStatement.isClosed();
    }

    /**
     * Closes all tracked ResultSets.
     */
    public void closeOpenResultSets() {
        for (ResultSet rs : openResultSets) {
            try {
                rs.close();
            } catch (Exception ignored) {
            }
        }
        openResultSets.clear();
    }

    /**
     * Closes ResultSets and PreparedStatement.
     */
    public void close() {
        closeOpenResultSets();
        try {
            preparedStatement.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public String toString() {

        return "PooledPreparedStatement{"
                + "openResultSets=" + openResultSets.size()
                + '}';
    }
}
