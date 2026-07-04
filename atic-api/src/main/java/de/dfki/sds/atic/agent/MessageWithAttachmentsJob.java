package de.dfki.sds.atic.agent;

import de.dfki.sds.atic.ac.User;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;

public class MessageWithAttachmentsJob implements Job {

    private final User principal;
    private final String message;
    private final Set<Node> nodes;
    private final Set<Triple> triples;

    private MessageWithAttachmentsJob(Builder builder) {
        this.principal = builder.principal;
        this.message = builder.message;
        this.nodes = Collections.unmodifiableSet(
                new LinkedHashSet<>(builder.nodes));
        this.triples = Collections.unmodifiableSet(
                new LinkedHashSet<>(builder.triples));
    }

    public User getPrincipal() {
        return principal;
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
        return "MessageWithAttachmentsJob{" +
                "principal=" + principal +
                ", message='" + message + '\'' +
                ", nodes=" + nodes +
                ", triples=" + triples +
                '}';
    }

    public static Builder builder(
            User principal,
            String message) {

        return new Builder(principal, message);
    }

    public static class Builder {

        private final User principal;
        private final String message;

        private final Set<Node> nodes = new LinkedHashSet<>();
        private final Set<Triple> triples = new LinkedHashSet<>();

        private Builder(
                User principal,
                String message) {

            this.principal = principal;
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
