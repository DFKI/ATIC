package de.dfki.sds.aticsqlite.s16n;

import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.AticGraphUtils;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.GraphEventManager;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.TransactionHandler;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.impl.GraphMatcher;
import org.apache.jena.graph.impl.SimpleEventManager;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.sparql.graph.PrefixMappingAdapter;
import org.apache.jena.sparql.graph.TransactionHandlerNull;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.vocabulary.RDF;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 *
 */
public class WorkbookGraph implements AticGraph {

    public static final Node node = NodeFactory.createURI("urn:atic:workbooks");

    private SqliteAticDatasetGraph datasetGraph;

    private final TransactionHandler transactionHandler;
    private final GraphEventManager graphEventManager;

    private AticGraph innerGraph;

    public static final Resource Workbook = ResourceFactory.createResource("urn:atic:Workbook");

    public WorkbookGraph(SqliteAticDatasetGraph datasetGraph) {
        this.datasetGraph = datasetGraph;

        WorkbookGraph thisGraph = this;
        transactionHandler = new TransactionHandlerNull();
        graphEventManager = new SimpleEventManager();
    }

    public void ensureGraph(InvocationContext ctx) {
        boolean exists = datasetGraph.calculateRead(() -> {
            return datasetGraph.containsGraph(node, ctx);
        });
        if (!exists) {
            datasetGraph.executeWrite(() -> {
                datasetGraph.addGraph(node, Graph.emptyGraph, ctx);
            });
        }
        innerGraph = datasetGraph.calculateRead(() -> {
            return datasetGraph.getGraph(node, ctx);
        });
    }

    //-------------------------------
    //api
    public JSONObject getJson(Node workbook, InvocationContext ctx) {

        ExtendedIterator<Triple> iter
                = innerGraph.find(workbook, RDF.value.asNode(), null, ctx);

        try {
            if (!iter.hasNext()) {
                throw new IllegalStateException(
                        "No JSON value available for workbook: " + workbook
                );
            }

            Triple triple = iter.next();
            Node object = triple.getObject();

            if (!object.isLiteral()) {
                throw new IllegalStateException(
                        "Expected JSON value to be a literal, but got: " + object
                );
            }

            return new JSONObject(object.getLiteralLexicalForm());
        } finally {
            iter.close();
        }
    }

    private void setJson(Node workbook, JSONObject json, InvocationContext ctx) {
        innerGraph.remove(workbook, RDF.value.asNode(), null, ctx);

        Node jsonNode = NodeFactory.createLiteralDT(
                json.toString(),
                RDF.dtRDFJSON
        );

        innerGraph.add(
                Triple.create(
                        workbook,
                        RDF.value.asNode(),
                        jsonNode
                ),
                ctx
        );
    }

    public List<Node> addWorkbooks(List<WorkbookConfig> workbookConfigs, InvocationContext ctx) {
        return workbookConfigs.stream()
                .map(workbookConfig -> {
                    String urn = AticGraphUtils.createURN("workbook");
                    Node workbook = NodeFactory.createURI(urn);

                    innerGraph.add(
                            Triple.create(
                                    workbook,
                                    RDF.type.asNode(),
                                    Workbook.asNode()
                            ),
                            ctx
                    );

                    setJson(workbook, workbookConfig.toJson(), ctx);

                    return workbook;
                })
                .toList();
    }

    public void removeWorkbooks(Set<Node> workbooks, InvocationContext ctx) {
        for (Node workbook : workbooks) {
            innerGraph.remove(workbook, Node.ANY, Node.ANY, ctx);
            innerGraph.remove(Node.ANY, Node.ANY, workbook, ctx);
        }
    }

    public List<Node> addSheets(Node workbook, List<SheetConfig> sheetConfigs, InvocationContext ctx) {

        JSONObject json = getJson(workbook, ctx);
        JSONArray sheets = json.optJSONArray("sheets");

        if (sheets == null) {
            sheets = new JSONArray();
            json.put("sheets", sheets);
        }

        List<Node> result = new ArrayList<>();

        for (SheetConfig sheetConfig : sheetConfigs) {
            String urn = AticGraphUtils.createURN("sheet");
            Node sheet = NodeFactory.createURI(urn);

            JSONObject sheetJson = sheetConfig.toJson();
            sheetJson.put("@id", urn);

            sheets.put(sheetJson);
            result.add(sheet);
        }

        setJson(workbook, json, ctx);

        return result;
    }

    public void removeSheets(Node workbook, Set<Node> sheets, InvocationContext ctx) {
        JSONObject json = getJson(workbook, ctx);
        JSONArray sheetArray = json.optJSONArray("sheets");

        if (sheetArray == null) {
            return;
        }

        Set<String> sheetIds = sheets.stream().map(Node::getURI).collect(Collectors.toSet());

        JSONArray remaining = new JSONArray();
        for (int i = 0; i < sheetArray.length(); i++) {
            JSONObject sheetJson = sheetArray.getJSONObject(i);
            if (!sheetIds.contains(sheetJson.optString("@id"))) {
                remaining.put(sheetJson);
            }
        }

        json.put("sheets", remaining);
        setJson(workbook, json, ctx);
    }

