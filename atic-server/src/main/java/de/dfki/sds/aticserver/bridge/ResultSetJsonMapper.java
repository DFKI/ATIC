package de.dfki.sds.aticserver.bridge;

import de.dfki.sds.atic.jenatic.AticDatasetGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.vocabulary.XSD;
import org.json.JSONArray;
import org.json.JSONObject;

public class ResultSetJsonMapper {

    private final Map<String, NodeModifier> modifiers = new HashMap<>();

    private final Model model = ModelFactory.createDefaultModel();

    public ResultSetJsonMapper() {
        registerModifier(
                "date",
                new DateModifier()
        );
        registerModifier(
                "string",
                new StringModifier()
        );

        registerModifier(
                "upper",
                new StringModifier(String::toUpperCase)
        );

        registerModifier(
                "lower",
                new StringModifier(String::toLowerCase)
        );

        registerModifier(
                "trim",
                new StringModifier(String::trim)
        );

        registerModifier(
                "capitalize",
                new StringModifier(s
                        -> s.isEmpty()
                ? s
                : Character.toUpperCase(s.charAt(0))
                + s.substring(1))
        );
    }

    public final void registerModifier(
            String name,
            NodeModifier modifier
    ) {
        modifiers.put(name, modifier);
    }

    public Object map(
            JSONObject queryNode,
            ResultSet rs,
            Map<String, List<String>> queryParams,
            AticDatasetGraph datasetGraph,
            InvocationContext ctx,
            PrefixMapping prefixes,
            RdfJsonBridge.TemplateExecutor executor
    ) {

        Object map = queryNode.get("$map");

        JSONArray array = new JSONArray();

        while (rs.hasNext()) {

            QuerySolution qs = rs.next();

            Binding binding = createBinding(qs);

            Object value;

            if (map instanceof JSONObject obj) {

                value = instantiate(
                        obj,
                        qs,
                        binding,
                        executor,
                        queryParams,
                        datasetGraph,
                        ctx,
                        prefixes
                );

            } else {

                value = resolveValue(
                        null,
                        map,
                        map,
                        qs,
                        datasetGraph,
                        ctx,
                        prefixes
                );
            }

            array.put(value);
        }

        String type = queryNode.optString("$type", null);

        if ("array".equals(type)) {
            return array;
        }

        if ("object".equals(type)) {

            if (array.isEmpty()) {
                return JSONObject.NULL;
            }

            return array.get(0);
        }

        /*
     * automatic behavior:
     *
     * 0 -> null
     * 1 -> value
     * n -> array
         */
        if (array.isEmpty()) {
            return JSONObject.NULL;
        }

        if (array.length() == 1) {
            return array.get(0);
        }

        return array;
    }

