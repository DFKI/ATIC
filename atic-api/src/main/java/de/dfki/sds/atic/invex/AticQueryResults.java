

package de.dfki.sds.atic.invex;

import de.dfki.sds.atic.invex.QueryResults;
import java.util.List;
import java.util.Objects;
import org.apache.jena.graph.Node;

/**
 *
 */
public class AticQueryResults {

    private final QueryResults rawResults;
    private List<Node> foundNodes;

    private AticQueryResults(QueryResults rawResults, List<Node> foundNodes) {
        this.rawResults = rawResults;
        this.foundNodes = foundNodes;
    }

    public QueryResults getRawResults() {
        return rawResults;
    }

    public List<Node> getFoundNodes() {
        return foundNodes;
    }

    public String getQueryProcessID() {
        return rawResults.getQueryProcessID();
    }

    public boolean isSuccess() {
        return rawResults.isSuccess();
    }

    public boolean isQueryAccepted() {
        return rawResults.isQueryAccepted();
    }
    
    public static Builder builder(QueryResults rawResults) {
        return new Builder(rawResults);
    }

    public static class Builder {

        private final QueryResults rawResults;
        private List<Node> foundNodes;

        private Builder(QueryResults rawResults) {
            this.rawResults = Objects.requireNonNull(rawResults, "rawResults must not be null");
        }

        public Builder foundNodes(List<Node> foundNodes) {
            this.foundNodes = foundNodes;
            return this;
        }

        public AticQueryResults build() {
            return new AticQueryResults(rawResults, foundNodes);
        }
    }
}
