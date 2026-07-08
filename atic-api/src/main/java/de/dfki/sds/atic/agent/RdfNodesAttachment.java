package de.dfki.sds.atic.agent;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.jena.graph.Node;

public record RdfNodesAttachment(
        Set<Node> nodes
        ) implements Attachment {

    public RdfNodesAttachment {
        Objects.requireNonNull(nodes, "nodes");
        nodes = Set.copyOf(nodes);
    }

    @Override
    public Map<String, Object> toMap() {

        return Map.of(
                "type", "rdfNodes",
                "nodes",
                nodes.stream()
                        .map(Node::toString)
                        .toList()
        );
    }
}