    private Object instantiate(
            Object map,
            QuerySolution qs,
            Binding binding,
            RdfJsonBridge.TemplateExecutor executor,
            Map<String, List<String>> queryParams,
            AticDatasetGraph datasetGraph,
            InvocationContext ctx,
            PrefixMapping prefixes
    ) {

        /*
     * "$map": "?name"
         */
        if (!(map instanceof JSONObject jsonMap)) {

            return resolveValue(
                    null,
                    map,
                    map,
                    qs,
                    datasetGraph,
                    ctx,
                    prefixes
            );
        }

        JSONObject json = new JSONObject();

        for (String key : jsonMap.keySet()) {

            Object value = jsonMap.get(key);

            /*
         * nested query object
             */
            if (value instanceof JSONObject child
                    && child.has("$where")) {

                Object nested = executor.execute(
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
                                ctx,
                                prefixes
                        )
                );

                continue;
            }

            /*
         * arrays
             */
            if (value instanceof JSONArray arr) {

                JSONArray copy = new JSONArray();

                for (Object item : arr) {

                    copy.put(
                            instantiate(
                                    item,
                                    qs,
                                    binding,
                                    executor,
                                    queryParams,
                                    datasetGraph,
                                    ctx,
                                    prefixes
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
                            map,
                            qs,
                            datasetGraph,
                            ctx,
                            prefixes
                    )
            );
        }

        return json;
    }

    //this is called to resolve a value
    private Object resolveValue(
            String key,
            Object value,
            Object map,
            QuerySolution qs,
            AticDatasetGraph datasetGraph,
            InvocationContext ctx,
            PrefixMapping prefixes
    ) {

        if (!(value instanceof String expr)) {
            return value;
        }

        /*
        * Separate expression and optional modifiers.
        *
        * Examples:
        *
        * ?date
        * ?date$date:dd.MM.yyyy
        * ?date$upper
        * $foaf:name
        * $foaf:name$date:dd.MM.yyyy
         */
        String[] parts = expr.split("\\$");

        RDFNode node;

        String base = parts[0];

        if (base.startsWith("?")) {

            node = qs.get(base.substring(1));

        } 
        else if (base.isEmpty()) {
            
            node = null;
            
            // expression started with '$'
            String propertyCuri = parts[1];
            String propertyUri = prefixes.expandPrefix(propertyCuri);
            Node property = NodeFactory.createURI(propertyUri);
            
            if (map != null && (map instanceof JSONObject jsonMap)) {
                
                String idVar = jsonMap.optString("@id");
                if(idVar == null) {
                    throw new IllegalArgumentException("@id required in $map");
                }
                
                String name = idVar.substring(1);
                Resource subject = qs.getResource(name);
                if(subject == null) {
                    throw new IllegalArgumentException("no subject bound with " + name);
                }
                
                Node n = datasetGraph.calculateRead(() -> {
                    ExtendedIterator<Quad> iter = (ExtendedIterator<Quad>) datasetGraph.find(null, subject.asNode(), property, Node.ANY);
                    Quad q = null;
                    if(iter.hasNext()) {
                        q = iter.next();
                    }
                    iter.close();
                    return q == null ? null : q.getObject();
                });
                
                if(n != null) {
                    node = model.asRDFNode(n);
                }
            }

            // modifiers start one later
            parts = Arrays.copyOfRange(parts, 1, parts.length);
            
        } else {
            return expr;
        }

        if (node == null) {
            return JSONObject.NULL;
        }

        Node current = node.asNode();

        /*
     * Apply modifiers.
         */
        for (int i = 1; i < parts.length; i++) {

            String modifierExpr = parts[i];

            String name;
            String argument = null;

            int idx = modifierExpr.indexOf(':');

            if (idx >= 0) {
                name = modifierExpr.substring(0, idx);
                argument = modifierExpr.substring(idx + 1);
            } else {
                name = modifierExpr;
            }

            NodeModifier modifier = modifiers.get(name);

            if (modifier == null) {
                throw new IllegalArgumentException(
                        "Unknown modifier: $" + name
                );
            }

            current = modifier.apply(
                    current,
                    argument
            );
        }

        if ("@id".equals(key)) {
            return current.getURI();
        }

        return toJson(model.asRDFNode(current)
        );
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

            return new JSONObject()
                    .put(
                            "@id",
                            node.asResource().getURI()
                    );
        }

        Literal lit = node.asLiteral();

        /*
     * Native JSON values.
         */
        Object value = lit.getValue();

        if (value instanceof Number
                || value instanceof Boolean) {

            return value;
        }

        /*
     * Language-tagged literal.
         */
        String lang = lit.getLanguage();

        if (!lang.isBlank()) {

            return new JSONObject()
                    .put("@value", lit.getLexicalForm())
                    .put("@language", lang);
        }

        /*
     * Typed literal.
     *
     * xsd:string is represented as a plain JSON string.
         */
        String datatype = lit.getDatatypeURI();

        if (datatype != null
                && !XSD.xstring.getURI().equals(datatype)) {

            return new JSONObject()
                    .put("@value", lit.getLexicalForm())
                    .put("@type", datatype);
        }

        /*
     * Plain string literal.
         */
        return lit.getLexicalForm();
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
