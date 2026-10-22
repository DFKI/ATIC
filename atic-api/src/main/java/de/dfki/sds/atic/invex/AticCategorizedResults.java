

package de.dfki.sds.atic.invex;

import java.util.List;
import org.apache.jena.graph.Node;

/**
 *
 */
public class AticCategorizedResults {
    
    private String categoryName;
    private List<Node> nodes;

    public AticCategorizedResults(String categoryName, List<Node> nodes) {
        this.categoryName = categoryName;
        this.nodes = nodes;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public List<Node> getNodes() {
        return nodes;
    }

    public void setNodes(List<Node> nodes) {
        this.nodes = nodes;
    }
 
    
}
