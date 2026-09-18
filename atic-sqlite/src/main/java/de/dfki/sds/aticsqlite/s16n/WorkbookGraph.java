package de.dfki.sds.aticsqlite.s16n;

import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.AticGraphUtils;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import de.dfki.sds.aticsqlite.bridge.FragmentSettings;
import de.dfki.sds.aticsqlite.bridge.ResultSetJsonMapper;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.sparql.core.Quad;
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

    
    //TODO if @type is same for all in content, could be also on Cell
    //TODO turn uri nodes to FragmentNodes
    
    //TODO build an endpoint: /workbooks/{uri}/sheets/{uri}/cells    with ?window=0,0,10,10 or ?position=0,0
    //use GET POST DELETE
    
    public static final Node node = NodeFactory.createURI("urn:atic:workbooks");

    /**
     * Varibale to incudle in the row Query. It has the '?' included.
     */
    public static final String ROW_ENTITY_VAR = "?rowEntity";

    private SqliteAticDatasetGraph datasetGraph;

    private final TransactionHandler transactionHandler;
    private final GraphEventManager graphEventManager;

    private AticGraph innerGraph;

    public static final Resource Workbook = ResourceFactory.createResource("urn:atic:Workbook");

    /**
     * The invisible column for the entity. Has index smaller than 0.
     */
    private final Node entityRowColumn = NodeFactory.createURI("urn:entity:row");

    private Map<WorkbookSheet, SheetCache> cacheMap;
    
    private ResultSetJsonMapper mapper;

    //TODO expiration time would be useful
    private class SheetCache {

        //caches
        private IndexAllocation<Node> rowCache;
        private IndexAllocation<Node> columnCache;
        private Map<ResourceColumn, List<Node>> valueCache;
        private Query recentQuery;

        //settings
        private int pageSize = 20;
        private int valueCacheThreshold = 100;
        private int valueCacheCleanupMaxDist = pageSize * 3;
        private Integer maxNumberOfRows;

        public SheetCache() {
            this.rowCache = new IndexAllocation<>();
            this.columnCache = new IndexAllocation<>();
            this.valueCache = new HashMap<>();
        }

    }

    private record ResourceColumn(Node resource, Node column) {

    }

    private record WorkbookSheet(Node workbook, Node sheet) {

    }

    public WorkbookGraph(SqliteAticDatasetGraph datasetGraph) {
        this.datasetGraph = datasetGraph;

        WorkbookGraph thisGraph = this;
        transactionHandler = new TransactionHandlerNull();
        graphEventManager = new SimpleEventManager();

        cacheMap = new HashMap<>();
        
        mapper = new ResultSetJsonMapper(FragmentSettings.defaultSettings());
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

        SheetCache cache = cacheMap.computeIfAbsent(new WorkbookSheet(workbook, sheet), k -> new SheetCache());

        //get the config
        JSONObject selectedWorkbook = getJson(workbook, ctx);

        JSONObject selectedSheet = getSheet(selectedWorkbook, sheet);

        SheetConfig sheetConfig = SheetConfig.fromJson(selectedSheet);

        //we can not load all of them but a window
        //the row index decides what row entity we pick
        //decide: from cache or ask query window, cache it and use it
        //directly return empty cell if we reached the end
        if (cache.maxNumberOfRows != null && rowIndex >= cache.maxNumberOfRows) {
            return Cell.builder()
                    .workbook(workbook)
                    .sheet(sheet)
                    .location(rowIndex, columnIndex)
                    .build();
        }

        updateRowCache(cache, sheetConfig.getRowQuery(), rowIndex, ctx);

        //row cache is now filled
        Node rowEntity = cache.rowCache.getObject(rowIndex);

        //row does not exist
        if (rowEntity == null) {
            //empty cell
            return Cell.builder()
                    .workbook(workbook)
                    .sheet(sheet)
                    .location(rowIndex, columnIndex)
                    .build();
        }

        //special case: we are interested in the row entity, not one of its columns
        //we use a predefined entity row column here
        if (columnIndex < 0) {
            //return new Cell(rowEntity, entityRowColumn);
            return Cell.builder()
                    .workbook(workbook)
                    .sheet(sheet)
                    .location(rowIndex, columnIndex)
                    .column(entityRowColumn)
                    .rowEntity(rowEntity)
                    .addNode(node)
                    .build();
        }

        updateColumnCache(selectedSheet.getJSONArray("columns"), cache);

        Node column = cache.columnCache.getObject(columnIndex);

        //column does not exist
        if (column == null) {
            //empty cell
            return Cell.builder()
                    .workbook(workbook)
                    .sheet(sheet)
                    .location(rowIndex, columnIndex)
                    .build();
        }

        //JSONObject selectedColumn = getColumn(selectedSheet, column);

        //ColumnConfig columnConfig = ColumnConfig.fromJson(selectedColumn);

        ResourceColumn key = updateValueCache(cache, rowEntity, rowIndex, column, selectedSheet, ctx);

        //value cache is now filled
        List<Node> values = cache.valueCache.get(key);

        if (values == null) {
            throw new RuntimeException("values should never be null");
        }

        return Cell.builder()
                .workbook(workbook)
                .sheet(sheet)
                .column(column)
                .rowEntity(rowEntity)
                .location(rowIndex, columnIndex)
                .nodes(values)
                .build();
    }

    public Window get(Node workbook, Node sheet, Rectangle rect, InvocationContext ctx) {
        Window window = new Window();
        for (int rowIndex = rect.y; rowIndex < rect.y + rect.height; rowIndex++) {
            List<Cell> row = new ArrayList<>();
            window.add(row);
            for (int colIndex = rect.x; colIndex < rect.x + rect.width; colIndex++) {
                Cell cell = get(workbook, sheet, rowIndex, colIndex, ctx);
                row.add(cell);
            }
        }
        return window;
    }

    public void set(Node workbook, Node sheet, int rowIndex, int columnIndex, Cell cell, Operation operation, InvocationContext ctx) {
        SheetCache cache = cacheMap.computeIfAbsent(new WorkbookSheet(workbook, sheet), k -> new SheetCache());

        JSONObject selectedWorkbook = getJson(workbook, ctx);
        JSONObject selectedSheet = getSheet(selectedWorkbook, sheet);
        SheetConfig sheetConfig = SheetConfig.fromJson(selectedSheet);
        
        updateRowCache(cache, sheetConfig.getRowQuery(), rowIndex, ctx);

        Node rowEntity = cache.rowCache.getObject(rowIndex);
        if (rowEntity == null) {
            throw new IllegalArgumentException("Cell has no row");
        }

        updateColumnCache(selectedSheet.getJSONArray("columns"), cache);

        Node column = cache.columnCache.getObject(columnIndex);

        //column does not exist
        if (column == null) {
            throw new IllegalArgumentException("Cell has no column");
        }

        JSONObject selectedColumn = getColumn(selectedSheet, column);
        ColumnConfig columnConfig = ColumnConfig.fromJson(selectedColumn);
        Node property = columnConfig.getProperty();

        switch (operation) {
            case Set:
                removeColumnValues(rowEntity, property, columnConfig.getDirection(), ctx);
                addColumnValues(rowEntity, property, columnConfig.getDirection(), cell.getNodes(), ctx);
                break;
            case Add:
                addColumnValues(rowEntity, property, columnConfig.getDirection(), cell.getNodes(), ctx);
                break;
            case Remove:
                removeColumnValues(rowEntity, property, columnConfig.getDirection(), cell.getNodes(), ctx);
                break;
        }
        
        //since add is lazy, we need to flush
        datasetGraph.flush();

        updateValueCache(cache, rowEntity, rowIndex, column, selectedSheet, ctx);
    }

    public void set(Node workbook, Node sheet, Rectangle rect, Cell cell, Operation operation, InvocationContext ctx) {
        for (int rowIndex = rect.y; rowIndex < rect.y + rect.height; rowIndex++) {
            for (int colIndex = rect.x; colIndex < rect.x + rect.width; colIndex++) {
                set(workbook, sheet, rowIndex, colIndex, cell, operation, ctx);
            }
        }
    }

    //set helper
    
    //TODO solve Quad.defaultGraphIRI for set
    
    private void addColumnValues(Node rowEntity, Node property, Direction direction, List<Node> values, InvocationContext ctx) {
        for (Node value : values) {
            if (direction == Direction.Outgoing) {
                datasetGraph.add(new Quad(Quad.defaultGraphIRI, rowEntity, property, value), ctx);
            } else {
                datasetGraph.add(new Quad(Quad.defaultGraphIRI, value, property, rowEntity), ctx);
            }
        }
    }

    private void removeColumnValues(Node rowEntity, Node property, Direction direction, List<Node> values, InvocationContext ctx) {
        for (Node value : values) {
            if (direction == Direction.Outgoing) {
                datasetGraph.delete(new Quad(Quad.defaultGraphIRI, rowEntity, property, value), ctx);
            } else {
                datasetGraph.delete(new Quad(Quad.defaultGraphIRI, value, property, rowEntity), ctx);
            }
        }
    }

    private void removeColumnValues(Node rowEntity, Node property, Direction direction, InvocationContext ctx) {
        ExtendedIterator<Quad> iterator = null;

        try {
            if (direction == Direction.Outgoing) {
                iterator = (ExtendedIterator<Quad>) datasetGraph.find(Node.ANY, rowEntity, property, Node.ANY, ctx);
            } else {
                iterator = (ExtendedIterator<Quad>) datasetGraph.find(Node.ANY, Node.ANY, property, rowEntity, ctx);
            }

            List<Quad> quads = iterator.toList();

            for (Quad quad : quads) {
                datasetGraph.delete(quad, ctx);
            }
        } finally {
            if (iterator != null) {
                iterator.close();
            }
        }
    }

    
    //cache update
    private void updateColumnCache(JSONArray columns, SheetCache cache) {
        for (int i = 0; i < columns.length(); i++) {
            //JSONObject property = columns.getJSONObject(i).getJSONObject("property");
            //Node node = NodeFactory.createURI(property.getString("@id"));

            Node node = NodeFactory.createURI(columns.getJSONObject(i).getString("@id"));

            if (!cache.columnCache.contains(node)) {
                cache.columnCache.add(node);
            }
        }
    }

    private void updateRowCache(SheetCache cache, String rowQuery, int rowIndex, InvocationContext ctx) {
        //if resource was not found in cache, fill cache
        if (cache.rowCache.getObject(rowIndex) == null) {
            Query query = getRowQuery(cache, rowQuery, rowIndex);
            long offset = query.getOffset();
            Dataset ds = DatasetFactory.wrap(datasetGraph);
            ctx.transferContext(ds.getContext());
            ResultSet rs = QueryExecution.create(query, ds).execSelect();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                Resource re = qs.get(ROW_ENTITY_VAR.substring(1)).asResource();
                //row number starts at 1
                int index = (int) (offset + (rs.getRowNumber() - 1));
                cache.rowCache.putOverwrite(re.asNode(), index);
            }
            //if we reach the end we mark it so that we do not ask again when caching
            if (rs.getRowNumber() < query.getLimit()) {
                //number of rows, so it is +1
                cache.maxNumberOfRows = (int) (offset + rs.getRowNumber());
            }
        }
    }

    private ResourceColumn updateValueCache(SheetCache cache, Node rowEntity, int rowIndex, Node columnIndicator, JSONObject selectedSheet, InvocationContext ctx) {
        //check the cache
        ResourceColumn key = new ResourceColumn(rowEntity, columnIndicator);
        if (!cache.valueCache.containsKey(key)) {
            //if cache is empty we have to load it

            //again we only load all columns for our window
            int[] fromTo = getWindowFromTo(cache.pageSize, rowIndex);

            for (int i = fromTo[0]; i <= fromTo[1]; i++) {
                //we use the row cache, should be initialized already
                Node res = cache.rowCache.getObject(i);

                //no recsource no row for it
                if (res == null) {
                    continue;
                }

                //for each column we need to know the cell values
                for (Node col : cache.columnCache.toDeflatedList()) {

                    //because of deflated
                    if (col == null) {
                        continue;
                    }

                    //TODO improve this later
                    JSONObject selectedColumn = getColumn(selectedSheet, col);

                    ColumnConfig columnConfig = ColumnConfig.fromJson(selectedColumn);

                    List<Node> values = new ArrayList<>();
                    Node property = columnConfig.getProperty();
                    ExtendedIterator<Quad> iterator = null;

                    try {
                        switch (columnConfig.getDirection()) {
                            case Outgoing:
                                iterator = (ExtendedIterator<Quad>) datasetGraph.find(Node.ANY, res, property, Node.ANY, ctx);
                                while (iterator.hasNext()) {
                                    values.add(iterator.next().getObject());
                                }
                                break;
                            case Incoming:
                                iterator = (ExtendedIterator<Quad>) datasetGraph.find(Node.ANY, Node.ANY, property, res, ctx);
                                while (iterator.hasNext()) {
                                    values.add(iterator.next().getSubject());
                                }
                                break;
                        }
                    } finally {
                        if (iterator != null) {
                            iterator.close();
                        }
                    }

                    //TODO uri node can be enriched to FragmentNodes
                    
                    //update cache
                    cache.valueCache.put(new ResourceColumn(res, col), values);
                }
            }

        }

        //if value cache get too large we need to reduce it again
        //does nothing if cache is not over threshold
        manageValueCache(cache, rowIndex);

        return key;
    }

    private void manageValueCache(SheetCache cache, int indexIndicator) {
        //only do something when over threshold
        if (cache.valueCache.size() < cache.valueCacheThreshold) {
            return;
        }

        Set<ResourceColumn> toBeRemoved = new HashSet<>();
        for (ResourceColumn rc : cache.valueCache.keySet()) {
            Integer index = cache.rowCache.getIndex(rc.resource);
            //if the resource is not part of the current row cache
            //its values might also be not needed for now
            if (index == null) {
                toBeRemoved.add(rc);
            } else {
                int dist = Math.abs(index - indexIndicator);
                //heuristic:
                //if large distance, these values might not be needed for the current displayed indices
                if (dist > cache.valueCacheCleanupMaxDist) {
                    toBeRemoved.add(rc);
                }
            }
        }
        toBeRemoved.forEach(rc -> cache.valueCache.remove(rc));

        //int size = valueCache.size();
        /*
        log("manageValueCache(" + indexIndicator + "): " + toBeRemoved.size() + 
            " cleaned because valueCache size of " + size + " >= " + valueCacheThreshold + ". " +
            "valueCache size is now " + valueCache.size()
        );
         */
    }

    private JSONObject getSheet(JSONObject selectedWorkbook, Node sheet) {
        JSONArray sheetArray = selectedWorkbook.optJSONArray("sheets");
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
        return selectedSheet;
    }

    private JSONObject getColumn(JSONObject selectedSheet, Node column) {
        JSONArray columnArray = selectedSheet.optJSONArray("columns");
        String columnId = column.getURI();
        JSONObject selectedColumn = null;
        for (int i = 0; i < columnArray.length(); i++) {
            JSONObject columnJson = columnArray.getJSONObject(i);
            if (columnId.equals(columnJson.getString("@id"))) {
                selectedColumn = columnJson;
                break;
            }
        }
        if (selectedColumn == null) {
            throw new IllegalStateException("Column not found: " + column);
        }
        return selectedColumn;
    }

    //query management
    private Query getRowQuery(SheetCache cache, String rowQuery, int indexIndicator) {
        StringBuilder sb = new StringBuilder();
        sb.append(getPrefixes());
        //I guess we have to add distinct because bgps may yield multiple rows with same row
        sb.append("select distinct " + ROW_ENTITY_VAR + "\n");
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
        setOffsetAndLimit(cache.pageSize, q, indexIndicator);

        cache.recentQuery = q;

        return q;
    }

    private String getPrefixes() {
        StringBuilder sb = new StringBuilder();
        this.datasetGraph.prefixes().forEach((prefix, uri) -> {
            sb.append(String.format("PREFIX %s: <%s>\n", prefix, uri));
        });
        return sb.toString();
    }

    private void setOffsetAndLimit(int pageSize, Query query, int indexIndicator) {
        int[] fromTo = getWindowFromTo(pageSize, indexIndicator);
        query.setOffset(fromTo[0]);
        query.setLimit(pageSize);
    }

    private int[] getWindowFromTo(int pageSize, int indexIndicator) {
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
    
    //getter & setter
    
    public FragmentSettings getFragmentSettings() {
        return mapper.getFragmentSettings();
    }

    public void setFragmentSettings(FragmentSettings fragmentSettings) {
        mapper.setFragmentSettings(fragmentSettings);
    }

}
