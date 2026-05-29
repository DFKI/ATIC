

package de.dfki.sds.aticsqlite;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.apache.jena.sparql.core.Transactional;

/**
 *
 */
public interface Database extends Transactional, AutoCloseable {
    
    //read ======================================
    
    public <T> T read(String sql, ResultSetMapper<T> resultSetMapper, Object... params) throws SQLException;
    
    public TransactionalResultSet read(String sql, Object... params) throws SQLException;
 
    //write ======================================
    
    public void write(String sql, Object... params) throws SQLException;
    
    public void writeQuery(String sqlQueryResource, Object... params) throws SQLException;
    
    public long writeReturningId(String sql, Object... params) throws SQLException;
    
    public void writeBatch(String sql, List<Object[]> batchParams, int batchSize) throws SQLException;
    
    public List<Long> writeBatchReturningId(String sql, List<Object[]> batchParams, int batchSize) throws SQLException;
    
    //query logger ===============================
    
    public void enableQueryLogger(String dbFilePath);
    
    public void disableQueryLogger();
    
    //helper =====================================
    
    default void bindParams(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            int idx = i + 1;

            if (p == null) {
                ps.setNull(idx, java.sql.Types.NULL);

            } else if (p instanceof String) {
                ps.setString(idx, (String) p);

            } else if (p instanceof Integer) {
                ps.setInt(idx, (Integer) p);

            } else if (p instanceof Long) {
                ps.setLong(idx, (Long) p);

            } else if (p instanceof Boolean) {
                ps.setBoolean(idx, (Boolean) p);

            } else if (p instanceof Double) {
                ps.setDouble(idx, (Double) p);

            } else if (p instanceof Float) {
                ps.setFloat(idx, (Float) p);

            } else if (p instanceof Short) {
                ps.setShort(idx, (Short) p);

            } else if (p instanceof Byte) {
                ps.setByte(idx, (Byte) p);

            } else if (p instanceof byte[]) {
                ps.setBytes(idx, (byte[]) p);

            } else if (p instanceof java.sql.Date) {
                ps.setDate(idx, (java.sql.Date) p);

            } else if (p instanceof java.sql.Time) {
                ps.setTime(idx, (java.sql.Time) p);

            } else if (p instanceof java.sql.Timestamp) {
                ps.setTimestamp(idx, (java.sql.Timestamp) p);

            } else {
                throw new SQLException("Unsupported parameter type: " + p.getClass());
            }
        }
    }
    
    default String loadSql(String sqlQueryResource) throws SQLException {

        String resourcePath = "de/dfki/sds/aticsqlite/sql/" + sqlQueryResource;

        try (InputStream is = Database.class
                .getClassLoader()
                .getResourceAsStream(resourcePath)) {

            if (is == null) {
                throw new IllegalArgumentException("SQL resource not found: " + resourcePath);
            }

            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

        } catch (IOException e) {
            throw new SQLException("Failed to load SQL resource: " + resourcePath, e);
        }
    }
    
    public File getFolder();
    
    //special ===================================
    
    public boolean isInTransaction(long transactionId);
    
    public TransactionalNullIterator emptyIterator();

}
