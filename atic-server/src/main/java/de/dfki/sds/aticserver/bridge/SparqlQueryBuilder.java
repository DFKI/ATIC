package de.dfki.sds.aticserver.bridge;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;
import org.json.JSONArray;
import org.json.JSONObject;
public class SparqlQueryBuilder {

    public String build(
            JSONObject template,
            Map<String, List<String>> queryParams,
            Binding binding
    ) {

        ParameterizedSparqlString pss =
                new ParameterizedSparqlString();


        appendPrefixes(
                pss,
                template
        );


        StringBuilder sparql =
                new StringBuilder();


        sparql.append("SELECT ");


        if (template.optBoolean("$distinct", false)) {
            sparql.append("DISTINCT ");
        }


        sparql.append(
                buildSelectClause(template)
        );


        appendFrom(
                sparql,
                template
        );


        sparql.append("\nWHERE {\n");


        appendValues(
                sparql,
                queryParams,
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


        appendOrderBy(
                sparql,
                template
        );


        appendLimitOffset(
                sparql,
                template
        );


        pss.setCommandText(
                sparql.toString()
        );


        return pss.toString();
    }


    private void appendPrefixes(
            ParameterizedSparqlString pss,
            JSONObject template
    ) {

        JSONObject context =
                template.optJSONObject("@context");


        if (context == null) {
            return;
        }


        for (String key : context.keySet()) {

            Object value =
                    context.get(key);


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

        JSONObject map =
                template.optJSONObject("$map");


        if (map == null) {
            return "*";
        }


        Set<String> vars =
                new LinkedHashSet<>();


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

            Object value =
                    obj.get(key);


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

        JSONArray from =
                template.optJSONArray("$from");


        if (from == null) {
            return;
        }


        for (int i = 0; i < from.length(); i++) {

            sparql.append("FROM <")
                    .append(from.getString(i))
                    .append(">\n");
        }
    }


    private void appendValues(
            StringBuilder sparql,
            Map<String, List<String>> queryParams,
            Binding binding
    ) {

        if (binding != null
                && !binding.isEmpty()) {

            Iterator<Var> vars =
                    binding.vars();


            while (vars.hasNext()) {

                Var var =
                        vars.next();


                Node node =
                        binding.get(var);


                sparql.append("VALUES ")
                        .append(var)
                        .append(" { ");


                appendNode(
                        sparql,
                        node
                );


                sparql.append(" }\n");
            }
        }


        if (queryParams == null) {
            return;
        }


        for (Map.Entry<String,List<String>> entry :
                queryParams.entrySet()) {


            String variable =
                    entry.getKey();


            if (!variable.startsWith("?")) {
                variable = "?" + variable;
            }


            sparql.append("VALUES ")
                    .append(variable)
                    .append(" {\n");


            for (String value :
                    entry.getValue()) {


                sparql.append("  ");

                appendValue(
                        sparql,
                        value
                );

                sparql.append("\n");
            }


            sparql.append("}\n");
        }
    }


    private void appendWhere(
            StringBuilder sparql,
            JSONObject template
    ) {

        JSONArray where =
                template.optJSONArray("$where");


        if (where == null) {
            return;
        }


        for (int i = 0; i < where.length(); i++) {

            String pattern =
                    where.getString(i);


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

        JSONArray filters =
                template.optJSONArray("$filter");


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


    private void appendLimitOffset(
            StringBuilder sparql,
            JSONObject template
    ) {

        if (template.has("$limit")) {

            sparql.append("LIMIT ")
                    .append(template.getInt("$limit"))
                    .append("\n");
        }


        if (template.has("$offset")) {

            sparql.append("OFFSET ")
                    .append(template.getInt("$offset"))
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
}