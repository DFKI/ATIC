

package de.dfki.sds.aticsqlite;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.NoSuchElementException;
import org.apache.jena.dboe.transaction.txn.TransactionException;
import org.apache.jena.graph.Triple;
import org.apache.jena.util.iterator.NiceIterator;

/**
 *
 */
public class PagedTripleIterator extends NiceIterator<Triple> {

    private final TransactionalResultSet txnResultSet;
    private final Integer limit;
    private final ResultSetTripleMapper mapper;

    private boolean fetched = false;
    private boolean hasNext = false;
    private boolean hasMore = false;
    private boolean closed = false;

    private int returned = 0;
    private Triple nextTriple;
    
    private SqliteAticDatasetGraph datasetGraph;
    
    private final boolean CLOSE_STMT = false;
    private final boolean CLOSE_RESULTSET = true;

    public PagedTripleIterator(TransactionalResultSet rs, Integer limit, SqliteAticDatasetGraph datasetGraph, ResultSetTripleMapper mapper) {
        this.txnResultSet = rs;
        this.limit = limit;
        this.datasetGraph = datasetGraph;
        this.mapper = mapper;
    }

    public boolean hasMore() {
        return hasMore;
    }

    private void fetch() {
        if(!datasetGraph.isInTransaction()) {
            throw new TransactionException();
        }

        if (fetched || closed) {
            return;
        }

        try {

            if (!txnResultSet.getResultSet().next()) {
                hasNext = false;
                close();
                return;
            }

            // limit+1 pagination trick
            if (limit != null && returned >= limit) {
                hasMore = true;
                hasNext = false;
                close();
                return;
            }

            nextTriple = mapper.map(txnResultSet.getResultSet());
            hasNext = true;

        } catch (SQLException e) {
            close();
            throw new RuntimeException(e);
        }

        fetched = true;
    }

    @Override
    public boolean hasNext() {
        fetch();
        return hasNext;
    }

    @Override
    public Triple next() {

        fetch();

        if (!hasNext) {
            throw new NoSuchElementException();
        }

        Triple out = nextTriple;

        returned++;
        fetched = false;
        hasNext = false;
        nextTriple = null;

        return out;
    }

    @Override
    public void close() {

        if (closed) {
            return;
        }

        closed = true;
        
        try {
            Statement stmt = txnResultSet.getResultSet().getStatement();
            
            if(CLOSE_RESULTSET) {
                txnResultSet.close();
            }
            
            if (CLOSE_STMT && stmt != null) {
                stmt.close();
            }
            
        } catch (SQLException ignored) {
        }
        

        super.close();
    }
}
