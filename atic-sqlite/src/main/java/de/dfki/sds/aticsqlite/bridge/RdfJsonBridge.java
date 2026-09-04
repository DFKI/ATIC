package de.dfki.sds.aticsqlite.bridge;

import de.dfki.sds.atic.helper.JSONUtils;
import de.dfki.sds.atic.jenatic.AticDatasetGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.RDFChangesDistinctCollector;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.jena.datatypes.BaseDatatype;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.query.ResultSetRewindable;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.RDFPatchOps;
import org.apache.jena.rdfpatch.changes.RDFChangesBase;
import org.apache.jena.riot.out.NodeFmtLib;
import org.apache.jena.riot.system.PrefixMap;
import org.apache.jena.riot.system.PrefixMapFactory;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.core.Var;
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

    public List<ResultSetJsonMapper.FragmentProperty> getFragmentSetting() {
        return resultSetJsonMapper.getFragmentSetting();
    }

    //==========================================================
    //reading
    public Object toJson(
            Map<String, List<String>> queryParams,
            JSONObject template,
            AticDatasetGraph datasetGraph,
            InvocationContext ctx
    ) {
        JSONObject root = template;

        PrefixMapping prefixes = PrefixMapping.Factory.create();

        JSONObject context = template.optJSONObject("@context");
        if (context != null) {
            loadPrefixes(context, prefixes);
        }

        Map<Var, List<Node>> binding = new HashMap<>();

        //if $default is there bindings are set
        initDefault(template, prefixes, binding);

        //turn query params to bindings
        //here query params get higher priority and overwrite $default
        initQueryParams(queryParams, prefixes, binding);

        return evaluate(
                template,
                root,
                datasetGraph,
                ctx,
                binding,
                prefixes
        );
    }

    private Object evaluate(
            Object node,
            JSONObject root,
            AticDatasetGraph datasetGraph,
            InvocationContext ctx,
            Map<Var, List<Node>> binding,
            PrefixMapping prefixes
    ) {

        if (node instanceof JSONObject obj) {

            if (isQueryNode(obj)) {

                return executeQuery(
                        obj,
                        root,
                        datasetGraph,
                        ctx,
                        binding,
                        prefixes
                );
            }

            JSONObject result = new JSONObject();

            for (String key : obj.keySet()) {

                result.put(
                        key,
                        evaluate(
                                obj.get(key),
                                root,
                                datasetGraph,
                                ctx,
                                binding,
                                prefixes
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
                                root,
                                datasetGraph,
                                ctx,
                                binding,
                                prefixes
                        )
                );
            }

            return result;
        }

        return node;
    }

    private Object executeQuery(
            JSONObject template,
            JSONObject root,
            AticDatasetGraph datasetGraph,
            InvocationContext ctx,
            Map<Var, List<Node>> binding,
            PrefixMapping prefixes
    ) {

        Map<Var, List<Node>> bindingCopy = new HashMap<>(binding);

        String sparql
                = sparqlQueryBuilder.build(
                        template,
                        root,
                        binding,
                        false
                );

        LOG.debug("SPARQL:\n{}", sparql);

        Query query
                = QueryFactory.create(sparql);

        //temporary dataset with context
        Dataset ds = DatasetFactory.wrap(datasetGraph);
        ctx.transferContext(ds.getContext());

        Object json;
        try (QueryExecution qExec = QueryExecutionFactory.create(query, ds)) {
            ResultSet rs = qExec.execSelect();

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

            json = resultSetJsonMapper.map(template,
                    rewindable,
                    datasetGraph,
                    ctx,
                    binding,
                    prefixes,
                    (JSONObject childTemplate, Map<Var, List<Node>> childBinding) -> executeQuery(
                            inherit(template, childTemplate),
                            root,
                            datasetGraph,
                            ctx,
                            childBinding,
                            prefixes
                    )
            );

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
        }

        json = processPagination(json, template, root, datasetGraph, ctx, bindingCopy, prefixes);

        return json;
    }

    private Object processPagination(
            Object json,
            JSONObject template,
            JSONObject root,
            AticDatasetGraph datasetGraph,
            InvocationContext ctx,
            Map<Var, List<Node>> binding,
            PrefixMapping prefixes) {

        if (!template.has("$pagination")) {
            return json;
        }

        JSONObject paginationConfig = template.getJSONObject("$pagination");

        int offset = resolvePaginationInt(
                paginationConfig,
                "offset",
                binding
        );

        int size = resolvePaginationInt(
                paginationConfig,
                "limit",
                binding
        );

        if (offset < 0) {
            throw new IllegalArgumentException(
                    "$pagination.offset must be >= 0: " + offset
            );
        }

        if (size <= 0) {
            throw new IllegalArgumentException(
                    "$pagination.size must be > 0: " + size
            );
        }

        /*
         * Build and execute the equivalent COUNT query.
         */
        String countSparql = sparqlQueryBuilder.build(
                template,
                root,
                binding,
                true
        );

        LOG.debug("Pagination COUNT SPARQL:\n{}", countSparql);

        Query countQuery = QueryFactory.create(countSparql);

        Dataset ds = DatasetFactory.wrap(datasetGraph);
        ctx.transferContext(ds.getContext());

        long total;

        try (QueryExecution qExec
                = QueryExecutionFactory.create(countQuery, ds)) {

            ResultSet rs = qExec.execSelect();

            if (!rs.hasNext()) {
                total = 0;
            } else {
                QuerySolution solution = rs.next();

                Literal count = solution.getLiteral(SparqlQueryBuilder.COUNT_VARNAME);

                if (count == null) {
                    throw new IllegalStateException(
                            "Count query did not return ?" + SparqlQueryBuilder.COUNT_VARNAME + ":\n"
                            + countSparql
                    );
                }

                total = count.getLong();
            }
        }

        // Calculate everything.
        long currentPage = (offset / size) + 1;
        long totalPages = total == 0 ? 0 : (total + size - 1) / size;
        long firstOffset = 0;
        long lastOffset = total == 0 ? 0 : ((total - 1) / size) * size;

        JSONArray pages = new JSONArray();
        for (long page = 1; page <= totalPages; page++) {
            long pageOffset = (page - 1) * size;
            pages.put(new JSONObject()
                    .put("offset", pageOffset)
                    .put("label", String.valueOf(page))
                    .put("number", page));
        }

        int currentPageIndex = totalPages == 0 ? -1 : (int) (currentPage - 1);

        JSONObject firstPage = totalPages == 0 ? null : pages.getJSONObject(0);
        JSONObject currentPageObject = currentPageIndex >= 0 && currentPageIndex < totalPages
                ? pages.getJSONObject(currentPageIndex)
                : null;
        JSONObject lastPage = totalPages == 0 ? null : pages.getJSONObject((int) totalPages - 1);
        
        JSONObject prevPage = currentPageIndex > 0 ? pages.getJSONObject(currentPageIndex - 1) : null; 
        JSONObject nextPage = currentPageIndex >= 0 && currentPageIndex < totalPages - 1 ? pages.getJSONObject(currentPageIndex + 1) : null;

        JSONObject pagination = JSONUtils.createJSONObject()
                .put("limit", size)
                .put("total", total)
                .put("totalPages", totalPages)
                .put("currentPageIndex", currentPageIndex)
                .put("firstPage", firstPage)
                .put("prevPage", prevPage)
                .put("currentPage", currentPageObject)
                .put("nextPage", nextPage)
                .put("lastPage", lastPage)
                .put("pages", pages);

        return JSONUtils.createJSONObject()
                .put("data", json)
                .put("pagination", pagination);
    }

    private int resolvePaginationInt(
            JSONObject config,
            String key,
            Map<Var, List<Node>> binding) {

        if (!config.has(key)) {
            throw new IllegalArgumentException(
                    "$pagination requires '" + key + "'"
            );
        }

        Object value = config.get(key);

        if (value instanceof Number number) {
            return number.intValue();
        }

        if (value instanceof String string
                && string.startsWith("?")) {

            String variableName = string.substring(1);

            Var var = Var.alloc(variableName);

            if (!binding.containsKey(var)) {
                throw new IllegalArgumentException(
                        "Pagination variable not found in binding: "
                        + string
                );
            }

            List<Node> nodes = binding.get(var);

            if (nodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "No binding found for: " + var
                );
            }

            try {
                return (Integer) nodes.get(0).getLiteral().getValue();
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Pagination variable is not an integer: "
                        + string,
                        e
                );
            }
        }

        throw new IllegalArgumentException(
                "$pagination." + key
                + " must be an integer or variable such as '?offset'"
        );
    }

    private boolean isQueryNode(Object node) {
        return node instanceof JSONObject object
                && object.has("$where");
    }

    @FunctionalInterface
    public interface TemplateExecutor {

        Object execute(
                JSONObject template,
                Map<Var, List<Node>> binding
        );
    }

    private JSONObject inherit(JSONObject parentTemplate, JSONObject childTemplate) {
        JSONObject retTemplate = new JSONObject(childTemplate.toString());

        if (parentTemplate.has("$from")
                && !retTemplate.has("$from")
                && retTemplate.has("$where")) {

            retTemplate.put("$from", parentTemplate.get("$from"));
        }

        return retTemplate;
    }

    private void initDefault(JSONObject template, PrefixMapping prefixes, Map<Var, List<Node>> bindings) {
        JSONObject defaultObj = template.optJSONObject("$default");
        if (defaultObj != null) {
            for (String key : defaultObj.keySet()) {

                Node node = toNode(
                        defaultObj.get(key),
                        prefixes
                );

                if (node != null) {
                    bindings.computeIfAbsent(Var.alloc(key), v -> new ArrayList<>()).add(node);
                }
            }
        }
    }

    private void initQueryParams(
            Map<String, List<String>> queryParams,
            PrefixMapping prefixes,
            Map<Var, List<Node>> bindings
    ) {
        if (queryParams == null || queryParams.isEmpty()) {
            return;
        }

        for (Map.Entry<String, List<String>> entry : queryParams.entrySet()) {
            Var var = Var.alloc(entry.getKey());
            List<Node> nodes = new ArrayList<>();

            for (String value : entry.getValue()) {
                if (value == null) {
                    continue;
                }

                String trimmed = value.trim();
                Object parsed = trimmed;

                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    parsed = new JSONObject(trimmed);
                } else if ("true".equalsIgnoreCase(trimmed)
                        || "false".equalsIgnoreCase(trimmed)) {
                    parsed = Boolean.parseBoolean(trimmed);
                } else {
                    try {
                        parsed = Integer.valueOf(trimmed);
                    } catch (NumberFormatException e1) {
                        try {
                            parsed = Long.valueOf(trimmed);
                        } catch (NumberFormatException e2) {
                            try {
                                parsed = Double.valueOf(trimmed);
                            } catch (NumberFormatException e3) {
                                // Keep it as a String.
                            }
                        }
                    }
                }

                Node node = toNode(parsed, prefixes);

                if (node != null) {
                    nodes.add(node);
                }
            }

            if (!nodes.isEmpty()) {
                bindings.put(var, nodes);
            }
        }
    }

    //====================================================================
    //writing
    // use for POST, PUT, PATCH, DELETE
    public RDFPatch toPatch(
            String method,
            Map<String, List<String>> queryParams,
            Object data,
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

        //TODO use queryParams
        walk(
                data,
                template,
                prefixes,
                uriSupplier,
                method,
                payload,
                datasetGraph,
                ctx
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
                //you should not be able to delete something which does not exist
                payload.find().forEachRemaining(q -> {
                    if (datasetGraph.contains(q)) {
                        collector.delete(q.getGraph(), q.getSubject(), q.getPredicate(), q.getObject());
                    }
                });
            }

            case "PATCH" -> {
                /*
                 * PATCH replaces only the predicates present
                 * in the submitted data.
                 */
                Set<Quad> deletes = new HashSet<>();
                Set<Quad> adds = new HashSet<>();

                payload.find().forEachRemaining(q -> {
                    // TODO later we need to support reverse
                    datasetGraph.find(q.getGraph(), q.getSubject(), q.getPredicate(), Node.ANY, ctx)
                            .forEachRemaining(deletes::add);

                    adds.add(q);
                });

                Set<Quad> cancelled = new HashSet<>(deletes);
                cancelled.retainAll(adds);
                deletes.removeAll(cancelled);
                adds.removeAll(cancelled);

                deletes.forEach(q -> collector.delete(q.getGraph(), q.getSubject(), q.getPredicate(), q.getObject()));
                adds.forEach(q -> collector.add(q.getGraph(), q.getSubject(), q.getPredicate(), q.getObject()));
            }

            case "PUT" -> {
                //PUT means we have to do a toJson and collect the triples if they would be queried all
                //so we delete the queried triples and insert the given ones to simulate a PUT

                //we modify the template: we remove limit so everything is queried and overwritten by PUT
                JSONObject templateCopy = new JSONObject(template.toString());
                removeAll(templateCopy, "$limit");

                Object dataFromQuery = toJson(queryParams, templateCopy, datasetGraph, ctx);

                RDFPatch deletePatch = toPatch("DELETE", queryParams, dataFromQuery, template, uriSupplier, datasetGraph, ctx);

                RDFPatch addPatch = toPatch("POST", queryParams, data, template, uriSupplier, datasetGraph, ctx);

                //a D and A with the same quad will not be in the patch
                RDFPatch combined = combine(deletePatch, addPatch);

                return combined;
            }

            default ->
                throw new IllegalArgumentException(
                        "Unsupported method: " + method
                );
        }

        return collector.getRDFPatch();
    }

    private RDFPatch combine(RDFPatch deletePatch, RDFPatch addPatch) {
        Set<Quad> deletes = new HashSet<>();
        deletePatch.apply(new RDFChangesBase() {
            @Override
            public void delete(Node g, Node s, Node p, Node o) {
                deletes.add(new Quad(g, s, p, o));
            }
        });

        Set<Quad> adds = new HashSet<>();
        addPatch.apply(new RDFChangesBase() {
            @Override
            public void add(Node g, Node s, Node p, Node o) {
                adds.add(new Quad(g, s, p, o));
            }
        });

        Set<Quad> cancelled = new HashSet<>(deletes);
        cancelled.retainAll(adds);
        deletes.removeAll(cancelled);
        adds.removeAll(cancelled);

        return RDFPatchOps.build(col -> {
            deletes.forEach(q -> col.delete(q.getGraph(), q.getSubject(), q.getPredicate(), q.getObject()));
            adds.forEach(q -> col.add(q.getGraph(), q.getSubject(), q.getPredicate(), q.getObject()));
        });
    }

    private void walk(
            Object data,
            Object template,
            PrefixMapping prefixes,
            Supplier<String> uriSupplier,
            String method,
            DatasetGraph payload,
            AticDatasetGraph datasetGraph,
            InvocationContext ctx
    ) {

        if (!(template instanceof JSONObject templateObj)) {
            return;
        }

        Node graph = getGraph(templateObj);

        /*
         * Query node
         */
        if (templateObj.has("$map")) {

            Object map = templateObj.get("$map");
            JSONArray where = templateObj.getJSONArray("$where");

            ParsedTemplate parsed = parseWhere(
                    map,
                    where,
                    prefixes
            );

            if (data instanceof JSONObject obj) {

                emitObject(
                        obj,
                        graph,
                        parsed,
                        prefixes,
                        uriSupplier,
                        method,
                        payload,
                        datasetGraph,
                        ctx
                );

            } else if (data instanceof JSONArray arr) {

                for (Object item : arr) {

                    if (item instanceof JSONObject obj) {

                        emitObject(
                                obj,
                                graph,
                                parsed,
                                prefixes,
                                uriSupplier,
                                method,
                                payload,
                                datasetGraph,
                                ctx
                        );

                    } else if (item instanceof String obj) {
                        /*
                        emitObject(
                                obj,
                                parsed,
                                prefixes,
                                uriSupplier,
                                payload
                        );
                         */
                    }
                }
            }

            /*
             * Continue walking nested template parts.
             */
            if (map instanceof JSONObject mapJsonObject) {
                for (String key : mapJsonObject.keySet()) {

                    Object childTemplate = mapJsonObject.get(key);

                    if (!(childTemplate instanceof JSONObject childTemplateObj)) {
                        continue;
                    }

                    if (data instanceof JSONObject obj && obj.has(key)) {

                        walk(
                                obj.get(key),
                                inherit(templateObj, childTemplateObj),
                                prefixes,
                                uriSupplier,
                                method,
                                payload,
                                datasetGraph,
                                ctx
                        );
                    }
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
                    method,
                    payload,
                    datasetGraph,
                    ctx
            );
        }
    }

    private void emitObject(
            JSONObject data,
            Node graph,
            ParsedTemplate parsed,
            PrefixMapping prefixes,
            Supplier<String> uriSupplier,
            String method,
            DatasetGraph payload,
            AticDatasetGraph datasetGraph,
            InvocationContext ctx
    ) {
        PrefixMap prefixMap = PrefixMapFactory.create(prefixes);

        /*
         * Variable bindings established while materializing this object.
         */
        //Map<Var, Node> bindings = new HashMap<>();
        Map<Var, List<Node>> bindings = new HashMap<>();

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

        bindings.put(parsed.rootVariable(), List.of(subject));

        /*
         * Bind JSON values.
         */
        for (Map.Entry<String, Triple> e : parsed.jsonMappings().entrySet()) {

            String jsonKey = e.getKey();

            if (!data.has(jsonKey)) {
                continue;
            }

            Triple t = e.getValue();

            List<Node> objects = toNodes(
                    data.get(jsonKey),
                    prefixes
            );

            Node object = toNode(
                    data.get(jsonKey),
                    prefixes
            );

            bindings.put(
                    (Var) t.getObject(),
                    objects
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
                        x -> List.of(NodeFactory.createBlankNode())
                );
            }

            if (t.getObject().isVariable()) {

                Var v = (Var) t.getObject();

                if (!bindings.containsKey(v)
                        && !parsed.literalVariables().contains(v)) {

                    bindings.put(
                            v,
                            List.of(NodeFactory.createBlankNode())
                    );
                }
            }
        }

        /*
         * Apply all currently known bindings to the graph pattern.
         *
         * If every variable is bound, the pattern can be emitted directly.
         * Otherwise, execute the remaining pattern as a SPARQL SELECT to
         * obtain the missing bindings.
         */
        List<Triple> whereTriplesBound = parsed.whereTriplesBound;

        boolean requiresQuery = false;

        for (Triple pattern : parsed.whereTriples()) {

            List<Node> ss = substitute(
                    pattern.getSubject(),
                    bindings
            );

            List<Node> ps = substitute(
                    pattern.getPredicate(),
                    bindings
            );

            List<Node> os = substitute(
                    pattern.getObject(),
                    bindings
            );

            if (ss.get(0).isVariable()
                    || ps.get(0).isVariable()
                    || os.get(0).isVariable()) {
                requiresQuery = true;
                break;
            } else {
                for (Node s : ss) {
                    for (Node p : ps) {
                        for (Node o : os) {
                            Triple bound = Triple.create(
                                    s,
                                    p,
                                    o
                            );
                            whereTriplesBound.add(bound);
                        }
                    }
                }
            }
        }

        /*
         * No unresolved variables remain. Emit the instantiated graph
         * pattern directly without executing a SPARQL query.
         */
        if (method.equals("PATCH") || !requiresQuery) {

            for (Triple pattern : whereTriplesBound) {

                payload.add(
                        Quad.create(
                                graph,
                                pattern.getSubject(),
                                pattern.getPredicate(),
                                pattern.getObject()
                        )
                );
            }

            return;
        }

        /*
         * At least one variable remains unresolved. Query the dataset
         * using the original graph pattern and the bindings established
         * above.
         */
        ParameterizedSparqlString pss
                = new ParameterizedSparqlString();

        pss.setNsPrefixes(prefixes);

        StringBuilder sparql = new StringBuilder("SELECT *\n");

        sparql.append("FROM <").append(graph).append(">\n");

        sparql.append("WHERE {\n");

        for (Map.Entry<Var, List<Node>> entry : bindings.entrySet()) {

            sparql.append(" VALUES ?")
                    .append(entry.getKey().getVarName())
                    .append(" { ");

            for (Node node : entry.getValue()) {
                sparql.append(NodeFmtLib.str(node, prefixMap))
                        .append(" ");
            }

            sparql.append("}\n");
        }

        for (Triple pattern : parsed.whereTriples()) {

            sparql.append("  ")
                    .append(NodeFmtLib.str(
                            pattern.getSubject(),
                            prefixMap
                    ))
                    .append(" ")
                    .append(NodeFmtLib.str(
                            pattern.getPredicate(),
                            prefixMap
                    ))
                    .append(" ")
                    .append(NodeFmtLib.str(
                            pattern.getObject(),
                            prefixMap
                    ))
                    .append(" .\n");
        }

        sparql.append("}");

        pss.setCommandText(sparql.toString());

        Query query = pss.asQuery();

        //temporary dataset with context
        Dataset ds = DatasetFactory.wrap(datasetGraph);
        ctx.transferContext(ds.getContext());

        try (QueryExecution qExec = QueryExecutionFactory.create(query, ds)) {

            ResultSet results = qExec.execSelect();

            while (results.hasNext()) {

                QuerySolution solution = results.next();

                Map<Var, List<Node>> completeBindings = new HashMap<>(bindings);

                solution.varNames().forEachRemaining(varName -> {

                    Node value = solution
                            .get(varName)
                            .asNode();

                    completeBindings.put(
                            Var.alloc(varName),
                            List.of(value)
                    );
                });

                /*
                 * Instantiate the complete graph pattern using all
                 * bindings obtained from the initial bindings and query.
                 */
                for (Triple pattern : parsed.whereTriples()) {

                    List<Node> ss = substitute(
                            pattern.getSubject(),
                            completeBindings
                    );

                    List<Node> ps = substitute(
                            pattern.getPredicate(),
                            completeBindings
                    );

                    List<Node> os = substitute(
                            pattern.getObject(),
                            completeBindings
                    );

                    for (Node s : ss) {
                        for (Node p : ps) {
                            for (Node o : os) {
                                Quad q = Quad.create(
                                        graph,
                                        s,
                                        p,
                                        o
                                );

                                payload.add(q);
                            }
                        }
                    }

                }
            }
        }
    }

    private Node getGraph(JSONObject template) {
        Object value = template.opt("$to");

        if (value == null) {
            value = template.opt("$from");
        }

        if (value == null) {
            return Quad.defaultGraphIRI;
        }

        String graph;

        if (value instanceof String) {
            graph = (String) value;
        } else if (value instanceof JSONArray array) {
            if (array.length() != 1 || !(array.get(0) instanceof String)) {
                throw new IllegalArgumentException(
                        "$from or $to must be a string or an array containing exactly one string"
                );
            }
            graph = array.getString(0);
        } else {
            throw new IllegalArgumentException(
                    "$from or $to must be a string or an array containing exactly one string"
            );
        }

        return NodeFactory.createURI(graph);
    }

    private List<Node> substitute(
            Node node,
            Map<Var, List<Node>> bindings
    ) {
        if (!node.isVariable()) {
            return List.of(node);
        }

        return bindings.getOrDefault(
                (Var) node,
                List.of(node)
        );
    }

    private ParsedTemplate parseWhere(
            Object map,
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

        Var directVariable = null;

        if (map instanceof String mapString) {

            if (mapString.startsWith("?")) {
                directVariable = Var.alloc(mapString.substring(1));
            }

        } else if (map instanceof JSONObject mapJsonObject) {
            for (String key : mapJsonObject.keySet()) {

                Object value = mapJsonObject.get(key);

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

                Var ov = (Var) triple.getObject();

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

                //direct variable
                if (ov.equals(directVariable)) {
                    jsonMappings.put(
                            "$map",
                            triple
                    );
                    literalVariables.add(ov);
                }
            }
        }

        return new ParsedTemplate(
                rootVariable,
                triples,
                new ArrayList<>(),
                jsonMappings,
                literalVariables
        );
    }

    private record ParsedTemplate(
            Var rootVariable,
            List<Triple> whereTriples,
            List<Triple> whereTriplesBound,
            Map<String, Triple> jsonMappings,
            Set<Var> literalVariables
            ) {

    }

    /*package*/ static Node parseToken(
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

        try {
            //try parse as URI
            URI.create(token);
            return NodeFactory.createURI(token);
        } catch (Exception e) {
            //ignore
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
            Object value,
            PrefixMapping prefixes
    ) {

        if (value == null
                || value == JSONObject.NULL) {

            return null;
        }

        /*
     * JSON-LD resource
         */
        if (value instanceof JSONObject obj
                && obj.has("@id")) {

            String uri = obj.getString("@id");

            uri = expandUri(uri, prefixes);

            return NodeFactory.createURI(uri);
        }

        /*
     * JSON-LD literal
         */
        if (value instanceof JSONObject obj
                && obj.has("@value")) {

            Object lexical = obj.get("@value");

            String lang = obj.optString("@language", null);

            if (lang != null && !lang.isBlank()) {

                return NodeFactory.createLiteralLang(
                        lexical.toString(),
                        lang
                );
            }

            String datatype = obj.optString("@type", null);

            if (datatype != null && !datatype.isBlank()) {

                datatype = expandUri(
                        datatype,
                        prefixes
                );

                return NodeFactory.createLiteralDT(
                        lexical.toString(),
                        new BaseDatatype(datatype)
                );
            }

            return NodeFactory.createLiteralString(
                    lexical.toString()
            );
        }

        /*
     * Native JSON values.
         */
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

        /*
        * String:
        *  - full URI
        *  - CURIE
        *  - prefix name
        *  - otherwise plain string
         */
        if (value instanceof String s) {

            String expanded = expandUri(
                    s,
                    prefixes
            );

            if (!expanded.equals(s)) {
                return NodeFactory.createURI(expanded);
            }

            return NodeFactory.createLiteralString(s);
        }

        return NodeFactory.createLiteralString(
                value.toString()
        );
    }

    private List<Node> toNodes(
            Object value,
            PrefixMapping prefixes
    ) {

        List<Node> nodes = new ArrayList<>();

        if (value == null
                || value == JSONObject.NULL) {

            return nodes;
        }

        /*
     * JSON array
         */
        if (value instanceof JSONArray array) {

            for (int i = 0; i < array.length(); i++) {

                nodes.addAll(
                        toNodes(
                                array.get(i),
                                prefixes
                        )
                );
            }

            return nodes;
        }

        /*
     * JSON-LD resource
         */
        if (value instanceof JSONObject obj
                && obj.has("@id")) {

            String uri = obj.getString("@id");

            uri = expandUri(uri, prefixes);

            nodes.add(NodeFactory.createURI(uri));

            return nodes;
        }

        /*
     * JSON-LD literal
         */
        if (value instanceof JSONObject obj
                && obj.has("@value")) {

            Object lexical = obj.get("@value");

            String lang = obj.optString("@language", null);

            if (lang != null && !lang.isBlank()) {

                nodes.add(
                        NodeFactory.createLiteralLang(
                                lexical.toString(),
                                lang
                        )
                );

                return nodes;
            }

            String datatype = obj.optString("@type", null);

            if (datatype != null && !datatype.isBlank()) {

                datatype = expandUri(
                        datatype,
                        prefixes
                );

                nodes.add(
                        NodeFactory.createLiteralDT(
                                lexical.toString(),
                                new BaseDatatype(datatype)
                        )
                );

                return nodes;
            }

            nodes.add(
                    NodeFactory.createLiteralString(
                            lexical.toString()
                    )
            );

            return nodes;
        }

        /*
     * Native JSON values.
         */
        if (value instanceof Integer i) {

            nodes.add(
                    NodeFactory.createLiteralByValue(
                            i,
                            XSDDatatype.XSDinteger
                    )
            );

            return nodes;
        }

        if (value instanceof Long l) {

            nodes.add(
                    NodeFactory.createLiteralByValue(
                            l,
                            XSDDatatype.XSDlong
                    )
            );

            return nodes;
        }

        if (value instanceof Double d) {

            nodes.add(
                    NodeFactory.createLiteralByValue(
                            d,
                            XSDDatatype.XSDdouble
                    )
            );

            return nodes;
        }

        if (value instanceof Float f) {

            nodes.add(
                    NodeFactory.createLiteralByValue(
                            f,
                            XSDDatatype.XSDfloat
                    )
            );

            return nodes;
        }

        if (value instanceof Boolean b) {

            nodes.add(
                    NodeFactory.createLiteralByValue(
                            b,
                            XSDDatatype.XSDboolean
                    )
            );

            return nodes;
        }

        /*
     * String:
     *  - full URI
     *  - CURIE
     *  - prefix name
     *  - otherwise plain string
         */
        if (value instanceof String s) {

            String expanded = expandUri(
                    s,
                    prefixes
            );

            if (!expanded.equals(s)) {

                nodes.add(
                        NodeFactory.createURI(expanded)
                );

                return nodes;
            }

            nodes.add(
                    NodeFactory.createLiteralString(s)
            );

            return nodes;
        }

        nodes.add(
                NodeFactory.createLiteralString(
                        value.toString()
                )
        );

        return nodes;
    }

    private String expandUri(
            String value,
            PrefixMapping prefixes
    ) {
        String expanded
                = prefixes.expandPrefix(value);

        if (!expanded.equals(value)) {
            return expanded;
        }

        String ns
                = prefixes.getNsPrefixURI(value);

        if (ns != null) {
            return ns;
        }

        return value;
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

    private static void removeAll(Object value, String key) {
        if (value instanceof JSONObject jsonObject) {
            // Copy the keys because we modify the object while traversing it.
            for (String currentKey : jsonObject.keySet().toArray(new String[0])) {
                Object child = jsonObject.opt(currentKey);

                if (currentKey.equals(key)) {
                    jsonObject.remove(currentKey);
                    continue;
                }

                if (child instanceof JSONObject || child instanceof JSONArray) {
                    removeAll(child, key);
                }
            }
        } else if (value instanceof JSONArray jsonArray) {
            for (int i = 0; i < jsonArray.length(); i++) {
                Object child = jsonArray.get(i);

                if (child instanceof JSONObject || child instanceof JSONArray) {
                    removeAll(child, key);
                }
            }
        }
    }
}
