

package de.dfki.sds.aticsqlite.bridge;

import java.util.function.UnaryOperator;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;

/**
 *
 */
public class StringModifier implements NodeModifier {

    private final UnaryOperator<String> operation;

    public StringModifier() {
        this(UnaryOperator.identity());
    }

    public StringModifier(
            UnaryOperator<String> operation
    ) {
        this.operation = operation;
    }

    @Override
    public Node apply(
            Node node,
            String argument
    ) {

        if (!node.isLiteral()) {
            return node;
        }

        String value = operation.apply(
                node.getLiteralLexicalForm()
        );

        /*
         * Always return a plain xsd:string literal.
         */
        return NodeFactory.createLiteralString(value);
    }
}
