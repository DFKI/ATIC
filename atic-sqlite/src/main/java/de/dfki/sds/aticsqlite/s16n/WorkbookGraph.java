package de.dfki.sds.aticsqlite.s16n;

import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.AticGraphUtils;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.RDFNode;
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
    public static final String ROW_ENTITY_VAR = "rowEntity";

    private SqliteAticDatasetGraph datasetGraph;

    private final TransactionHandler transactionHandler;
    private final GraphEventManager graphEventManager;

    private AticGraph innerGraph;

    public static final Resource Workbook = ResourceFactory.createResource("urn:atic:Workbook");

    //caches
    private IndexAllocation<Node> rowCache;
    private IndexAllocation<Node> columns;
    private Map<ResourceColumn, List<Node>> valueCache;
    private Query recentQuery;

    //settings
    private int pageSize = 20;
    private int valueCacheThreshold = 100;
    private int valueCacheCleanupMaxDist = pageSize * 3;
    private Integer maxNumberOfRows;

    /**
     * The invisible column for the entity. Has index smaller than 0.
     */
    private Node entityRowColumn = NodeFactory.createURI("urn:entity:row");

    private record ResourceColumn(Node resource, Node column) {

    }

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

        //get the config
        JSONObject json = getJson(workbook, ctx);
        JSONArray sheetArray = json.optJSONArray("sheets");
        if (sheetArray == null) {
            throw new IllegalStateException("Sheet not found in workbook: " + sheet.getURI());
        }
        String sheetId = sheet.getURI();
        JSONObject selectedSheet = null;
        for (int i = 0; i < sheetArray.length(); i++) {
            JSONObject sheetJson = sheetArray.getJSONObject(i);
            if (sheetId.equals(sheetJson.optString("@id"))) {
                selectedSheet = sheetJson;
                break;
            }
        }
        if (selectedSheet == null) {
            throw new IllegalStateException("Sheet not found in workbook: " + sheetId);
        }

        //we can not load all of them but a window
        //the row index decides what row entity we pick
        //decide: from cache or ask query window, cache it and use it
        //directly return empty cell if we reached the end
        if (maxNumberOfRows != null && rowIndex >= maxNumberOfRows) {
            return Cell.empty();
        }

        updateRowCache(selectedSheet.getString("rowQuery"), rowIndex, ctx);

        //row cache is now filled
        Node rowEntity = rowCache.getObject(rowIndex);

        //row does not exist
        if (rowEntity == null) {
            //empty cell
            return Cell.empty();
        }

        //special case: we are interested in the row entity, not one of its columns
        //we use a predefined entity row column here
        if (columnIndex < 0) {
            //return new Cell(rowEntity, entityRowColumn);
            return Cell.builder()
                    .addNode(node)
                    .workbook(workbook)
                    .sheet(sheet)
                    .column(entityRowColumn)
                    .rowIndex(rowIndex)
                    .columnIndex(columnIndex)
                    .build();
        }

        Node column = columns.getObject(columnIndex);

        //column does not exist
        if (column == null) {
            //empty cell
            return Cell.empty();
        }

        JSONArray columnArray = selectedSheet.optJSONArray("columns");
        String columnId = column.getURI();
        JSONObject selectedColumn = null;
        for (int i = 0; i < columnArray.length(); i++) {
            JSONObject columnJson = columnArray.getJSONObject(i);
            if (columnId.equals(columnJson.optString("@id"))) {
                selectedColumn = columnJson;
                break;
            }
        }
        if (selectedColumn == null) {
            throw new IllegalStateException("Column not found in sheet: " + sheetId);
        }
        
        ColumnConfig columnConfig = ColumnConfig.fromJson(selectedColumn);
        
        ResourceColumn key = updateValueCache(rowEntity, rowIndex, column, columnConfig, ctx);

        //value cache is now filled
        List<Node> values = valueCache.get(key);

        if (values == null) {
            throw new RuntimeException("values should never be null");
        }

        //return new Cell(values, column);
        return Cell.builder()
                .workbook(workbook)
                .sheet(sheet)
                .column(column)
                .rowIndex(rowIndex)
                .columnIndex(columnIndex)
                .nodes(values)
                .build();
    }

    public Window get(Node workbook, Node sheet, Rectangle rect, InvocationContext ctx) {
        return null;
    }

    public void set(Node workbook, Node sheet, int rowIndex, int columnIndex, Cell cell, InvocationContext ctx) {

    }

    public void set(Node workbook, Node sheet, Rectangle rect, Cell cell, InvocationContext ctx) {

    }

    //cache update
    private void updateRowCache(String rowQuery, int rowIndex, InvocationContext ctx) {
        //if resource was not found in cache, fill cache
        if (rowCache.getObject(rowIndex) == null) {
            Query query = getRowQuery(rowQuery, rowIndex);
            long offset = query.getOffset();
            Dataset ds = DatasetFactory.wrap(datasetGraph);
            ctx.transferContext(ds.getContext());
            ResultSet rs = QueryExecution.create(query, ds).execSelect();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                Resource re = qs.get(ROW_ENTITY_VAR).asResource();
                //row number starts at 1
                int index = (int) (offset + (rs.getRowNumber() - 1));
                rowCache.putOverwrite(re.asNode(), index);
            }
            //if we reach the end we mark it so that we do not ask again when caching
            if (rs.getRowNumber() < query.getLimit()) {
                //number of rows, so it is +1
                maxNumberOfRows = (int) (offset + rs.getRowNumber());
            }
        }
    }

    private ResourceColumn updateValueCache(Node rowEntity, int rowIndex, Node columnIndicator, ColumnConfig columnConfig, InvocationContext ctx) {
        //check the cache
        ResourceColumn key = new ResourceColumn(rowEntity, columnIndicator);
        if (!valueCache.containsKey(key)) {
            //if cache is empty we have to load it

            //again we only load all columns for our window
            int[] fromTo = getWindowFromTo(rowIndex);

            for (int i = fromTo[0]; i <= fromTo[1]; i++) {
                //we use the row cache, should be initialized already
                Node res = rowCache.getObject(i);

                //no recsource no row for it
                if (res == null) {
                    continue;
                }

                //for each column we need to know the cell values
                for (Node col : columns.toDeflatedList()) {

                    //because of deflated
                    if (col == null) {
                        continue;
                    }

                    datasetGraph.find(Node.ANY, s, p, o, ctx);
                    
                    List<Node> values = null;
                    switch (columnConfig.getDirection()) {
                        
                        
                        
                        case Outgoing:
                            values = model.listObjectsOfProperty(res, col.getProperty()).toList();
                            break;
                        case Incoming:
                            values = model.listSubjectsWithProperty(col.getProperty(), res).mapWith(r -> (RDFNode) r).toList();
                            break;
                    }

                    //update cache
                    valueCache.put(new ResourceColumn(res, col), values);
                }
            }

        }

        //if value cache get too large we need to reduce it again
        //does nothing if cache is not over threshold
        manageValueCache(rowIndex);
        
        
        return key;
    }

    //query management
    private Query getRowQuery(String rowQuery, int indexIndicator) {
        StringBuilder sb = new StringBuilder();
        sb.append(getPrefixes());
        //I guess we have to add distinct because bgps may yield multiple rows with same row
        sb.append("select distinct ?" + ROW_ENTITY_VAR + "\n");
        sb.append("{\n");

        sb.append(rowQuery).append("\n");

        //better is filter before sort so we have less to check in query
        //add column filters here
        /*
        List<String> filterVars = new ArrayList<>();
        for(ColumnFilter cf : filterList) {
            String filterVar = RandomStringUtils.randomAlphabetic(6);
            filterVars.add(filterVar);
            String bgp = cf.getColumn().getBGP(filterVar);
            //we do not have to use optional here, because filter should filter only the matching ones
            sb.append(bgp).append("\n");
        }
         */
 /*
        if(!filterVars.isEmpty()) {
            //we only provide and operator (&&)
            StringJoiner sj = new StringJoiner(" && ");
            for(int i = 0; i < filterVars.size(); i++) {
                String filterVar = filterVars.get(i);
                ColumnFilter columnFilter = filterList.get(i);
                sj.add(columnFilter.getFiltering().toFilter(filterVar));
            }
            sb.append("filter ( ").append(sj.toString()).append(" )").append("\n");
        }
         */
        List<String> sortVars = new ArrayList<>();
        /*
        for(ColumnSort cs : sortList) {
            String sortVar = RandomStringUtils.randomAlphabetic(6);
            sortVars.add(sortVar);
            
            String bgp = cs.getColumn().getBGP(sortVar);
            //we have to use optional because not all may have the property for sorting
            sb.append("optional { ").append(bgp).append(" }").append("\n");
        }
         */

        //where end
        sb.append("}");

        //parse the query and add the rest
        Query q = QueryFactory.create(sb.toString());

        /*
        //variable names for the order by
        if(sortList.isEmpty()) {
            //if no sorting is specified, we order by ROW_ENTITY_VAR to have a fixed order
            q.addOrderBy(NodeFactory.createVariable(ROW_ENTITY_VAR), Query.ORDER_DEFAULT);
        } else {
            for(int i = 0; i < sortVars.size(); i++) {
                String sortVar = sortVars.get(i);
                ColumnSort columnSort = sortList.get(i);
                q.addOrderBy(sortVar, toDirection(columnSort.getSorting()));
            }
        }
         */
        //offset and limit
        setOffsetAndLimit(q, indexIndicator);

        recentQuery = q;

        return q;
    }

    private String getPrefixes() {
        StringBuilder sb = new StringBuilder();
        this.datasetGraph.prefixes().forEach((prefix, uri) -> {
            sb.append(String.format("PREFIX %s: <%s>\n", prefix, uri));
        });
        return sb.toString();
    }

    private void setOffsetAndLimit(Query query, int indexIndicator) {
        int[] fromTo = getWindowFromTo(indexIndicator);
        query.setOffset(fromTo[0]);
        query.setLimit(pageSize);
    }

    private int[] getWindowFromTo(int indexIndicator) {
        int offset = indexIndicator;
        if (offset < 0) {
            offset = 0;
        }
        return new int[]{offset, offset + pageSize};
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
