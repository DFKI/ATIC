package de.dfki.sds.aticsqlite.agent;

import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.apache.jena.datatypes.BaseDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.Quad;

/**
 *
 */
public class AticDatasetGraphTools {

    private SqliteAticDatasetGraph dataset;
    private InvocationContext ictx;

    public AticDatasetGraphTools(SqliteAticDatasetGraph dataset, InvocationContext ictx) {
        this.dataset = dataset;
        this.ictx = ictx;
    }

    @Tool("""
    Returns the URIs of all graphs currently visible to the invoker.
    The result is a list of graph node URIs.
    """)
    public List<String> listGraphNodes() {
        List<String> l = new ArrayList<>();
        dataset.executeRead(() -> {
            dataset.listGraphNodes(ictx).forEachRemaining(n -> l.add(n.toString()));
        });
        return l;
    }

    @Tool("""
    Returns the total number of triples across all accessible graphs.
    """)
    public long size() {
        return dataset.calculateRead(() -> {
            return dataset.size(ictx);
        });
    }

    @Tool("""
      Finds RDF quadruples with resources visible to the current user by matching optional graph, subject,
      predicate and object URIs. Any parameter may be omitted (null) and will act as a wildcard.
      Returns matching RDF quads. Results are limited by the 'limit' parameter.
      """)
    public List<QuadRecord> findResources(
            @P(
                    name = "graphUri",
                    description = "URI of the graph to search in. Null means search all visible graphs.",
                    required = false
            ) String graphUri,
            @P(
                    name = "subjectUri",
                    description = "URI of the subject to match. Null means any subject.",
                    required = false
            ) String subjectUri,
            @P(
                    name = "predicateUri",
                    description = "URI of the predicate to match. Null means any predicate.",
                    required = false
            ) String predicateUri,
            @P(
                    name = "objectUri",
                    description = "URI of the object to match. Null means any object. Only URI objects are supported.",
                    required = false
            ) String objectUri,
            @P(
                    name = "limit",
                    description = "Maximum number of matching quads to return.",
                    required = false,
                    defaultValue = "100"
            ) int limit
    ) {
        List<QuadRecord> results = new ArrayList<>();

        Node g = graphUri == null || graphUri.isBlank() ? Node.ANY : NodeFactory.createURI(graphUri);
        Node s = subjectUri == null || subjectUri.isBlank() ? Node.ANY : NodeFactory.createURI(subjectUri);
        Node p = predicateUri == null || predicateUri.isBlank() ? Node.ANY : NodeFactory.createURI(predicateUri);
        Node o = objectUri == null || objectUri.isBlank() ? Node.ANY : NodeFactory.createURI(objectUri);

        dataset.executeRead(() -> {

            Iterator<Quad> iter = dataset.find(g, s, p, o, ictx);

            int count = 0;

            while (iter.hasNext() && count < limit) {

                Quad q = iter.next();

                results.add(new QuadRecord(
                        q.getGraph().toString(),
                        q.getSubject().toString(),
                        q.getPredicate().toString(),
                        q.getObject().toString()
                ));

                count++;
            }
        });

        return results;
    }

    @Tool("""
      Finds RDF quadruples with literals visible to the current user by matching optional graph,
      subject, predicate and literal value constraints. Any parameter may be
      omitted (null) and will act as a wildcard.

      Literal matching supports:
      - plain literals via objectLex
      - language-tagged literals via objectLex + objectLanguage
      - typed literals via objectLex + objectDatatypeUri

      Returns matching RDF quads. Results are limited by the 'limit' parameter.
      """)
    public List<QuadRecord> findLiterals(
            @P(
                    name = "graphUri",
                    description = "URI of the graph to search in. Null means search all visible graphs.",
                    required = false
            ) String graphUri,
            @P(
                    name = "subjectUri",
                    description = "URI of the subject to match. Null means any subject.",
                    required = false
            ) String subjectUri,
            @P(
                    name = "predicateUri",
                    description = "URI of the predicate to match. Null means any predicate.",
                    required = false
            ) String predicateUri,
            @P(
                    name = "objectLex",
                    description = "Literal lexical value to match.",
                    required = false
            ) String objectLex,
            @P(
                    name = "objectDatatypeUri",
                    description = "Datatype URI for a typed literal, e.g. http://www.w3.org/2001/XMLSchema#integer.",
                    required = false
            ) String objectDatatypeUri,
            @P(
                    name = "objectLanguage",
                    description = "Language tag for a language-tagged literal, e.g. en or de.",
                    required = false
            ) String objectLanguage,
            @P(
                    name = "limit",
                    description = "Maximum number of matching literals to return.",
                    required = false,
                    defaultValue = "100"
            ) int limit
    ) {
        List<QuadRecord> results = new ArrayList<>();

        Node g = graphUri == null || graphUri.isBlank() 
                ? Node.ANY
                : NodeFactory.createURI(graphUri);

        Node s = subjectUri == null || subjectUri.isBlank() 
                ? Node.ANY
                : NodeFactory.createURI(subjectUri);

        Node p = predicateUri == null || predicateUri.isBlank() 
                ? Node.ANY
                : NodeFactory.createURI(predicateUri);

        Node o = Node.ANY;

        if (objectLex != null) {

            if (objectLanguage != null && !objectLanguage.isBlank()) {

                o = NodeFactory.createLiteralLang(
                        objectLex,
                        objectLanguage
                );

            } else if (objectDatatypeUri != null && !objectDatatypeUri.isBlank()) {

                o = NodeFactory.createLiteralDT(
                        objectLex,
                        new BaseDatatype(objectDatatypeUri)
                );

            } else {

                o = NodeFactory.createLiteralString(objectLex);
            }
        }

        Node finalObject = o;

        dataset.executeRead(() -> {

            Iterator<Quad> iter = dataset.find(
                    g,
                    s,
                    p,
                    finalObject,
                    ictx
            );

            int count = 0;

            while (iter.hasNext() && count < limit) {

                Quad q = iter.next();

                if (!q.getObject().isLiteral()) {
                    continue;
                }

                results.add(new QuadRecord(
                        q.getGraph().toString(),
                        q.getSubject().toString(),
                        q.getPredicate().toString(),
                        q.getObject().toString()
                ));

                count++;
            }
        });

        return results;
    }

    public record QuadRecord(
            String graph,
            String subject,
            String predicate,
            String object
            ) {

    }
}
