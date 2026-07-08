package de.dfki.sds.atic.agent;

import java.io.StringWriter;
import java.util.Map;
import java.util.Objects;
import org.apache.jena.graph.Graph;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;

public record RdfGraphAttachment(
        Graph graph
        ) implements Attachment {

    public RdfGraphAttachment {
        Objects.requireNonNull(graph, "graph");
    }

    @Override
    public Map<String, Object> toMap() {

        StringWriter sw = new StringWriter();

        Model model = ModelFactory.createModelForGraph(graph);

        RDFDataMgr.write(
                sw,
                model,
                RDFFormat.TURTLE_PRETTY
        );

        return Map.of(
                "type", "rdfGraph",
                "turtle", sw.toString()
        );
    }
}
