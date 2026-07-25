

package de.dfki.sds.aticserver.bridge;

import org.apache.jena.graph.Node;

@FunctionalInterface
public interface NodeModifier {

    Node apply(
            Node node,
            String argument
    );

}
