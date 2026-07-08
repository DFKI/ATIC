package de.dfki.sds.aticsqlite.agent;

import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.jena.datatypes.BaseDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.util.iterator.ExtendedIterator;

/**
 *
 */
public class AticDatasetGraphTools {

    private SqliteAticDatasetGraph dataset;
    private InvocationContext ictx;

    private static final String DEFAULT_LIMIT = "25";

    private static final Set<String> WILDCARDS = Set.of(
            "*",
            "/",
            "?",
            "any",
            "all",
            "null",
            "none",
            "wildcard"
    );
    
    public static final String SKILL_TEXT = """
When you need to query RDF data, use both tools: findResources and findLiterals. 
At the beginning use a small value for limit, like 15 and increase when necessary.
                                      """;

    public AticDatasetGraphTools(SqliteAticDatasetGraph dataset, InvocationContext ictx) {
        this.dataset = dataset;
        this.ictx = ictx;
    }

    /**
     * Resets the state.
     */
    public void reset() {

    }

    //============================================================
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
                    required = false,
                    defaultValue = ""
            ) String graphUri,
            @P(
                    name = "subjectUri",
                    description = "URI of the subject to match. Null means any subject.",
                    required = false,
                    defaultValue = ""
            ) String subjectUri,
            @P(
                    name = "predicateUri",
                    description = "URI of the predicate to match. Null means any predicate.",
                    required = false,
                    defaultValue = ""
            ) String predicateUri,
            @P(
                    name = "objectUri",
                    description = "URI of the object to match. Null means any object. Only URI objects are supported.",
                    required = false,
                    defaultValue = ""
            ) String objectUri,
            @P(
                    name = "limit",
                    description = "Maximum number of matching quads to return.",
                    required = false,
                    defaultValue = DEFAULT_LIMIT
            ) int limit
    ) {
        List<QuadRecord> results = new ArrayList<>();
        
        dataset.executeRead(() -> {
            
            Node g = toNodeOrAny(dataset, graphUri);
            Node s = toNodeOrAny(dataset, subjectUri);
            Node p = toNodeOrAny(dataset, predicateUri);
            Node o = toNodeOrAny(dataset, objectUri);

            ExtendedIterator<Quad> iter = (ExtendedIterator<Quad>) dataset.find(g, s, p, o, ictx);

            while (iter.hasNext() && results.size() < limit) {

                Quad q = iter.next();
                
                if(q.getObject().isLiteral()) {
                    continue;
                }

                results.add(new QuadRecord(
                        q.getGraph().toString(),
                        q.getSubject().toString(),
                        q.getPredicate().toString(),
                        q.getObject().toString()
                ));
            }

            iter.close();
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
                    required = false,
                    defaultValue = ""
            ) String graphUri,
            @P(
                    name = "subjectUri",
                    description = "URI of the subject to match. Null means any subject.",
                    required = false,
                    defaultValue = ""
            ) String subjectUri,
            @P(
                    name = "predicateUri",
                    description = "URI of the predicate to match. Null means any predicate.",
                    required = false,
                    defaultValue = ""
            ) String predicateUri,
            @P(
                    name = "objectLex",
                    description = "Literal lexical value to match.",
                    required = false,
                    defaultValue = ""
            ) String objectLex,
            @P(
                    name = "objectDatatypeUri",
                    description = "Datatype URI for a typed literal, e.g. http://www.w3.org/2001/XMLSchema#integer.",
                    required = false,
                    defaultValue = ""
            ) String objectDatatypeUri,
            @P(
                    name = "objectLanguage",
                    description = "Language tag for a language-tagged literal, e.g. en or de.",
                    required = false,
                    defaultValue = ""
            ) String objectLanguage,
            @P(
                    name = "limit",
                    description = "Maximum number of matching literals to return.",
                    required = false,
                    defaultValue = DEFAULT_LIMIT
            ) int limit
    ) {
        List<QuadRecord> results = new ArrayList<>();

        Node o = Node.ANY;
        if (objectLex != null && !objectLex.isBlank()) {
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
            
            Node g = toNodeOrAny(dataset, graphUri);
            Node s = toNodeOrAny(dataset, subjectUri);
            Node p = toNodeOrAny(dataset, predicateUri);

            ExtendedIterator<Quad> iter = (ExtendedIterator<Quad>) dataset.find(g, s, p, finalObject, ictx);

            while (iter.hasNext() && results.size() < limit) {

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
            }

            iter.close();
        });

        return results;
    }


    private static Node toNodeOrAny(DatasetGraph dsg, String value) {
        if (value == null) {
            return Node.ANY;
        }

        value = value.trim();

        if (value.isEmpty() || WILDCARDS.contains(value.toLowerCase())) {
            return Node.ANY;
        }

        // CURIE?
        int colon = value.indexOf(':');
        if (colon > 0) {
            String expanded = dsg.prefixes().expand(value);

            if (expanded != null && !expanded.equals(value)) {
                return NodeFactory.createURI(expanded);
            }
        }
        
        // Absolute URI?
        try {
            URI uri = new URI(value);
            if (uri.isAbsolute()) {
                return NodeFactory.createURI(value);
            }
        } catch (URISyntaxException ignored) {
            
        }

        return Node.ANY;
    }

    public record QuadRecord(
            String graph,
            String subject,
            String predicate,
            String object
            ) {

    }
}
