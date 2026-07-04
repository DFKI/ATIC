

package de.dfki.sds.atic.agent;


import java.util.Objects;
import org.apache.jena.graph.Graph;

public record RdfGraphAttachment(
        Graph graph
) implements Attachment {

    public RdfGraphAttachment {
        Objects.requireNonNull(graph, "graph");
    }
}
