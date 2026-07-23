package de.dfki.sds.aticserver;

import de.dfki.sds.atic.helper.JSONUtils;
import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.PagedTripleIterator;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import de.dfki.sds.aticsqlite.SqliteAticGraph;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.impl.LiteralLabel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.vocabulary.FOAF;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 *
 */
public class MoleculeEndpoint {

    private List<Property> typeProperties;
    private List<Property> iconProperties;
    private List<Property> labelProperties;
    private List<Property> commentProperties;

    //TODO later better ontology vocab
    private String MORE_SPL_URI = "urn:atic:moreSPL";
    private String MORE_SPO_URI = "urn:atic:moreSPO";
    private String MORE_OPS_URI = "urn:atic:moreOPS";

    private final boolean PRINT = false;

    public MoleculeEndpoint() {
        this.typeProperties = new ArrayList<>();
        this.iconProperties = new ArrayList<>();
        this.labelProperties = new ArrayList<>();
        this.commentProperties = new ArrayList<>();

        defaultProperties();
    }

    public final void defaultProperties() {
        typeProperties.add(RDF.type);

        iconProperties.add(FOAF.img);

        labelProperties.add(SKOS.prefLabel);
        labelProperties.add(RDFS.label);

        commentProperties.add(RDFS.comment);
    }

    public void register(RoutesConfig routes, String path, SqliteAticDatasetGraph datasetGraph) {
        routes.get(path + "resource" + "/{uri}", ctx -> handleGetInstance(ctx, Rendering.RESOURCE, datasetGraph));
        routes.get(path + "fragment" + "/{uri}", ctx -> handleGetInstance(ctx, Rendering.FRAGMENT, datasetGraph));
        routes.get(path + "molecule" + "/{uri}", ctx -> handleGetInstance(ctx, Rendering.MOLECULE, datasetGraph));

        routes.get(path + "molecule", this::handleGetDocumentation);
        
        //TODO maybe later other endpoints
        //app.get(fullPath, ctx -> handleGet(ctx, datasetGraph));
        //app.post(fullPath, ctx -> handlePost(fullPath, ctx, datasetGraph));
        //app.put(fullPath + "/{uri}", ctx -> handlePutInstance(fullPath, ctx, datasetGraph));
        //app.delete(fullPath + "/{uri}", ctx -> handleDeleteInstance(ctx, datasetGraph));
    }
    
    private void handleGetDocumentation(Context ctx) {
        ctx.json(RenderOptions.toJSON().toString(4));
    }

    private void handleGetInstance(Context ctx, Rendering startRendering, SqliteAticDatasetGraph datasetGraph) {

        try {
            RenderOptions options = RenderOptions.loadFromContext(ctx);

            InvocationContext ictx = AticServer.fromJavalinContext(ctx);
            String uri = ctx.pathParam("uri");
            Node node = NodeFactory.createURI(uri);

            AticGraph graph = datasetGraph.calculateRead(() -> {
                return datasetGraph.getUnionGraph(options.getGraphs().iterator(), ictx);
            });

            //check existance
            boolean exists = datasetGraph.calculateRead(() -> {
                return graph.contains(node, Node.ANY, Node.ANY, ictx)
                        || graph.contains(Node.ANY, Node.ANY, node, ictx);
            });
            if (!exists) {
                ctx.status(HttpStatus.NOT_FOUND).result("Not found: " + uri);
                return;
            }

            JSONObject context = JSONUtils.createJSONObject();

            JSONObject result = datasetGraph.calculateRead(() -> {
                return render(node, graph, ictx, startRendering, 0, options, context);
            });

            result.put("@context", context);

            if (PRINT) {
                System.out.println("check json-ld before parse");
                System.out.println(result.toString(options.getIndent()));
            }

            //check
            Model model = ModelFactory.createDefaultModel();
            RDFParser.fromString(result.toString(options.getIndent()), Lang.JSONLD11)
                    .lang(Lang.JSONLD)
                    .parse(model);

            if (PRINT) {
                System.out.println("check NT after parse");
                model.write(System.out, "NT");
            }

            ctx.contentType("application/ld+json");
            ctx.result(result.toString(options.getIndent()));

        } catch (Exception e) {
            e.printStackTrace();
            ctx.status(500).result("Error: " + e.getMessage());
        }

    }

