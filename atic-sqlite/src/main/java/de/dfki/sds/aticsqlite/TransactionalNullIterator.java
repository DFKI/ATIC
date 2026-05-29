

package de.dfki.sds.aticsqlite;

import org.apache.jena.dboe.transaction.txn.TransactionException;
import org.apache.jena.util.iterator.NullIterator;

/**
 * Is a null iterator but checks that it is still in the correct transaction.
 */
public class TransactionalNullIterator extends NullIterator {
    
    private long transactionId;
    private Database owner;

    public TransactionalNullIterator(long transactionId, Database owner) {
        this.transactionId = transactionId;
        this.owner = owner;
    }

    @Override
    public Object next() {
        if(!owner.isInTransaction(transactionId)) {
            throw new TransactionException();
        }
        return super.next();
    }
    
    
    
}
