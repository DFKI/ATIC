

package de.dfki.sds.aticserver.bridge;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.impl.LiteralLabel;

/**
 *
 */
public class DateModifier implements NodeModifier {

    @Override
    public Node apply(
            Node node,
            String argument
    ) {

        if (!node.isLiteral()) {
            return node;
        }

        LiteralLabel lit =
                node.getLiteral();

        Object value =
                lit.getValue();

        LocalDate date;

        if (value instanceof LocalDate ld) {

            date = ld;

        } else {

            date = LocalDate.parse(
                    lit.getLexicalForm()
            );
        }

        String pattern =
                argument == null || argument.isBlank()
                ? "yyyy-MM-dd"
                : argument;

        String formatted =
                date.format(
                        DateTimeFormatter.ofPattern(pattern)
                );

        return NodeFactory.createLiteralString(
                formatted
        );
    }

}