    private JSONObject render(Node node, AticGraph graph, InvocationContext ictx, Rendering rendering, int depth, RenderOptions options, JSONObject context) {
        JSONObject result = JSONUtils.createJSONObject();

        //resource:
        //first how it is identified
        //- the uri
        if (!node.isBlank()) {
            result.put(options.getUriKey(), node.getURI());
            context.put(options.getUriKey(), "@id");
        }
        if (rendering == Rendering.RESOURCE) {
            return result;
        }

        //fragment ============================================
        Property[] selectedLabelAndCommentProperty = renderLabelAndComment(node, graph, ictx, options, context, result);

        //then what it is
        //- one primary type & other types
        Property[] selectedTypeProperties = renderTypes(node, graph, ictx, rendering, depth, options, context, result);

        //maybe how it is depicted
        //- icon
        Property[] selectedIconProperties = renderIcon(node, graph, ictx, options, context, result);

        if (rendering == Rendering.FRAGMENT) {
            return result;
        }

        //molecule =========================================
        //Label, Comment, Type, Icon selected properties from fragment
        //avoid listing them up again in SPL,SPO
        Property[] lcti = new Property[]{
            selectedLabelAndCommentProperty[0],
            selectedLabelAndCommentProperty[1],
            selectedTypeProperties[0],
            selectedIconProperties[0]
        };

        //- outgoing edges spl
        renderOutgoingSPL(node, graph, ictx, rendering, depth, lcti, options, context, result);

        //- outgoing edges spo
        renderOutgoingSPO(node, graph, ictx, rendering, depth, lcti, options, context, result);

        //- incoming edges spo (@reverse)
        renderIncomingSPO(node, graph, ictx, rendering, depth, lcti, options, context, result);

        return result;
    }

    private Property[] renderLabelAndComment(Node node, AticGraph graph, InvocationContext ictx, RenderOptions options, JSONObject context, JSONObject result) {
        Property[] selectedProperties = new Property[]{null, null};

        //first humans read label and comment
        //- label & comment
        List<Property> propertyGroups[] = new List[]{
            labelProperties,
            commentProperties
        };
        String[] jsonKeys = new String[]{
            options.getLabelKey(),
            options.getCommentKey()
        };
        
            for (int i = 0; i < propertyGroups.length; i++) {

                List<Property> properties = propertyGroups[i];
                String jsonKey = jsonKeys[i];

                for (Property p : properties) {

                    if (skipProperty(p, options)) {
                        continue;
                    }

                    ExtendedIterator<Triple> it
                            = graph.find(node, p.asNode(), Node.ANY, ictx);

                    Node best = selectBestLiteral(it, options.getLocale());
                    
                    it.close();

                    if (best != null) {
                        //TODO there can be a clash because "label" is rdfs:label but in another object foaf:name
                        context.put(jsonKey, p.getURI());
                        result.put(jsonKey, best.getLiteralLexicalForm());

                        selectedProperties[i] = p;
                        break;
                    }
                }
            }

        return selectedProperties;
    }

    private Property[] renderTypes(Node node, AticGraph graph, InvocationContext ictx, Rendering rendering, int depth, RenderOptions options, JSONObject context, JSONObject result) {
        Property[] selectedProperties = new Property[]{null};

        String key = options.getTypeKey();

            for (Property p : typeProperties) {

                if (skipProperty(p, options)) {
                    continue;
                }

                ExtendedIterator<Triple> typeIter = graph.find(node, p.asNode(), Node.ANY, ictx);
                if (!typeIter.hasNext()) {
                    continue;
                }

                Node primaryType = null;
                JSONArray otherTypes = new JSONArray();
                while (typeIter.hasNext()) {
                    Node t = typeIter.next().getObject();

                    //TODO what a primary type is needs to be decided here, for now it is just the first one
                    if (primaryType == null) {
                        primaryType = t;
                    } else {
                        otherTypes.put(t.getURI());
                    }
                }
                typeIter.close();
                if (primaryType != null) {
                    if (options.expandType && depth == 0) {
                        context.put(key, p.getURI());
                        JSONObject type = render(primaryType, graph, ictx, Rendering.FRAGMENT, depth + 1, options, context);
                        result.put(key, type);
                    } else {
                        context.put(key + "Uri", "@type");
                        result.put(key + "Uri", primaryType.getURI());
                    }
                }

                if (!otherTypes.isEmpty() && !options.isSingleType()) {
                    if (options.expandType && depth == 0) {

                        JSONArray expandedTypes = new JSONArray();

                        for (int i = 0; i < otherTypes.length(); i++) {

                            String uri = otherTypes.getString(i);
                            Node typeNode = NodeFactory.createURI(uri);

                            //expand type if option is set
                            Rendering typeRendering = Rendering.FRAGMENT;
                            if (options.expandProperties.contains(p.asNode()) && depth == 0) {
                                typeRendering = Rendering.MOLECULE;
                            }

                            JSONObject typeObj = render(
                                    typeNode,
                                    graph,
                                    ictx,
                                    typeRendering,
                                    depth + 1,
                                    options,
                                    context
                            );

                            expandedTypes.put(typeObj);
                        }

                        JSONObject typesDefinition = new JSONObject();
                        typesDefinition.put("@id", p.getURI());
                        typesDefinition.put("@container", "@set");
                        context.put(key + "s", typesDefinition);

                        result.put(key + "s", expandedTypes);

                    } else {
                        JSONObject typesDefinition = new JSONObject();
                        typesDefinition.put("@id", p.getURI());
                        typesDefinition.put("@container", "@set");
                        typesDefinition.put("@type", "@id");
                        context.put(key + "Uris", typesDefinition);

                        result.put(key + "Uris", otherTypes);
                    }
                }

                selectedProperties[0] = p;
                break;
            }

        return selectedProperties;
    }

