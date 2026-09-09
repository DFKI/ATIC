

package de.dfki.sds.aticsqlite.bridge;

import org.apache.jena.graph.Node;

@FunctionalInterface
public interface NodeModifier {

    Node apply(
            Node node,
            String argument
    );

}
