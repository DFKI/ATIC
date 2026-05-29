

package de.dfki.sds.aticsqlite;

import org.apache.jena.query.Dataset;
import org.apache.jena.sparql.core.DatasetGraph;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
 
/**
 *
 */
public class FactoryUnitTest {

    @Test
    public void createTxn() {
        DatasetGraph dg = AticFactory.createTxn();
        
        String location = dg.getContext().get(SqliteAticDatasetGraph.ATIC_LOCATION);
        
        Assertions.assertNotNull(location);
        Assertions.assertFalse(location.isBlank());
    }
    
    @Test
    public void createTxnAdminDataset() {
        Dataset ds = AticFactory.createTxnAdminDataset();
        
        String location = ds.getContext().get(SqliteAticDatasetGraph.ATIC_LOCATION);
        
        Assertions.assertNotNull(location);
        Assertions.assertFalse(location.isBlank());
    }

}