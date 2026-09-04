package de.dfki.sds.aticsqlite.bridge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.sparql.core.Var;
import org.json.JSONArray;
import org.json.JSONObject;

public class SparqlQueryBuilder {

    public static final String COUNT_VARNAME = "sparqlQueryBuilderCount";

    public String build(
            JSONObject template,
            JSONObject root,
            Map<String, List<String>> queryParams,
            Map<Var, List<Node>> binding,
            boolean asCountQuery
    ) {
        JSONObject context = root.optJSONObject("@context");

        ParameterizedSparqlString pss
                = new ParameterizedSparqlString();

        appendPrefixes(
                pss,
                context
        );

        StringBuilder sparql
                = new StringBuilder();

        if (asCountQuery) {
            if (template.optBoolean("$distinct", false)) {
                sparql.append("SELECT (COUNT(DISTINCT *) AS ?" + COUNT_VARNAME + ")");
            } else {
                sparql.append("SELECT (COUNT(*) AS ?" + COUNT_VARNAME + ")");
            }

        } else {
            sparql.append("SELECT ");

            if (template.optBoolean("$distinct", false)) {
                sparql.append("DISTINCT ");
            }

            sparql.append(
                    buildSelectClause(template)
            );
        }

        appendFrom(
                sparql,
                template
        );

        sparql.append("\nWHERE {\n");

        appendValues(
                sparql,
                binding
        );

        appendWhere(
                sparql,
                template
        );

        appendFilters(
                sparql,
                template
        );

        sparql.append("}\n");

        appendGroupBy(
                sparql,
                template
        );

        if (!asCountQuery) {
            appendOrderBy(
                    sparql,
                    template
            );

            appendLimitOffset(
                    sparql,
                    template,
                    binding
            );
        }

        pss.setCommandText(
                sparql.toString()
        );

        return pss.toString();
    }

    private void appendPrefixes(
            ParameterizedSparqlString pss,
            JSONObject context
    ) {
        if (context == null) {
            return;
        }

        for (String key : context.keySet()) {

            Object value
                    = context.get(key);

            if (!(value instanceof String uri)) {
                continue;
            }

            if (!uri.endsWith("#")
                    && !uri.endsWith("/")) {
                continue;
            }

            pss.setNsPrefix(
                    key,
                    uri
            );
        }
    }

    private String buildSelectClause(
            JSONObject template
    ) {

        JSONObject map
                = template.optJSONObject("$map");

        if (map == null) {
            return "*";
        }

        Set<String> vars
                = new LinkedHashSet<>();

        collectVariables(
                map,
                vars
        );

        if (vars.isEmpty()) {
            return "*";
        }

        return String.join(
                " ",
                vars
        );
    }

    private void collectVariables(
            JSONObject obj,
            Set<String> vars
    ) {

        for (String key : obj.keySet()) {

            Object value
                    = obj.get(key);

            if (value instanceof String s) {

                if (s.startsWith("?")) {
                    vars.add(s);
                }

                continue;
            }

            if (value instanceof JSONObject child) {

                /*
                 * nested query has its own SELECT
                 */
                if (child.has("$where")) {
                    continue;
                }

                collectVariables(
                        child,
                        vars
                );
            }

            if (value instanceof JSONArray array) {

                collectVariables(
                        array,
                        vars
                );
            }
        }
    }

    private void collectVariables(
            JSONArray array,
            Set<String> vars
    ) {

        for (Object item : array) {

            if (item instanceof JSONObject obj) {

                collectVariables(
                        obj,
                        vars
                );
            }
        }
    }

    private void appendFrom(
            StringBuilder sparql,
            JSONObject template
    ) {
        Object from = template.opt("$from");

        if (from == null) {
            return;
        }

        if (from instanceof JSONArray) {
            JSONArray fromArray = (JSONArray) from;

            for (int i = 0; i < fromArray.length(); i++) {
                sparql.append("\nFROM <")
                        .append(fromArray.getString(i))
                        .append(">\n");
            }
        } else {
            sparql.append("\nFROM <")
                    .append(from.toString())
                    .append(">\n");
        }
    }

