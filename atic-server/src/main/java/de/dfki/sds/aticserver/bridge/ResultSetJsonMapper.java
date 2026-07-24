package de.dfki.sds.aticserver.bridge;

import de.dfki.sds.atic.jenatic.AticDatasetGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;
import org.json.JSONArray;
import org.json.JSONObject;

public class ResultSetJsonMapper {

    public Object map(
            JSONObject queryNode,
            ResultSet rs,
            Map<String, List<String>> queryParams,
            AticDatasetGraph datasetGraph,
            InvocationContext ctx,
            RdfJsonBridge.TemplateExecutor executor
    ) {
        
        JSONObject map
                = queryNode.getJSONObject("$map");

        JSONArray array
                = new JSONArray();

        while (rs.hasNext()) {

            QuerySolution qs
                    = rs.next();

            Binding binding
                    = createBinding(qs);

            JSONObject obj
                    = instantiate(
                            map,
                            qs,
                            binding,
                            executor,
                            queryParams,
                            datasetGraph,
                            ctx
                    );

            array.put(obj);
        }

        String type
                = queryNode.optString(
                        "$type",
                        null
                );

        if ("array".equals(type)) {
            return array;
        }

        if ("object".equals(type)) {

            if (array.isEmpty()) {
                return JSONObject.NULL;
            }

            return array.getJSONObject(0);
        }


        /*
         * automatic behavior:
         *
         * 0 -> null
         * 1 -> object
         * n -> array
         */
        if (array.isEmpty()) {
            return JSONObject.NULL;
        }

        if (array.length() == 1) {
            return array.getJSONObject(0);
        }

        return array;
    }

    private JSONObject instantiate(
            JSONObject map,
            QuerySolution qs,
            Binding binding,
            RdfJsonBridge.TemplateExecutor executor,
            Map<String, List<String>> queryParams,
            AticDatasetGraph datasetGraph,
            InvocationContext ctx
    ) {

        JSONObject json
                = new JSONObject();

        for (String key : map.keySet()) {

            Object value
                    = map.get(key);


            /*
             * nested query object
             */
            if (value instanceof JSONObject child
                    && child.has("$where")) {

                Object nested
                        = executor.execute(
                                child,
                                binding
                        );

                json.put(
                        key,
                        nested
                );

                continue;
            }


            /*
             * nested static object
             */
            if (value instanceof JSONObject child) {

                json.put(
                        key,
                        instantiate(
                                child,
                                qs,
                                binding,
                                executor,
                                queryParams,
                                datasetGraph,
                                ctx
                        )
                );

                continue;
            }


            /*
             * arrays
             */
            if (value instanceof JSONArray array) {

                JSONArray copy
                        = new JSONArray();

                for (Object item : array) {

                    copy.put(
                            resolveValue(
                                    null,
                                    item,
                                    qs
                            )
                    );
                }

                json.put(
                        key,
                        copy
                );

                continue;
            }

            json.put(
                    key,
                    resolveValue(
                            key,
                            value,
                            qs
                    )
            );
        }

        return json;
    }

    //this is called to resolve a value
    private Object resolveValue(
            String key,
            Object value,
            QuerySolution qs
    ) {

        if (!(value instanceof String expr)) {
            return value;
        }

        /*
         * ?variable
         */
        if (expr.startsWith("?")) {

            RDFNode node
                    = qs.get(
                            expr.substring(1)
                    );
            
            if(key.equals("@id")) {
                return node.asResource().getURI();
            }

            return toJson(node);
        }


        /*
         * $property alias
         */
        if (expr.startsWith("$")) {

            String variable
                    = propertyVariable(expr);

            RDFNode node
                    = qs.get(variable);
            
            if(key.equals("@id")) {
                return node.asResource().getURI();
            }

            return toJson(node);
        }

        return expr;
    }

    //create a binding from an existing one
    private Binding createBinding(
            QuerySolution qs
    ) {

        BindingBuilder builder
                = Binding.builder();

        Iterator<String> vars
                = qs.varNames();

        while (vars.hasNext()) {

            String var
                    = vars.next();

            builder.add(
                    Var.alloc(var),
                    qs.get(var).asNode()
            );
        }

        return builder.build();
    }

    //turn rdf node to json
    private Object toJson(
            RDFNode node
    ) {

        if (node == null) {
            return JSONObject.NULL;
        }

        if (node.isResource()) {

            JSONObject obj
                    = new JSONObject();

            obj.put(
                    "@id",
                    node.asResource()
                            .getURI()
            );

            return obj;
        }

        Literal lit
                = node.asLiteral();

        Object value
                = lit.getValue();

        if (value instanceof Number
                || value instanceof Boolean) {

            return value;
        }

        return value.toString();
    }

    private String propertyVariable(
            String expr
    ) {

        String s
                = expr.substring(1);

        int idx
                = s.indexOf(':');

        if (idx >= 0) {
            s = s.substring(
                    idx + 1
            );
        }

        idx
                = s.indexOf('$');

        if (idx >= 0) {
            s = s.substring(
                    0,
                    idx
            );
        }

        return s;
    }
}