    private Property[] renderIcon(Node node, AticGraph graph, InvocationContext ictx, RenderOptions options, JSONObject context, JSONObject result) {
        Property[] selectedProperties = new Property[]{null};

            for (Property p : iconProperties) {

                if (skipProperty(p, options)) {
                    continue;
                }

                ExtendedIterator<Triple> it = graph.find(node, p.asNode(), Node.ANY, ictx);

                if (it.hasNext()) {
                    Node obj = it.next().getObject();

                    if (obj.isURI()) {
                        //TODO there could be an option let download the image from the link and creates a data:image/png;base64,...
                        context.put(options.getIconKey(), p.getURI());
                        result.put(options.getIconKey(), obj.getURI());

                        selectedProperties[0] = p;
                        break;
                    }
                }
                
                it.close();
            }

        return selectedProperties;
    }

    private void renderOutgoingSPL(Node node, AticGraph graph, InvocationContext ictx, Rendering rendering, int depth, Property[] lcti, RenderOptions options, JSONObject context, JSONObject result) {
        Set<Node> fragmentProperties = Arrays.stream(lcti)
                .filter(Objects::nonNull)
                .map(Property::asNode)
                .collect(Collectors.toSet());

        SqliteAticGraph aticGraph = (SqliteAticGraph) graph;

        ExtendedIterator<Triple> iter = aticGraph.findSPL(node, Node.ANY, Node.ANY, options.getSplLimit(), options.getSplOffset(), false, null, ictx);

        if (!iter.hasNext()) {
            return;
        }

        Map<String, JSONArray> predicateMap = new HashMap<>();

        while (iter.hasNext()) {

            Triple t = iter.next();

            Node p = t.getPredicate();
            Node o = t.getObject();

            if (!p.isURI()) {
                continue;
            }

            if (skipProperty(p, options)) {
                continue;
            }

            if (fragmentProperties.contains(p)) {
                continue;
            }

            if (!o.isLiteral()) {
                continue;
            }

            String uri = p.getURI();
            String localName = p.getLocalName();

            //not allowed to overwrite previous created keys
            if (result.has(localName)) {
                continue;
            }

            //TODO there could be a clash of localName
            context.put(localName, uri);

            JSONArray arr = predicateMap.computeIfAbsent(localName, k -> new JSONArray());

            /*
            if (reduceProperty(p, options)) {
                arr.put(StringUtils.abbreviate(o.getLiteralLexicalForm(), options.getAbbrevMaxWidth()));
            } else {
                arr.put(o.getLiteralLexicalForm());
            }
             */
            renderLiteralToArray(p, o.getLiteral(), arr, options, localName, context);

            if (options.getSingleValueProperties().contains(p)) {
                break;
            }
        }

        if (predicateMap.isEmpty()) {
            iter.close();
            return;
        }

        //TODO here we could order the keys as given in the options
        List<String> predicateList = new ArrayList<>(predicateMap.keySet());
        predicateList.sort(new NaturalComparator());
        for (String predicate : predicateList) {
            JSONArray array = predicateMap.get(predicate);
            if (options.isUnwrapSingle() && array.length() == 1) {
                result.put(predicate, array.get(0));
            } else {
                result.put(predicate, predicateMap.get(predicate));
            }
        }

        //has more
        boolean hasMore = false;
        if (iter instanceof PagedTripleIterator) {
            PagedTripleIterator pti = (PagedTripleIterator) iter;
            hasMore = pti.hasMore();
        }
        context.put("moreSPL", MORE_SPL_URI);
        result.put("moreSPL", hasMore);
        
        iter.close();
    }

