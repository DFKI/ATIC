

package de.dfki.sds.aticsqlite;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase0;

/**
 *
 */
public class SparqlFunctionCreateURN extends FunctionBase0 {
    
    private SqliteAticDatasetGraph datasetGraph;
    
    public SparqlFunctionCreateURN(SqliteAticDatasetGraph datasetGraph) {
        this.datasetGraph = datasetGraph;
    }

    @Override
    public NodeValue exec() {
        // example: generate a fresh URI
        String urn = datasetGraph.createURN("resource");
        Node n = NodeFactory.createURI(urn);
        return NodeValue.makeNode(n);
    }

    
    
}