    public List<Node> addColumns(Node workbook, Node sheet, List<ColumnConfig> columnConfigs, InvocationContext ctx) {
        JSONObject json = getJson(workbook, ctx);
        JSONArray sheets = json.optJSONArray("sheets");

        if (sheets == null) {
            throw new IllegalStateException("No sheets available for workbook: " + workbook);
        }

        JSONObject sheetJson = null;
        String sheetUri = sheet.getURI();

        for (int i = 0; i < sheets.length(); i++) {
            JSONObject candidate = sheets.getJSONObject(i);
            if (sheetUri.equals(candidate.optString("@id", null))) {
                sheetJson = candidate;
                break;
            }
        }

        if (sheetJson == null) {
            throw new IllegalStateException("Sheet not found in workbook: " + sheetUri);
        }

        JSONArray columns = sheetJson.optJSONArray("columns");
        if (columns == null) {
            columns = new JSONArray();
            sheetJson.put("columns", columns);
        }

        List<Node> result = new ArrayList<>();

        for (ColumnConfig columnConfig : columnConfigs) {
            String urn = AticGraphUtils.createURN("column");
            Node column = NodeFactory.createURI(urn);

            JSONObject columnJson = columnConfig.toJson();
            columnJson.put("@id", urn);
            columns.put(columnJson);
            result.add(column);
        }

        setJson(workbook, json, ctx);
        return result;
    }

    public void removeColumns(Node workbook, Node sheet, Set<Node> columns, InvocationContext ctx) {
        JSONObject json = getJson(workbook, ctx);
        JSONArray sheets = json.optJSONArray("sheets");
        String sheetId = sheet.getURI();

        if (sheets == null) {
            throw new IllegalStateException("Sheet not found in workbook: " + sheetId);
        }

        Set<String> columnIds = columns.stream().map(Node::getURI).collect(Collectors.toSet());

        for (int i = 0; i < sheets.length(); i++) {
            JSONObject sheetJson = sheets.getJSONObject(i);

            if (!sheetId.equals(sheetJson.optString("@id"))) {
                continue;
            }

            JSONArray columnArray = sheetJson.optJSONArray("columns");
            if (columnArray == null) {
                return;
            }

            JSONArray remaining = new JSONArray();
            for (int j = 0; j < columnArray.length(); j++) {
                JSONObject columnJson = columnArray.getJSONObject(j);
                if (!columnIds.contains(columnJson.optString("@id"))) {
                    remaining.put(columnJson);
                }
            }

            sheetJson.put("columns", remaining);
            setJson(workbook, json, ctx);
            return;
        }

        throw new IllegalStateException("Sheet not found in workbook: " + sheetId);
    }

    //sheet stuff
    public Cell get(Node workbook, Node sheet, int rowIndex, int columnIndex, InvocationContext ctx) {
        return null;
    }

    public Window get(Node workbook, Node sheet, Rectangle rect, InvocationContext ctx) {
        return null;
    }

    public void set(Node workbook, Node sheet, int rowIndex, int columnIndex, Cell cell, InvocationContext ctx) {

    }

    public void set(Node workbook, Node sheet, Rectangle rect, Cell cell, InvocationContext ctx) {

    }

    //----------------------------------
    //read
    @Override
    public ExtendedIterator<Triple> find(Node s, Node p, Node o, InvocationContext ctx) {
        return innerGraph.find(s, p, o, ctx);
    }

    @Override
    public boolean contains(Node s, Node p, Node o, InvocationContext ctx) {
        return innerGraph.contains(s, p, o, ctx);
    }

    @Override
    public int size(InvocationContext ctx) {
        return innerGraph.size(ctx);
    }

    //-----------------------------
    //write
    @Override
    public void add(Triple t, InvocationContext ctx) {
        throw new UnsupportedOperationException(node + " cannot be modified directly.");
    }

    @Override
    public void remove(Node s, Node p, Node o, InvocationContext ctx) {
        throw new UnsupportedOperationException(node + " cannot be modified directly.");
    }

    //delete reuses remove
    @Override
    public void delete(Triple t, InvocationContext ctx) {
        throw new UnsupportedOperationException(node + " cannot be modified directly.");
    }

    //clear reuses remove with Node.ANY, Node.ANY, Node.ANY
    @Override
    public void clear(InvocationContext ctx) {
        throw new UnsupportedOperationException(node + " cannot be modified directly.");
    }

    //------------------------------------------------------
    //the delegates (e.g. opens Triple and delegates to s,p,o method)
    @Override
    public boolean contains(Triple t, InvocationContext ctx) {
        return contains(t.getSubject(), t.getPredicate(), t.getObject(), ctx);
    }

    @Override
    public ExtendedIterator<Triple> find(Triple t, InvocationContext ctx) {
        return find(t.getSubject(), t.getPredicate(), t.getObject(), ctx);
    }

    @Override
    public boolean isEmpty(InvocationContext ctx) {
        return size(ctx) == 0;
    }

    //============================
    //management and other
    @Override
    public void close(InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean isClosed(InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public PrefixMapping getPrefixMapping(InvocationContext ctx) {
        return new PrefixMappingAdapter(datasetGraph.prefixes(ctx));
    }

    @Override
    public TransactionHandler getTransactionHandler() {
        return transactionHandler;
    }

    @Override
    public GraphEventManager getEventManager() {
        return graphEventManager;
    }

    @Override
    public boolean isIsomorphicWith(Graph g, InvocationContext ctx) {
        //TODO isIsomorphicWith currently does not check permissions
        return GraphMatcher.equals(this, g);
    }

    @Override
    public boolean dependsOn(Graph other, InvocationContext ctx) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

}
