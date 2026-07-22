

package de.dfki.sds.aticserver;

import de.dfki.sds.atic.jenatic.AticDatasetGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import org.apache.jena.rdfpatch.RDFPatch;
import org.json.JSONObject;

/**
 *
 */
public class RdfJsonBridge {
    
    //use for GET (QUERY)
    JSONObject toJson(
        JSONObject projection,
        AticDatasetGraph datasetGraph,
        InvocationContext ctx
    ) {
        return null;
    }

    //use for POST, PUT, PATCH, DELETE
    RDFPatch toPatch(
        String operation,
        JSONObject data,
        JSONObject projection,
        AticDatasetGraph datasetGraph,
        InvocationContext ctx
    ) {
        return null;
    }
    
    
}