    private void renderOutgoingSPO(Node node, AticGraph graph, InvocationContext ictx, Rendering rendering, int depth, Property[] lcti, RenderOptions options, JSONObject context, JSONObject result) {
        Set<Node> fragmentProperties = Arrays.stream(lcti)
                .filter(Objects::nonNull)
                .map(Property::asNode)
                .collect(Collectors.toSet());

        SqliteAticGraph aticGraph = (SqliteAticGraph) graph;

        ExtendedIterator<Triple> iter = aticGraph.findSPO(node, Node.ANY, Node.ANY, options.getSpoOutgoingLimit(), options.getSpoOutgoingOffset(), false, null, ictx);

        if (!iter.hasNext()) {
            return;
        }

        Map<String, JSONArray> predicateMap = new HashMap<>();

        while (iter.hasNext()) {

            Triple t = iter.next();

            Node p = t.getPredicate();
            Node o = t.getObject();

            if (!p.isURI()) {
                continue;
            }

            if (skipProperty(p, options)) {
                continue;
            }

            if (fragmentProperties.contains(p)) {
                continue;
            }

            String uri = p.getURI();
            String localName = p.getLocalName();

            //not allowed to overwrite previous created keys (safety net)
            if (result.has(localName)) {
                continue;
            }

            //TODO there could be a clash of localName
            context.put(localName, uri);

            JSONArray arr = predicateMap.computeIfAbsent(localName, k -> new JSONArray());

            if (reduceProperty(p, options)) {
                arr.put(o.isBlank() ? o.getBlankNodeLabel() : o.getURI());
            } else if (options.expandProperties.contains(p) && depth == 0) {
                //explicit expand, but only on depth=0 to avoid recursion
                arr.put(render(o, graph, ictx, Rendering.MOLECULE, depth + 1, options, context));
            } else {
                //normal case: fragment
                arr.put(render(o, graph, ictx, Rendering.FRAGMENT, depth + 1, options, context));
            }

            if (options.getSingleValueProperties().contains(p)) {
                break;
            }
        }

        if (predicateMap.isEmpty()) {
            iter.close();
            return;
        }

        //TODO here we could order the keys as given in the options
        List<String> predicateList = new ArrayList<>(predicateMap.keySet());
        predicateList.sort(new NaturalComparator());
        for (String predicate : predicateList) {
            JSONArray array = predicateMap.get(predicate);
            if (options.isUnwrapSingle() && array.length() == 1) {
                result.put(predicate, array.get(0));
            } else {
                result.put(predicate, predicateMap.get(predicate));
            }
        }

        //has more
        boolean hasMore = false;
        if (iter instanceof PagedTripleIterator) {
            PagedTripleIterator pti = (PagedTripleIterator) iter;
            hasMore = pti.hasMore();
        }
        context.put("moreSPO", MORE_SPO_URI);
        result.put("moreSPO", hasMore);
        
        iter.close();
    }

    private void renderIncomingSPO(Node node, AticGraph graph, InvocationContext ictx, Rendering rendering, int depth, Property[] lcti, RenderOptions options, JSONObject context, JSONObject result) {
        SqliteAticGraph aticGraph = (SqliteAticGraph) graph;

        ExtendedIterator<Triple> iter = aticGraph.findSPO(Node.ANY, Node.ANY, node, options.getSpoIncomingLimit(), options.getSpoIncomingOffset(), false, null, ictx);

        if (!iter.hasNext()) {
            return;
        }

        Map<String, JSONArray> predicateMap = new HashMap<>();

        while (iter.hasNext()) {

            Triple t = iter.next();

            Node s = t.getSubject();
            Node p = t.getPredicate();

            if (!p.isURI()) {
                continue;
            }

            if (skipProperty(p, options)) {
                continue;
            }

            String uri = p.getURI();
            String localName = p.getLocalName();

            //TODO there could be a clash of localName
            context.put(localName, uri);

            JSONArray arr = predicateMap.computeIfAbsent(localName, k -> new JSONArray());

            if (reduceProperty(p, options)) {
                //Inside @reverse, the values must be node objects, not plain strings.
                arr.put(render(s, graph, ictx, Rendering.RESOURCE, depth + 1, options, context));
            } else if (options.expandProperties.contains(p) && depth == 0) {
                //explicit expand, but only on depth=0 to avoid recursion
                arr.put(render(s, graph, ictx, Rendering.MOLECULE, depth + 1, options, context));
            } else {
                //normal case: fragment
                arr.put(render(s, graph, ictx, Rendering.FRAGMENT, depth + 1, options, context));
            }

            if (options.getSingleValueProperties().contains(p)) {
                break;
            }
        }

        if (predicateMap.isEmpty()) {
            iter.close();
            return;
        }

        JSONObject reverse = new JSONObject();

        //TODO here we could order the keys as given in the options
        List<String> predicateList = new ArrayList<>(predicateMap.keySet());
        predicateList.sort(new NaturalComparator());
        for (String predicate : predicateList) {
            JSONArray array = predicateMap.get(predicate);
            if (options.isUnwrapSingle() && array.length() == 1) {
                reverse.put(predicate, array.get(0));
            } else {
                reverse.put(predicate, predicateMap.get(predicate));
            }
        }

        result.put("@reverse", reverse);

        //has more
        boolean hasMore = false;
        if (iter instanceof PagedTripleIterator) {
            PagedTripleIterator pti = (PagedTripleIterator) iter;
            hasMore = pti.hasMore();
        }
        context.put("moreOPS", MORE_OPS_URI);
        result.put("moreOPS", hasMore);
    }

