package de.dfki.sds.aticserver.bridge;

import de.dfki.sds.atic.jenatic.AticDatasetGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.RDFChangesDistinctCollector;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.query.ResultSetRewindable;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingFactory;
import org.apache.jena.vocabulary.RDF;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RdfJsonBridge {

    private static final Logger LOG
            = LoggerFactory.getLogger(RdfJsonBridge.class);

    private final SparqlQueryBuilder sparqlQueryBuilder;
    private final ResultSetJsonMapper resultSetJsonMapper;

    public RdfJsonBridge() {
        this(
                new SparqlQueryBuilder(),
                new ResultSetJsonMapper()
        );
    }

    private RdfJsonBridge(
            SparqlQueryBuilder sparqlQueryBuilder,
            ResultSetJsonMapper resultSetJsonMapper
    ) {
        this.sparqlQueryBuilder = sparqlQueryBuilder;
        this.resultSetJsonMapper = resultSetJsonMapper;
    }

    public JSONObject toJson(
            Map<String, List<String>> queryParams,
            JSONObject template,
            AticDatasetGraph datasetGraph,
            InvocationContext ctx
    ) {

        Object result = evaluate(
                template,
                queryParams,
                datasetGraph,
                ctx,
                BindingFactory.binding()
        );

        if (result instanceof JSONObject json) {
            return json;
        }

        throw new IllegalStateException(
                "Root document must evaluate to JSONObject"
        );
    }

    private Object evaluate(
            Object node,
            Map<String, List<String>> queryParams,
            AticDatasetGraph datasetGraph,
            InvocationContext ctx,
            Binding binding
    ) {

        if (node instanceof JSONObject obj) {

            if (isQueryNode(obj)) {

                return executeQuery(
                        obj,
                        queryParams,
                        datasetGraph,
                        ctx,
                        binding
                );
            }

            JSONObject result
                    = new JSONObject();

            for (String key : obj.keySet()) {

                result.put(
                        key,
                        evaluate(
                                obj.get(key),
                                queryParams,
                                datasetGraph,
                                ctx,
                                binding
                        )
                );
            }

            return result;
        }

        if (node instanceof JSONArray array) {

            JSONArray result
                    = new JSONArray();

            for (Object item : array) {

                result.put(
                        evaluate(
                                item,
                                queryParams,
                                datasetGraph,
                                ctx,
                                binding
                        )
                );
            }

            return result;
        }

        return node;
    }

    private Object executeQuery(
            JSONObject template,
            Map<String, List<String>> queryParams,
            AticDatasetGraph datasetGraph,
            InvocationContext ctx,
            Binding binding
    ) {

        String sparql
                = sparqlQueryBuilder.build(
                        template,
                        queryParams,
                        binding
                );

        LOG.debug("SPARQL:\n{}", sparql);

        Query query
                = QueryFactory.create(sparql);

        return datasetGraph.calculateRead(() -> {

            try (QueryExecution qExec
                    = QueryExecutionFactory.create(
                            query,
                            datasetGraph
                    )) {

                        ctx.transferContext(
                                datasetGraph.getContext()
                        );

                        ResultSet rs
                                = qExec.execSelect();

                        ResultSetRewindable rewindable = rs.rewindable();

                        if (LOG.isDebugEnabled()) {

                            LOG.debug(
                                    "ResultSet:\n{}",
                                    ResultSetFormatter.asText(
                                            rewindable
                                    )
                            );

                            rewindable.reset();
                        }

                        Object json
                                = resultSetJsonMapper.map(template,
                                        rewindable,
                                        queryParams,
                                        datasetGraph,
                                        ctx, (JSONObject childTemplate, Binding childBinding) -> executeQuery(
                                        childTemplate,
                                        queryParams,
                                        datasetGraph,
                                        ctx,
                                        childBinding
                                ));

                        if (LOG.isDebugEnabled()) {

                            if (json instanceof JSONObject o) {

                                LOG.debug(
                                        "JSON:\n{}",
                                        o.toString(2)
                                );
                            } else if (json instanceof JSONArray o) {

                                LOG.debug(
                                        "JSON:\n{}",
                                        o.toString(2)
                                );
                            } else {

                                LOG.debug(
                                        "JSON:\n{}",
                                        json
                                );
                            }
                        }

                        return json;
                    }
        });
    }

    private boolean isQueryNode(Object node) {
        return node instanceof JSONObject object
                && object.has("$where");
    }

    @FunctionalInterface
    public interface TemplateExecutor {

        Object execute(
                JSONObject template,
                Binding binding
        );
    }

    // use for POST, PUT, PATCH, DELETE
    public RDFPatch toPatch(
            String method,
            JSONObject data,
            JSONObject template,
            Supplier<String> uriSupplier,
            AticDatasetGraph datasetGraph,
            InvocationContext ctx
    ) {

        DatasetGraph payload = DatasetGraphFactory.createGeneral();

        PrefixMapping prefixes = PrefixMapping.Factory.create();

        JSONObject context = template.optJSONObject("@context");
        if (context != null) {
            loadPrefixes(context, prefixes);
        }

        walk(
                data,
                template,
                prefixes,
                uriSupplier,
                payload
        );

        RDFChangesDistinctCollector collector = new RDFChangesDistinctCollector();

        switch (method.toUpperCase()) {

            case "POST" -> {

                payload.find()
                        .forEachRemaining(q
                                -> collector.add(
                                q.getGraph(),
                                q.getSubject(),
                                q.getPredicate(),
                                q.getObject()
                        )
                        );
            }

            case "DELETE" -> {

                payload.find()
                        .forEachRemaining(q
                                -> collector.delete(
                                q.getGraph(),
                                q.getSubject(),
                                q.getPredicate(),
                                q.getObject()
                        )
                        );
            }

            case "PATCH" -> {

                /*
                    * PATCH replaces only the predicates present
                    * in the submitted data.
                 */
                datasetGraph.executeRead(() -> {

                    payload.find()
                            .forEachRemaining(q -> {

                                //TODO later we need to support reverse
                                datasetGraph.find(
                                        q.getGraph(),
                                        q.getSubject(),
                                        q.getPredicate(),
                                        Node.ANY
                                ).forEachRemaining(old -> {

                                    collector.delete(
                                            old.getGraph(),
                                            old.getSubject(),
                                            old.getPredicate(),
                                            old.getObject()
                                    );
                                });

                                collector.add(
                                        q.getGraph(),
                                        q.getSubject(),
                                        q.getPredicate(),
                                        q.getObject()
                                );
                            });
                });
            }
            
            //TODO PUT means we have to do a toJson and collect the triples if they would be queried
            //so we delete the queried triples and insert the given ones to simulate a PUT
            
            default ->
                throw new IllegalArgumentException(
                        "Unsupported method: " + method
                );
        }

        return collector.getRDFPatch();
    }

    private void walk(
            Object data,
            Object template,
            PrefixMapping prefixes,
            Supplier<String> uriSupplier,
            DatasetGraph payload
    ) {

        if (!(template instanceof JSONObject templateObj)) {
            return;
        }

        /*
     * Query node
         */
        if (templateObj.has("$map")) {

            JSONObject map = templateObj.getJSONObject("$map");
            JSONArray where = templateObj.getJSONArray("$where");

            ParsedTemplate parsed = parseWhere(
                    map,
                    where,
                    prefixes
            );

            if (data instanceof JSONObject obj) {

                emitObject(
                        obj,
                        parsed,
                        prefixes,
                        uriSupplier,
                        payload
                );

            } else if (data instanceof JSONArray arr) {

                for (Object item : arr) {

                    if (item instanceof JSONObject obj) {

                        emitObject(
                                obj,
                                parsed,
                                prefixes,
                                uriSupplier,
                                payload
                        );
                    }
                }
            }

            /*
         * Continue walking nested template parts.
             */
            for (String key : map.keySet()) {

                Object childTemplate = map.get(key);

                if (!(childTemplate instanceof JSONObject)) {
                    continue;
                }

                if (data instanceof JSONObject obj && obj.has(key)) {

                    walk(
                            obj.get(key),
                            childTemplate,
                            prefixes,
                            uriSupplier,
                            payload
                    );
                }
            }

            return;
        }

        /*
     * Plain JSON object
         */
        if (!(data instanceof JSONObject dataObj)) {
            return;
        }

        for (String key : templateObj.keySet()) {

            if (!dataObj.has(key)) {
                continue;
            }

            walk(
                    dataObj.get(key),
                    templateObj.get(key),
                    prefixes,
                    uriSupplier,
                    payload
            );
        }
    }

    private void emitObject(
            JSONObject data,
            ParsedTemplate parsed,
            PrefixMapping prefixes,
            Supplier<String> uriSupplier,
            DatasetGraph payload
    ) {

        /*
     * Variable bindings established while materializing this object.
         */
        Map<Var, Node> bindings = new HashMap<>();

        /*
     * Root subject.
         */
        Node subject;

        if (data.has("@id")) {

            subject = NodeFactory.createURI(
                    data.getString("@id")
            );

        } else {

            subject = NodeFactory.createURI(
                    uriSupplier.get()
            );
        }

        bindings.put(parsed.rootVariable(), subject);

        /*
     * Bind JSON values.
         */
        for (Map.Entry<String, Triple> e : parsed.jsonMappings().entrySet()) {

            String jsonKey = e.getKey();

            if (!data.has(jsonKey)) {
                continue;
            }

            Triple t = e.getValue();

            Node object
                    = toNode(data.get(jsonKey));

            bindings.put(
                    (Var) t.getObject(),
                    object
            );
        }

        /*
     * Allocate remaining resource variables.
         */
        for (Triple t : parsed.whereTriples()) {

            if (t.getSubject().isVariable()) {

                Var v = (Var) t.getSubject();

                bindings.computeIfAbsent(
                        v,
                        x -> NodeFactory.createBlankNode()
                );
            }

            if (t.getObject().isVariable()) {

                Var v = (Var) t.getObject();

                if (!bindings.containsKey(v)
                        && !parsed.literalVariables().contains(v)) {

                    bindings.put(
                            v,
                            NodeFactory.createBlankNode()
                    );
                }
            }
        }

        /*
     * Instantiate the whole graph pattern.
         */
        for (Triple pattern : parsed.whereTriples()) {

            Node s = substitute(
                    pattern.getSubject(),
                    bindings
            );

            Node p = pattern.getPredicate();

            Node o = substitute(
                    pattern.getObject(),
                    bindings
            );

            Quad q = Quad.create(
                    Quad.defaultGraphNodeGenerated,
                    s,
                    p,
                    o
            );

            payload.add(q);
        }
    }

    private Node substitute(
            Node node,
            Map<Var, Node> bindings
    ) {

        if (!node.isVariable()) {
            return node;
        }

        return bindings.get((Var) node);
    }

    private ParsedTemplate parseWhere(
            JSONObject map,
            JSONArray where,
            PrefixMapping prefixes
    ) {

        List<Triple> triples = new ArrayList<>();

        Map<String, Triple> jsonMappings
                = new LinkedHashMap<>();

        Set<Var> literalVariables
                = new HashSet<>();

        Var rootVariable = null;

        Map<String, Var> mappedVariables
                = new HashMap<>();

        for (String key : map.keySet()) {

            Object value = map.get(key);

            if (!(value instanceof String s)
                    || !s.startsWith("?")) {
                continue;
            }

            mappedVariables.put(
                    stripModifiers(key),
                    Var.alloc(
                            s.substring(1)
                    )
            );

            if (key.equals("@id")) {
                rootVariable
                        = Var.alloc(
                                s.substring(1)
                        );
            }
        }

        for (int i = 0; i < where.length(); i++) {

            String line = where.getString(i).trim();

            if (line.endsWith(".")) {
                line = line.substring(0, line.length() - 1);
            }

            String[] parts = line.split("\\s+", 3);

            if (parts.length != 3) {
                continue;
            }

            Node s = parseToken(parts[0], prefixes);
            Node p = parseToken(parts[1], prefixes);
            Node o = parseToken(parts[2], prefixes);

            Triple triple = Triple.create(s, p, o);
            
            triples.add(triple);

            if (triple.getObject().isVariable()) {

                Var ov
                        = (Var) triple.getObject();

                for (Map.Entry<String, Var> e
                        : mappedVariables.entrySet()) {

                    if (e.getValue().equals(ov)) {

                        jsonMappings.put(
                                e.getKey(),
                                triple
                        );

                        literalVariables.add(ov);
                    }
                }
            }
        }

        return new ParsedTemplate(
                rootVariable,
                triples,
                jsonMappings,
                literalVariables
        );
    }

    private record ParsedTemplate(
            Var rootVariable,
            List<Triple> whereTriples,
            Map<String, Triple> jsonMappings,
            Set<Var> literalVariables
            ) {

    }

    private Node parseToken(
            String token,
            PrefixMapping prefixes
    ) {

        if (token.startsWith("?")) {
            return Var.alloc(token.substring(1));
        }

        if (token.equals("a")) {
            return RDF.Nodes.type;
        }

        if (token.startsWith("<") && token.endsWith(">")) {
            return NodeFactory.createURI(
                    token.substring(1, token.length() - 1)
            );
        }

        String expanded = prefixes.expandPrefix(token);

        if (!expanded.equals(token)) {
            return NodeFactory.createURI(expanded);
        }

        String uri = prefixes.getNsPrefixURI(token);

        if (uri != null) {
            return NodeFactory.createURI(uri);
        }

        throw new IllegalArgumentException(
                "Cannot parse token: " + token
        );
    }

    private void loadPrefixes(
            JSONObject context,
            PrefixMapping prefixes
    ) {

        for (String key : context.keySet()) {

            Object value = context.get(key);

            if (!(value instanceof String uri)) {
                continue;
            }

            if (uri.endsWith("#") || uri.endsWith("/")) {
                prefixes.setNsPrefix(key, uri);
            } else {
                prefixes.setNsPrefix(key, uri);
            }
        }
    }

    private Node toNode(
            Object value
    ) {

        if (value == null
                || value == JSONObject.NULL) {

            return null;
        }

        if (value instanceof JSONObject obj
                && obj.has("@id")) {

            return NodeFactory.createURI(
                    obj.getString("@id")
            );
        }

        if (value instanceof Integer i) {
            return NodeFactory.createLiteralByValue(
                    i,
                    XSDDatatype.XSDinteger
            );
        }

        if (value instanceof Long l) {
            return NodeFactory.createLiteralByValue(
                    l,
                    XSDDatatype.XSDlong
            );
        }

        if (value instanceof Double d) {
            return NodeFactory.createLiteralByValue(
                    d,
                    XSDDatatype.XSDdouble
            );
        }

        if (value instanceof Float f) {
            return NodeFactory.createLiteralByValue(
                    f,
                    XSDDatatype.XSDfloat
            );
        }

        if (value instanceof Boolean b) {
            return NodeFactory.createLiteralByValue(
                    b,
                    XSDDatatype.XSDboolean
            );
        }

        return NodeFactory.createLiteralString(
                value.toString()
        );
    }

    private String stripModifiers(
            String key
    ) {

        int idx = key.indexOf('$');

        if (idx >= 0) {
            return key.substring(0, idx);
        }

        return key;
    }

}