    private void appendValues(
            StringBuilder sparql,
            Map<Var, List<Node>> binding
    ) {
        if (binding == null || binding.isEmpty()) {
            return;
        }

        for (Map.Entry<Var, List<Node>> entry : binding.entrySet()) {
            sparql.append("VALUES ")
                    .append(entry.getKey())
                    .append(" {\n");

            for (Node node : entry.getValue()) {
                sparql.append("  ");
                appendNode(sparql, node);
                sparql.append("\n");
            }

            sparql.append("}\n");
        }
    }

    private void appendWhere(
            StringBuilder sparql,
            JSONObject template
    ) {

        JSONArray where
                = template.optJSONArray("$where");

        if (where == null) {
            return;
        }

        for (int i = 0; i < where.length(); i++) {

            String pattern
                    = where.getString(i);

            sparql.append("  ")
                    .append(pattern);

            if (!pattern.trim().endsWith(".")) {
                sparql.append(" .");
            }

            sparql.append("\n");
        }
    }

    private void appendFilters(
            StringBuilder sparql,
            JSONObject template
    ) {

        JSONArray filters
                = template.optJSONArray("$filter");

        if (filters == null) {
            return;
        }

        for (int i = 0; i < filters.length(); i++) {

            sparql.append("  FILTER(")
                    .append(filters.getString(i))
                    .append(")\n");
        }
    }

    private void appendGroupBy(
            StringBuilder sparql,
            JSONObject template
    ) {

        appendExpressionList(
                sparql,
                template.optJSONArray("$groupby"),
                "GROUP BY"
        );
    }

    private void appendOrderBy(
            StringBuilder sparql,
            JSONObject template
    ) {

        appendExpressionList(
                sparql,
                template.optJSONArray("$orderby"),
                "ORDER BY"
        );
    }

    private void appendExpressionList(
            StringBuilder sparql,
            JSONArray array,
            String keyword
    ) {

        if (array == null || array.isEmpty()) {
            return;
        }

        sparql.append(keyword)
                .append(" ");

        for (int i = 0; i < array.length(); i++) {

            sparql.append(
                    array.getString(i)
            );

            sparql.append(" ");
        }

        sparql.append("\n");
    }

    private void appendLimitOffset(StringBuilder sparql, JSONObject template, Map<Var, List<Node>> binding) {
        if (template.has("$limit")) {
            sparql.append("LIMIT ")
                    .append(resolveInt(template.get("$limit"), binding))
                    .append("\n");
        }

        if (template.has("$offset")) {
            sparql.append("OFFSET ")
                    .append(resolveInt(template.get("$offset"), binding))
                    .append("\n");
        }
    }

    private void appendNode(
            StringBuilder sparql,
            Node node
    ) {

        if (node.isURI()) {

            sparql.append("<")
                    .append(node.getURI())
                    .append(">");

        } else {

            sparql.append(node);
        }
    }

    private void appendValue(
            StringBuilder sparql,
            String value
    ) {

        if (value.startsWith("http://")
                || value.startsWith("https://")) {

            sparql.append("<")
                    .append(value)
                    .append(">");

            return;
        }

        sparql.append(value);
    }

    private int resolveInt(Object value, Map<Var, List<Node>> binding) {
        if (value instanceof Number number) {
            return number.intValue();
        }

        if (value instanceof String string && string.startsWith("?")) {
            Var var = Var.alloc(string.substring(1));

            if (!binding.containsKey(var)) {
                throw new IllegalArgumentException(
                        "Variable not found in binding: " + string
                );
            }

            List<Node> nodes = binding.get(var);
            
            if(nodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "No binding found for " + var
                );
            }

            if (!nodes.get(0).isLiteral()) {
                throw new IllegalArgumentException(
                        "Variable is not a literal: " + string
                );
            }

            try {
                return (Integer) nodes.get(0).getLiteral().getValue();
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Variable is not an integer: " + string, e
                );
            }
        }

        throw new IllegalArgumentException(
                "Expected integer or variable, got: " + value
        );
    }

}