    private void renderLiteralToArray(Node p, LiteralLabel lit, JSONArray arr, RenderOptions options, String localName, JSONObject context) {

        Object value;

        // -------------------------
        // Native value (Java types)
        // -------------------------
        if (options.isNativeValue()) {
            value = lit.getValue();
        } else {
            value = lit.getLexicalForm();
        }

        //langstring not necessary to mention, would be set if @language is used
        if (!lit.getDatatypeURI().equals(RDF.langString.getURI())) {
            JSONObject literalContext = new JSONObject();
            literalContext.put("@id", p.getURI());
            //we assume that datatype is stable
            if (lit.getDatatypeURI() != null) {
                literalContext.put("@type", lit.getDatatypeURI());
            }
            //we should not use @language because this might change for a fixed predicate
            context.put(localName, literalContext);
        }

        //reduction if too long string
        if (reduceProperty(p, options) && value instanceof String) {
            value = StringUtils.abbreviate((String) value, options.getAbbrevMaxWidth());
        }

        // -------------------------
        // JSON-LD object wrapping?
        // -------------------------
        boolean hasLang = options.isLanguageTag() && !lit.language().isBlank();
        boolean hasDatatype = options.isDatatype() && lit.getDatatypeURI() != null;

        //not native but lang or datatype
        if (!options.isNativeValue() && (hasLang || hasDatatype)) {

            JSONObject obj = new JSONObject();

            obj.put("@value", value);

            if (hasLang) {
                obj.put("@language", lit.language());
            }

            // only include datatype if no language (JSON-LD rule)
            if (hasDatatype && !hasLang) {
                obj.put("@type", lit.getDatatypeURI());
            }

            arr.put(obj);

        } else {
            // plain value (compact JSON-LD)
            arr.put(value);
        }
    }

    private static class RenderOptions {

        private String uriKey = "uri";
        private String labelKey = "label";
        private String commentKey = "comment";
        private String iconKey = "icon";
        private String typeKey = "type";

        private int indent = 4;
        private Locale locale = Locale.ENGLISH;

        private int spoOutgoingPage = 0;
        private int spoOutgoingPageSize = 5; //50

        private int spoIncomingPage = 0;
        private int spoIncomingPageSize = 5;

        private int splPage = 0;
        private int splPageSize = 5;

        private List<Node> includeProperties = new ArrayList<>();
        private List<Node> excludeProperties = new ArrayList<>();

        private List<Node> expandProperties = new ArrayList<>();
        private List<Node> reduceProperties = new ArrayList<>();

        private List<Node> singleValueProperties = new ArrayList<>();

        private boolean excludeAll = false;
        private boolean reduceAll = false;

        private boolean unwrapSingle = false;

        private int abbrevMaxWidth = 100;

        private boolean expandType = true;
        private boolean singleType = false;

        //literals
        private boolean languageTag = false;
        private boolean datatype = false;
        private boolean nativeValue = true;

        private List<Node> graphs = List.of(Quad.defaultGraphIRI);

        // ================= getters =================
        public List<Node> getSingleValueProperties() {
            return singleValueProperties;
        }

        public boolean isUnwrapSingle() {
            return unwrapSingle;
        }

        public String getUriKey() {
            return uriKey;
        }

        public String getLabelKey() {
            return labelKey;
        }

        public String getCommentKey() {
            return commentKey;
        }

        public String getIconKey() {
            return iconKey;
        }

        public String getTypeKey() {
            return typeKey;
        }

        public boolean isExpandType() {
            return expandType;
        }

        public boolean isSingleType() {
            return singleType;
        }

        public List<Node> getExpandProperties() {
            return expandProperties;
        }

        public List<Node> getReduceProperties() {
            return reduceProperties;
        }

        public boolean isExcludeAll() {
            return excludeAll;
        }

        public boolean isReduceAll() {
            return reduceAll;
        }

        public int getIndent() {
            return indent;
        }

        public Locale getLocale() {
            return locale;
        }

        public int getSpoOutgoingPage() {
            return spoOutgoingPage;
        }

