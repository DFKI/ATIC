package de.dfki.sds.aticserver.bridge;

import de.dfki.sds.atic.jenatic.AticDatasetGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.util.List;
import java.util.Map;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.query.ResultSetRewindable;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RdfJsonBridge {

    private static final Logger LOG
            = LoggerFactory.getLogger(RdfJsonBridge.class);

    private final TemplateParser projectionParser;
    private final SparqlQueryBuilder sparqlQueryBuilder;
    private final ResultSetJsonMapper resultSetJsonMapper;

    public RdfJsonBridge() {
        this(
                new TemplateParser(),
                new SparqlQueryBuilder(),
                new ResultSetJsonMapper()
        );
    }

    private RdfJsonBridge(
            TemplateParser projectionParser,
            SparqlQueryBuilder sparqlQueryBuilder,
            ResultSetJsonMapper resultSetJsonMapper
    ) {
        this.projectionParser = projectionParser;
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

    //use for POST, PUT, PATCH, DELETE
    RDFPatch toPatch(
            String operation,
            JSONObject data,
            JSONObject projection,
            AticDatasetGraph datasetGraph,
            InvocationContext ctx
    ) {
        return null;
    }

}
