

package de.dfki.sds.aticsqlite.agent;

import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import dev.langchain4j.agent.tool.Tool;
import java.util.ArrayList;
import java.util.List;

/**
 *
 */
public class AticDatasetGraphTools {
    
    private SqliteAticDatasetGraph dataset;
    private InvocationContext ictx;
    
    public AticDatasetGraphTools(SqliteAticDatasetGraph dataset, InvocationContext ictx) {
        this.dataset = dataset;
        this.ictx = ictx;
    }
    
    @Tool("""
    Returns the URIs of all graphs currently visible to the invoker.
    The result is a list of graph node URIs.
    """)
    public List<String> listGraphNodes() {
        List<String> l = new ArrayList<>();
        dataset.executeRead(() -> {
            dataset.listGraphNodes(ictx).forEachRemaining(n -> l.add(n.toString()));
        });
        return l;
    }
}