        public int getSpoOutgoingPageSize() {
            return spoOutgoingPageSize;
        }

        public int getSpoOutgoingLimit() {
            return spoOutgoingPageSize;
        }

        public int getSpoOutgoingOffset() {
            return Math.max(0, spoOutgoingPage - 1) * spoOutgoingPageSize;
        }

        public int getSpoIncomingPage() {
            return spoIncomingPage;
        }

        public int getSpoIncomingPageSize() {
            return spoIncomingPageSize;
        }

        public int getSpoIncomingLimit() {
            return spoIncomingPageSize;
        }

        public int getSpoIncomingOffset() {
            return Math.max(0, spoIncomingPage - 1) * spoIncomingPageSize;
        }

        public int getSplPage() {
            return splPage;
        }

        public int getSplPageSize() {
            return splPageSize;
        }

        public int getSplLimit() {
            return splPageSize;
        }

        public int getSplOffset() {
            return Math.max(0, splPage - 1) * splPageSize;
        }

        public List<Node> getIncludeProperties() {
            return includeProperties;
        }

        public List<Node> getExcludeProperties() {
            return excludeProperties;
        }

        public List<Node> getGraphs() {
            return graphs;
        }

        public int getAbbrevMaxWidth() {
            return abbrevMaxWidth;
        }

        public boolean isLanguageTag() {
            return languageTag;
        }

        public boolean isDatatype() {
            return datatype;
        }

        public boolean isNativeValue() {
            return nativeValue;
        }

        // (existing getters unchanged...)
        // =========================================================
        // loader
        // =========================================================
        public static RenderOptions loadFromContext(Context ctx) {

            RenderOptions o = new RenderOptions();

            String uriNameParam = ctx.queryParam("uri-key");
            if (uriNameParam != null && !uriNameParam.isBlank()) {
                o.uriKey = uriNameParam;
            }

            String labelNameParam = ctx.queryParam("label-key");
            if (labelNameParam != null && !labelNameParam.isBlank()) {
                o.labelKey = labelNameParam;
            }

            String commentNameParam = ctx.queryParam("comment-key");
            if (commentNameParam != null && !commentNameParam.isBlank()) {
                o.commentKey = commentNameParam;
            }

            String iconNameParam = ctx.queryParam("icon-key");
            if (iconNameParam != null && !iconNameParam.isBlank()) {
                o.iconKey = iconNameParam;
            }

            String typeNameParam = ctx.queryParam("type-key");
            if (typeNameParam != null && !typeNameParam.isBlank()) {
                o.typeKey = typeNameParam;
            }

            // -------------------------
            // indent
            // -------------------------
            o.indent = parseInt(ctx.queryParam("indent"), o.indent);

            // -------------------------
            // locale
            // -------------------------
            String localeParam = ctx.queryParam("locale");
            if (localeParam != null && !localeParam.isBlank()) {
                Locale l = Locale.forLanguageTag(localeParam);
                if (!l.getLanguage().isBlank()) {
                    o.locale = l;
                }
            }

            // -------------------------
            // pagination
            // -------------------------
            o.spoOutgoingPage = parseInt(ctx.queryParam("spo-page"), 1);
            o.spoOutgoingPageSize = parseInt(ctx.queryParam("spo-page-size"), o.spoOutgoingPageSize);

            o.spoIncomingPage = parseInt(ctx.queryParam("ops-page"), 1);
            o.spoIncomingPageSize = parseInt(ctx.queryParam("ops-page-size"), o.spoIncomingPageSize);

            o.splPage = parseInt(ctx.queryParam("spl-page"), 1);
            o.splPageSize = parseInt(ctx.queryParam("spl-page-size"), o.splPageSize);

            // -------------------------
            // expand-type (boolean)
            // -------------------------
            String expandTypeParam = ctx.queryParam("expand-type");
            if (expandTypeParam != null) {
                o.expandType = expandTypeParam.isEmpty() || Boolean.parseBoolean(expandTypeParam);
            }

            // -------------------------
            // single-type (boolean)
            // -------------------------
            String singleTypeParam = ctx.queryParam("single-type");
            if (singleTypeParam != null) {
                o.singleType = singleTypeParam.isEmpty() || Boolean.parseBoolean(singleTypeParam);
            }

            List<String> singleValue = ctx.queryParams("single-value");
            if (singleValue != null && !singleValue.isEmpty()) {
                o.singleValueProperties = singleValue.stream()
                        .filter(s -> s != null && !s.isBlank())
                        .map(NodeFactory::createURI)
                        .toList();
            }

            String unwrapSingleParam = ctx.queryParam("unwrap-single");
            if (unwrapSingleParam != null) {
                o.unwrapSingle = unwrapSingleParam.isEmpty() || Boolean.parseBoolean(unwrapSingleParam);
            }

            //literals
            String langParam = ctx.queryParam("lang");
            if (langParam != null) {
                o.languageTag = langParam.isEmpty() || Boolean.parseBoolean(langParam);
            }

            String datatypeParam = ctx.queryParam("datatype");
            if (datatypeParam != null) {
                o.datatype = datatypeParam.isEmpty() || Boolean.parseBoolean(datatypeParam);
            }

            String nativeValueParam = ctx.queryParam("native-value");
            if (nativeValueParam != null) {
                o.nativeValue = nativeValueParam.isEmpty() || Boolean.parseBoolean(nativeValueParam);
            }

            // -------------------------
            // include
            // -------------------------
            List<String> include = ctx.queryParams("include");
            if (include != null && !include.isEmpty()) {
                o.includeProperties = include.stream()
                        .filter(s -> s != null && !s.isBlank())
                        .map(NodeFactory::createURI)
                        .toList();
            }

            // -------------------------
            // exclude
            // -------------------------
            List<String> exclude = ctx.queryParams("exclude");
            if (exclude != null && !exclude.isEmpty()) {

                if (exclude.stream().anyMatch(s -> "*".equals(s))) {
                    o.excludeAll = true;
                } else {
                    o.excludeProperties = exclude.stream()
                            .filter(s -> s != null && !s.isBlank())
                            .map(NodeFactory::createURI)
                            .toList();
                }
            }

            // -------------------------
            // expand
            // -------------------------
            List<String> expand = ctx.queryParams("expand");
            if (expand != null && !expand.isEmpty()) {
                o.expandProperties = expand.stream()
                        .filter(s -> s != null && !s.isBlank())
                        .map(NodeFactory::createURI)
                        .toList();
            }

            // -------------------------
            // reduce
            // -------------------------
            List<String> reduce = ctx.queryParams("reduce");
            if (reduce != null && !reduce.isEmpty()) {

                if (reduce.stream().anyMatch(s -> "*".equals(s))) {
                    o.reduceAll = true;
                } else {
                    o.reduceProperties = reduce.stream()
                            .filter(s -> s != null && !s.isBlank())
                            .map(NodeFactory::createURI)
                            .toList();
                }
            }

            // -------------------------
            // graph
            // -------------------------
            List<String> graphParams = ctx.queryParams("graph");
            if (graphParams != null && !graphParams.isEmpty()) {
                List<Node> g = graphParams.stream()
                        .filter(s -> s != null && !s.isBlank())
                        .map(NodeFactory::createURI)
                        .toList();

                if (!g.isEmpty()) {
                    o.graphs = g;
                }
            }

            o.abbrevMaxWidth = parseInt(ctx.queryParam("max-width"), o.abbrevMaxWidth);

            return o;
        }

