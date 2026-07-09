package de.dfki.sds.aticsqlite.agent;

import de.dfki.sds.atic.agent.RdfDatasetAttachment;
import de.dfki.sds.atic.agent.RdfPatchAttachment;
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
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.RDFPatchOps;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
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
                                            
When the user wants you to change RDF data use modifyLiteralQuad for object literals and modifyResourceQuad for object resources.
If the graph is unknown, assume default graph with URI `urn:x-arq:DefaultGraph`.
The current state of the RDF patch during processing can be inspected with inspectRDFPatch.
                                      """;

    private DatasetGraph foundQuadsDatasetGraph;
    private RDFChangesDistinctCollector collector;

    public AticDatasetGraphTools(SqliteAticDatasetGraph dataset, InvocationContext ictx) {
        this.dataset = dataset;
        this.ictx = ictx;

    }

    /**
     * Resets the state.
     */
    public void reset() {
        foundQuadsDatasetGraph = DatasetGraphFactory.createGeneral();
        collector = new RDFChangesDistinctCollector();
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

            ExtendedIterator<Quad> iter = (ExtendedIterator<Quad>) dataset.findInternal(g, s, p, o, true, false, ictx);

            while (iter.hasNext() && results.size() < limit) {

                Quad q = iter.next();

                if (q.getObject().isLiteral()) {
                    continue;
                }

                foundQuadsDatasetGraph.add(q);

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

            ExtendedIterator<Quad> iter = (ExtendedIterator<Quad>) dataset.findInternal(g, s, p, finalObject, true, false, ictx);

            while (iter.hasNext() && results.size() < limit) {

                Quad q = iter.next();

                if (!q.getObject().isLiteral()) {
                    continue;
                }

                foundQuadsDatasetGraph.add(q);

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

    public enum PatchOperation {
        ADD,
        REMOVE
    }

    @Tool("""
    Records an operation on a resource RDF quad in the internal RDF patch.

    Use operation=ADD to add the quad to the patch.
    Use operation=REMOVE to remove the quad in the patch.

    The graph, subject, predicate and object must be URIs.

    This tool only records the operation in the internal patch. It does not immediately modify the dataset.
    """)
    public void modifyResourceQuad(
            @P(
                    name = "operation",
                    description = "Either ADD or REMOVE."
            ) PatchOperation operation,
            @P(
                    name = "graphUri",
                    description = "URI of the graph.",
                    required = true
            ) String graphUri,
            @P(
                    name = "subjectUri",
                    description = "URI of the subject.",
                    required = true
            ) String subjectUri,
            @P(
                    name = "predicateUri",
                    description = "URI of the predicate.",
                    required = true
            ) String predicateUri,
            @P(
                    name = "objectUri",
                    description = "URI of the object.",
                    required = true
            ) String objectUri
    ) {
        dataset.executeRead(() -> {

            Node g = toNodeOrAny(dataset, graphUri);
            Node s = toNodeOrAny(dataset, subjectUri);
            Node p = toNodeOrAny(dataset, predicateUri);
            Node o = toNodeOrAny(dataset, objectUri);

            if (g.equals(Node.ANY)) {
                throw new IllegalArgumentException("Provide a valid graph URI.");
            }
            if (s.equals(Node.ANY)) {
                throw new IllegalArgumentException("Provide a valid subject URI.");
            }
            if (p.equals(Node.ANY)) {
                throw new IllegalArgumentException("Provide a valid predicate URI.");
            }
            if (o.equals(Node.ANY)) {
                throw new IllegalArgumentException("Provide a valid object URI.");
            }

            switch (operation) {
                case ADD ->
                    collector.add(g, s, p, o);
                case REMOVE -> {
                    
                    //make sure that only quads can be removed which exist
                    if(!dataset.contains(g, s, p, o, ictx)) {
                        throw new IllegalArgumentException(
                                "This quad cannot be deleted because it does not exist: " + Quad.create(g, s, p, o) + ". " + 
                                "Make sure to find it first with findResources."
                        );
                    }
                    
                    collector.delete(g, s, p, o);
                }
            }
        });
    }
    
    
    @Tool("""
    Records an operation on a literal RDF quad in the internal RDF patch.

    Use operation=ADD to add the quad to the patch.
    Use operation=REMOVE to remove the quad in the patch.

    The graph, subject and predicate must be URIs.
    The object is always a literal and can be plain, language-tagged, or typed.

    This tool only records the operation in the internal patch. It does not immediately modify the dataset.
    """)
    public void modifyLiteralQuad(
            @P(
                    name = "operation",
                    description = "Either ADD or REMOVE."
            ) PatchOperation operation,
            @P(
                    name = "graphUri",
                    description = "URI of the graph.",
                    required = true
            ) String graphUri,
            @P(
                    name = "subjectUri",
                    description = "URI of the subject.",
                    required = true
            ) String subjectUri,
            @P(
                    name = "predicateUri",
                    description = "URI of the predicate.",
                    required = true
            ) String predicateUri,
            @P(
                    name = "objectLex",
                    description = "Literal lexical value.",
                    required = true
            ) String objectLex,
            @P(
                    name = "objectDatatypeUri",
                    description = "Datatype URI for a typed literal.",
                    required = false,
                    defaultValue = ""
            ) String objectDatatypeUri,
            @P(
                    name = "objectLanguage",
                    description = "Language tag for a language-tagged literal.",
                    required = false,
                    defaultValue = ""
            ) String objectLanguage
    ) {
        Node object;

        if (objectLanguage != null && !objectLanguage.isBlank()) {
            object = NodeFactory.createLiteralLang(objectLex, objectLanguage);
        } else if (objectDatatypeUri != null && !objectDatatypeUri.isBlank()) {
            object = NodeFactory.createLiteralDT(
                    objectLex,
                    new BaseDatatype(objectDatatypeUri)
            );
        } else {
            object = NodeFactory.createLiteralString(objectLex);
        }

        dataset.executeRead(() -> {

            Node g = toNodeOrAny(dataset, graphUri);
            Node s = toNodeOrAny(dataset, subjectUri);
            Node p = toNodeOrAny(dataset, predicateUri);

            if (g.equals(Node.ANY)) {
                throw new IllegalArgumentException("Provide a valid graph URI.");
            }
            if (s.equals(Node.ANY)) {
                throw new IllegalArgumentException("Provide a valid subject URI.");
            }
            if (p.equals(Node.ANY)) {
                throw new IllegalArgumentException("Provide a valid predicate URI.");
            }

            switch (operation) {
                case ADD ->
                    collector.add(g, s, p, object);
                case REMOVE -> {
                    
                    //make sure that only quads can be removed which exist
                    if(!dataset.contains(g, s, p, object, ictx)) {
                        throw new IllegalArgumentException(
                                "This quad cannot be deleted because it does not exist: " + Quad.create(g, s, p, object) + ". " + 
                                "Make sure to find it first with findLiterals."
                        );
                    }
                    
                    collector.delete(g, s, p, object);
                }
            }
        });
    }

    @Tool("""
      Returns the current state of the RDF patch.
      """)
    public String inspectRDFPatch() {
        RDFPatch patch = collector.getRDFPatch();
        StringBuilder sb = new StringBuilder();
        if(hasRDFPatch()) {
            sb.append(RDFPatchOps.str(patch));
        } else {
            sb.append("Patch is empty. No modifications were made yet.");
        }
        return sb.toString();
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

    public RdfDatasetAttachment getRdfDatasetAttachment() {
        return new RdfDatasetAttachment(foundQuadsDatasetGraph);
    }

    public boolean hasFoundQuads() {
        return foundQuadsDatasetGraph.find().hasNext();
    }
    
    public RdfPatchAttachment getRdfPatchAttachment() {
        return new RdfPatchAttachment(collector.getRDFPatch());
    }
    
    public boolean hasRDFPatch() {
        return !collector.isEmpty();
    }

}
