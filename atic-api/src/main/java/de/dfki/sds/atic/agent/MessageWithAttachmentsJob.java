package de.dfki.sds.atic.agent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;

public class MessageWithAttachmentsJob implements Job {

    private final String message;
    private final Set<Node> nodes;
    private final Set<Triple> triples;

    private MessageWithAttachmentsJob(Builder builder) {
        this.message = builder.message;
        this.nodes = Collections.unmodifiableSet(
                new LinkedHashSet<>(builder.nodes));
        this.triples = Collections.unmodifiableSet(
                new LinkedHashSet<>(builder.triples));
    }

    public String getMessage() {
        return message;
    }

    public Set<Node> getNodes() {
        return nodes;
    }

    public Set<Triple> getTriples() {
        return triples;
    }

    @Override
    public String toString() {
        return "MessageJob{" +
                "message='" + message + '\'' +
                ", nodes=" + nodes +
                ", triples=" + triples +
                '}';
    }

    public static Builder builder(String message) {
        return new Builder(message);
    }

    public static class Builder {

        private final String message;
        private final Set<Node> nodes = new LinkedHashSet<>();
        private final Set<Triple> triples = new LinkedHashSet<>();

        private Builder(String message) {
            this.message = message;
        }

        public Builder node(Node node) {
            nodes.add(node);
            return this;
        }

        public Builder nodes(Set<Node> nodes) {
            this.nodes.addAll(nodes);
            return this;
        }

        public Builder triple(Triple triple) {
            triples.add(triple);
            return this;
        }

        public Builder triples(Set<Triple> triples) {
            this.triples.addAll(triples);
            return this;
        }

        public MessageWithAttachmentsJob build() {
            return new MessageWithAttachmentsJob(this);
        }
    }
}