        // -------------------------
        // helper
        // -------------------------
        private static int parseInt(String v, int def) {
            if (v == null) {
                return def;
            }
            try {
                return Integer.parseInt(v);
            } catch (Exception e) {
                return def;
            }
        }

        public static JSONObject toJSON() {

            JSONObject root = JSONUtils.createJSONObject();
            JSONArray params = new JSONArray();

            Consumer<Object[]> add = arr -> {
                JSONObject o = JSONUtils.createJSONObject();
                o.put("name", arr[0]);

                // optional default value
                if (arr.length > 2 && arr[1] != null && !(arr[1] instanceof String && ((String) arr[1]).isEmpty())) {
                    o.put("default", arr[1]);
                }

                o.put("description", arr.length == 3 ? arr[2] : arr[1]);
                params.put(o);
            };

            // =========================
            // keys
            // =========================
            add.accept(new Object[]{"uri-key", "uri", "Key used for URI field in response JSON"});
            add.accept(new Object[]{"label-key", "label", "Key used for label field in response JSON"});
            add.accept(new Object[]{"comment-key", "comment", "Key used for comment field in response JSON"});
            add.accept(new Object[]{"icon-key", "icon", "Key used for icon field in response JSON"});
            add.accept(new Object[]{"type-key", "type", "Key used for type field in response JSON"});

            // =========================
            // formatting
            // =========================
            add.accept(new Object[]{"indent", 4, "JSON indentation level"});
            add.accept(new Object[]{"locale", "en", "Locale for localization (BCP47 format)"});

            // =========================
            // pagination
            // =========================
            add.accept(new Object[]{"spo-page", 1, "Outgoing SPO page number"});
            add.accept(new Object[]{"spo-page-size", 5, "Outgoing SPO page size"});
            add.accept(new Object[]{"spl-page", 1, "SPL page number"});
            add.accept(new Object[]{"spl-page-size", 5, "SPL page size"});
            add.accept(new Object[]{"ops-page", 1, "Incoming SPO page number"});
            add.accept(new Object[]{"ops-page-size", 5, "Incoming SPO page size"});

            // =========================
            // booleans
            // =========================
            add.accept(new Object[]{"expand-type", true, "Expand type information"});
            add.accept(new Object[]{"single-type", false, "Only show one type information"});
            add.accept(new Object[]{"unwrap-single", false, "Unwrap single-value properties"});
            add.accept(new Object[]{"lang", false, "Include language tags for literals. native-value needs to be false."});
            add.accept(new Object[]{"datatype", false, "Include datatype IRIs for literals. native-value needs to be false."});
            add.accept(new Object[]{"native-value", true, "Include native literal value. No datatype and language is added."});

            // =========================
            // properties
            // =========================
            add.accept(new Object[]{"include", "Include specified properties (repeatable)"});
            add.accept(new Object[]{"exclude", "Exclude specified properties (* = all, repeatable)"});
            add.accept(new Object[]{"expand", "Expand property objects to molecules (repeatable)"});
            add.accept(new Object[]{"reduce", "Reduce property objects (* = all, repeatable)"});
            add.accept(new Object[]{"single-value", "Mark property as single-valued (repeatable)"});

            // =========================
            // graph
            // =========================
            add.accept(new Object[]{"graph", "Query to specific named graphs (repeatable)"});

            // =========================
            // misc
            // =========================
            add.accept(new Object[]{"max-width", 100, "Maximum abbreviation width for string values"});

            root.put("parameters", params);

            return root;
        }
    }

