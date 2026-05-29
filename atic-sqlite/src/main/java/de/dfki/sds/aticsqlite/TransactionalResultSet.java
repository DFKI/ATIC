package de.dfki.sds.aticsqlite;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.apache.jena.dboe.transaction.txn.TransactionException;

/**
 *
 */
public final class TransactionalResultSet implements AutoCloseable {

    private final ResultSet resultSet;
    private final long transactionId;
    private final Database owner;
    private final PooledPreparedStatement pps;
    private final List<AutoCloseable> closeListeners;

    public TransactionalResultSet(
            ResultSet resultSet,
            long transactionId,
            PooledPreparedStatement pps,
            Database owner) {
        this.resultSet = resultSet;
        this.transactionId = transactionId;
        this.pps = pps;
        this.owner = owner;
        this.closeListeners = new ArrayList<>();
    }
    
    public void addListener(AutoCloseable autoCloseable) {
        closeListeners.add(autoCloseable);
    }

    public ResultSet getResultSet() {
        // validate transaction still active
        if (!owner.isInTransaction(transactionId)) {
            throw new TransactionException("Not in correct transaction anymore");
        }
        return resultSet;
    }

    @Override
    public void close()
            throws SQLException {

        closeListeners.forEach(c -> {
            try {
                c.close();
            } catch (Exception ex) {
                //ignore
            }
        });
        
        try {
            resultSet.close();
        } finally {
            pps.unregisterResultSet(resultSet);
        }
    }

    public long getTransactionId() {
        return transactionId;
    }

    /*package*/ PooledPreparedStatement getPooledPreparedStatement() {
        return pps;
    }

}
