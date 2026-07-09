package de.dfki.sds.atic.agent;

import java.io.StringWriter;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.jena.graph.Node;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;

public record RdfDatasetAttachment(
        DatasetGraph dataset
        ) implements Attachment {

    public RdfDatasetAttachment {
        Objects.requireNonNull(dataset, "dataset");
    }

    public int getNumberOfGraphs() {

        int count = 0;

        // count default graph if it exists and is not empty
        if (dataset.getDefaultGraph() != null
                && dataset.getDefaultGraph().find().hasNext()) {
            count++;
        }

        Iterator<Node> iter = dataset.listGraphNodes();

        while (iter.hasNext()) {
            iter.next();
            count++;
        }

        return count;
    }

    public int getNumberOfTriples() {

        int count = 0;

        Iterator<Quad> iter = dataset.find();

        while (iter.hasNext()) {
            iter.next();
            count++;

            if (count == Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }

        return count;
    }

    @Override
    public Map<String, Object> toMap() {

        StringWriter sw = new StringWriter();

        Dataset jenaDataset = DatasetFactory.wrap(dataset);

        RDFDataMgr.write(
                sw,
                jenaDataset,
                RDFFormat.TRIG_PRETTY
        );

        Map<String, Object> map = new LinkedHashMap<>();

        map.put("type", "rdfDataset");
        map.put("graphs", getNumberOfGraphs());
        map.put("triples", getNumberOfTriples());
        map.put("content", sw.toString());

        return Map.copyOf(map);
    }
}