    private static enum Rendering {

        RESOURCE(1, "urn:atic:resource", "resource"),
        FRAGMENT(2, "urn:atic:fragment", "fragment"),
        MOLECULE(3, "urn:atic:molecule", "molecule");

        private final int code;
        private final String uri;
        private final Node node;
        private final String name;

        Rendering(int code, String uri, String name) {
            this.code = code;
            this.uri = uri;
            this.node = NodeFactory.createURI(uri);
            this.name = name;
        }

        /**
         * The numeric storage code (1=read, 2=edit, 3=admin)
         */
        public int getCode() {
            return code;
        }

        public String getUri() {
            return uri;
        }

        public Node asNode() {
            return node;
        }

        public static Rendering fromName(String name) {
            for (Rendering rend : values()) {
                if (rend.name.equals(name)) {
                    return rend;
                }
            }
            return null;
        }
    }

    private static Node selectBestLiteral(ExtendedIterator<Triple> it, Locale locale) {

        Node fallbackAny = null;
        Node fallbackEn = null;
        String preferredLang = locale.getLanguage();

        while (it.hasNext()) {
            Node obj = it.next().getObject();

            if (!obj.isLiteral()) {
                continue;
            }

            String lang = obj.getLiteralLanguage();

            // 1. exact locale match
            if (lang != null && lang.equalsIgnoreCase(preferredLang)) {
                return obj;
            }

            // 2. remember english fallback
            if (lang != null && lang.equalsIgnoreCase("en")) {
                if (fallbackEn == null) {
                    fallbackEn = obj;
                }
            }

            // 3. remember first literal
            if (fallbackAny == null) {
                fallbackAny = obj;
            }
        }

        if (fallbackEn != null) {
            return fallbackEn;
        }
        return fallbackAny;
    }

    private boolean skipProperty(Node p, RenderOptions options) {
        return options.getExcludeProperties().contains(p)
                || (options.isExcludeAll() && !options.getIncludeProperties().contains(p));

    }

    private boolean skipProperty(Property p, RenderOptions options) {
        return skipProperty(p.asNode(), options);
    }

    private boolean reduceProperty(Node p, RenderOptions options) {
        return options.getReduceProperties().contains(p)
                || (options.isReduceAll() && !options.getExpandProperties().contains(p));
    }

    //-------------------------------------
    //maybe later
    private void handleGet(Context ctx, SqliteAticDatasetGraph datasetGraph) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    private void handlePost(String path, Context ctx, SqliteAticDatasetGraph datasetGraph) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    private void handlePutInstance(String path, Context ctx, SqliteAticDatasetGraph datasetGraph) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    private void handleDeleteInstance(Context ctx, SqliteAticDatasetGraph datasetGraph) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    //-----------------------------------------
    public List<Property> getTypeProperties() {
        return typeProperties;
    }

    public List<Property> getIconProperties() {
        return iconProperties;
    }

    public List<Property> getLabelProperties() {
        return labelProperties;
    }

    public List<Property> getCommentProperties() {
        return commentProperties;
    }

}
