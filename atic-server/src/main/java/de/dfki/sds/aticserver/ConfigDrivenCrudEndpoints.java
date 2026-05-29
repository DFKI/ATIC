package de.dfki.sds.aticserver;

import com.apicatalog.jsonld.JsonLd;
import com.apicatalog.jsonld.JsonLdError;
import com.apicatalog.jsonld.JsonLdOptions;
import com.apicatalog.jsonld.document.JsonDocument;
import com.apicatalog.jsonld.serialization.QuadsToJsonld;
import com.apicatalog.rdf.api.RdfConsumerException;
import de.dfki.sds.atic.ac.PermissionDeniedException;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import de.dfki.sds.aticsqlite.SqliteAticGraph;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.function.Consumer;
import static java.util.stream.Collectors.toList;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.TxnType;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.changes.RDFChangesCollector;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.system.Txn;
import org.apache.jena.update.UpdateAction;
import org.apache.jena.update.UpdateFactory;
import org.apache.jena.update.UpdateRequest;
import org.apache.jena.vocabulary.RDF;
import org.yaml.snakeyaml.Yaml;

/**
 * Configuration-Driven CRUD Endpoints (CDCE).
 */
public class ConfigDrivenCrudEndpoints {

    private final Yaml yaml;
    private Map<String, Object> config;
    
    //also page size
    private int globalDefaultLimit = 50;

    public ConfigDrivenCrudEndpoints(String resourcePath) {
        this.yaml = new Yaml();
        loadFromResource(resourcePath);
    }

    public ConfigDrivenCrudEndpoints(File file) {
        this.yaml = new Yaml();
        loadFromFile(file);
    }

    private void loadFromResource(String resourcePath) {
        try (InputStream is = ConfigDrivenCrudEndpoints.class
                .getResourceAsStream(resourcePath)) {

            if (is == null) {
                throw new RuntimeException("Resource not found: " + resourcePath);
            }

            config = yaml.load(is); // just trigger parsing

        } catch (Exception e) {
            throw new RuntimeException("Failed to load YAML from resource", e);
        }
    }

    private void loadFromFile(File file) {
        try (InputStream is = new FileInputStream(file)) {
            config = yaml.load(is); // just trigger parsing
        } catch (Exception e) {
            throw new RuntimeException("Failed to load YAML from file", e);
        }
    }

    public void register(Javalin app, String path, SqliteAticDatasetGraph datasetGraph) {

        //allow an additional path so it can be registered under e.g. "/api"
        String fullPath = path + (String) config.get("path");
        Map<String, Object> endpoints = (Map<String, Object>) config.get("endpoints");

        for (Map.Entry<String, Object> entry : endpoints.entrySet()) {
            String type = entry.getKey();
            Map<String, Object> endpointConfig = (Map<String, Object>) entry.getValue();

            switch (type) {
                case "get" ->
                    app.get(fullPath, ctx -> handleGet(ctx, datasetGraph, endpointConfig));

                case "getInstance" ->
                    app.get(fullPath + "/{uri}", ctx -> handleGetInstance(ctx, datasetGraph, endpointConfig));

                case "post" ->
                    app.post(fullPath, ctx -> handlePost(fullPath, ctx, datasetGraph, endpointConfig));

                case "putInstance" -> {
                    //reuse the getInstance config and put it into the putInstance config
                    Map<String, Object> getInstance = (Map<String, Object>) endpoints.get("getInstance");
                    endpointConfig.put("getInstance", getInstance);

                    app.put(fullPath + "/{uri}", ctx -> handlePutInstance(fullPath, ctx, datasetGraph, endpointConfig));
                }

                case "deleteInstance" ->
                    app.delete(fullPath + "/{uri}", ctx -> handleDeleteInstance(ctx, datasetGraph, endpointConfig));
            }
        }
    }

    private Model getModel(
            Context ctx,
            SqliteAticDatasetGraph datasetGraph,
            Map<String, Object> cfg,
            Consumer<ParameterizedSparqlString> pssConsumer) {

        String sparql = (String) cfg.get("sparql");

        int defaultLimit = (int) cfg.getOrDefault("defaultLimit", globalDefaultLimit);
        int defaultPageSize = (int) cfg.getOrDefault("defaultPageSize", defaultLimit);

        // ---- Read query params ----
        Integer page = ctx.queryParamAsClass("page", Integer.class).allowNullable().get();
        Integer pageSize = ctx.queryParamAsClass("page-size", Integer.class).allowNullable().get();
        Integer offset = ctx.queryParamAsClass("offset", Integer.class).allowNullable().get();
        Integer limit = ctx.queryParamAsClass("limit", Integer.class).allowNullable().get();

        // ---- Normalize to offset + limit ----
        if (page != null) {
            int size = pageSize != null ? pageSize : defaultPageSize;
            limit = size;
            offset = (page - 1) * size;
        } else {
            if (limit == null) {
                limit = defaultLimit;
            }
            if (offset == null) {
                offset = 0;
            }
        }

        ParameterizedSparqlString pss = new ParameterizedSparqlString(sparql);

        if (pssConsumer != null) {
            pssConsumer.accept(pss);
        }

        // inject pagination parameters
        pss.setLiteral("limit", limit);
        pss.setLiteral("offset", offset);

        Query queryObj = QueryFactory.create(pss.toString());

        Dataset dataset = DatasetFactory.wrap(datasetGraph);
        AticServer.transferContext(ctx, dataset.getContext());
        
        //select graph
        String graphUri = ctx.queryParam("graph");
        if(graphUri == null) {
            graphUri = Quad.defaultGraphIRI.getURI();
        }

        datasetGraph.begin(TxnType.READ);
        Model model = dataset.getNamedModel(graphUri);
        try (QueryExecution qExec = QueryExecutionFactory.create(queryObj, model)) {
            return qExec.execConstruct();
        } finally {
            datasetGraph.end();
        }
    }

    private void getJsonld(Context ctx,
            SqliteAticDatasetGraph datasetGraph,
            Map<String, Object> cfg,
            Consumer<ParameterizedSparqlString> pssConsumer) {

        Model model = getModel(ctx, datasetGraph, cfg, pssConsumer);

        String jsonld = toJsonLdViaJena(model);

        ctx.contentType("application/ld+json");

        try {
            if (cfg.containsKey("frame")) {
                String frameCfg = (String) cfg.get("frame");

                JsonDocument input = JsonDocument.of(
                        Json.createReader(new StringReader(jsonld)).read()
                );

                JsonDocument frame = JsonDocument.of(
                        Json.createReader(new StringReader(frameCfg)).read()
                );

                JsonObject framed = JsonLd.frame(input, frame).get();
                ctx.result(framed.toString());

            } else {
                ctx.result(jsonld);
            }

        } catch (JsonLdError ex) {
            throw new RuntimeException(ex);
        }
    }

    private void handleGet(Context ctx, SqliteAticDatasetGraph datasetGraph, Map<String, Object> cfg) {
        getJsonld(ctx, datasetGraph, cfg, null);
    }

    private void handleGetInstance(Context ctx, SqliteAticDatasetGraph datasetGraph, Map<String, Object> cfg) {
        String uri = ctx.pathParam("uri");

        getJsonld(ctx, datasetGraph, cfg, (pss) -> {
            //TODO maybe later we should use a better variable name or something like ?_...
            pss.setIri("uri", uri);
        });
    }

    private void handlePost(String path, Context ctx, SqliteAticDatasetGraph datasetGraph, Map<String, Object> cfg) {
        handlePostOrPut(false, path, ctx, datasetGraph, cfg);
    }

    private void handlePutInstance(String path, Context ctx, SqliteAticDatasetGraph datasetGraph, Map<String, Object> cfg) {
        handlePostOrPut(true, path, ctx, datasetGraph, cfg);
    }

    private void handlePostOrPut(boolean isPut, String path, Context ctx, SqliteAticDatasetGraph datasetGraph, Map<String, Object> cfg) {

        //reason for extra sparql query: do not trust what payload client send us
        //read json ld
        String body = ctx.body();
        Model payloadModel = ModelFactory.createDefaultModel();
        RDFDataMgr.read(payloadModel, new StringReader(body), null, Lang.JSONLD11);

        //create a temp dataset with a payload graph
        Resource payloadGraph = ResourceFactory.createResource("urn:graph:payload-" + UUID.randomUUID().toString());
        Resource metaGraph = ResourceFactory.createResource("urn:graph:meta-" + UUID.randomUUID().toString());
        Dataset tempDataset = DatasetFactory.create();

        //if Txn, this seems important: add the payload graph in a separate write transaction
        tempDataset.addNamedModel(payloadGraph, payloadModel);

        //1. stage: run the query to fill the default graph
        //the update sparql
        String sparql = (String) cfg.get("sparql");

        //this way we can pass predefined values
        ParameterizedSparqlString pss = new ParameterizedSparqlString(sparql);
        pss.setParam("payload", payloadGraph);
        pss.setParam("meta", metaGraph);

        //if put we know what should be overwritten
        if (isPut) {
            String uri = ctx.pathParam("uri");
            pss.setIri("uri", uri);
        }

        UpdateRequest request = UpdateFactory.create(pss.toString());
        UpdateAction.execute(request, tempDataset);

        //select graph
        String graphUri = ctx.queryParam("graph");
        if(graphUri == null) {
            graphUri = Quad.defaultGraphIRI.getURI();
        }
        Node graphNode = NodeFactory.createURI(graphUri);
        
        //prepare invocation context
        InvocationContext ictx = AticServer.fromJavalinContext(ctx);

        //debug
        //StringWriter sw = new StringWriter();
        //RDFDataMgr.write(sw, tempDataset, Lang.TRIG);
        //System.out.println(sw);
        if (isPut) {
            String uri = ctx.pathParam("uri");

            //do a get instance
            Map<String, Object> getInstanceConf = (Map<String, Object>) cfg.get("getInstance");
            //current data
            Model actualModel = getModel(ctx, datasetGraph, getInstanceConf, (p) -> {
                //TODO maybe later we should use a better variable name or something like ?_...
                p.setIri("uri", uri);
            });

            //here the expected is written from the payload
            Model expectedModel = tempDataset.getDefaultModel();

            //TODO maybe use Node API here
            RDFPatch patch = diff(graphNode, actualModel, expectedModel);

            Txn.executeWrite(datasetGraph, () -> {
                datasetGraph.apply(patch, ictx);
            });

            ctx.status(204);

        } else {

            //ok idea is that insert query can write into a meta graph that marks what should be in the location
            //TODO better use a vocabulary here (maybe standard?)
            ResIterator riter = tempDataset
                    .getNamedModel(metaGraph)
                    .listSubjectsWithProperty(
                            RDF.type,
                            ResourceFactory.createResource("urn:atic:Location")
                    );
            List<Resource> createdResources = riter.toList();

            //2. stage add to real dataset
            datasetGraph.begin(TxnType.WRITE);
            try {
                SqliteAticGraph graph = (SqliteAticGraph) datasetGraph.getGraph(graphNode, ictx);

                // create StreamRDF using your storage graph
                StreamRDF streamRDF = graph.asStreamRDF(ictx, 500, 500, -1);

                // start streaming
                streamRDF.start();

                // read statements from tempDataset and send them to streamRDF
                Txn.executeRead(tempDataset, () -> {
                    Model tempModel = tempDataset.getDefaultModel();
                    StmtIterator iter = tempModel.listStatements();

                    while (iter.hasNext()) {
                        Statement stmt = iter.next();

                        // convert Jena Statement into a Triple
                        org.apache.jena.graph.Triple triple = stmt.asTriple();

                        // send the triple to your StreamRDF
                        streamRDF.triple(triple);
                    }
                });

                // finish streaming
                streamRDF.finish();

                datasetGraph.commit();

                ctx.status(204);
            } catch (PermissionDeniedException e) {
                datasetGraph.abort();
                ctx.status(HttpStatus.FORBIDDEN);
                ctx.result(e.getMessage());
                return;
            } catch (Exception e) {
                datasetGraph.abort();
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).result("SPARQL update error:\n" + e.getMessage());
                return;
            } finally {
                datasetGraph.end();
            }

            Map<Resource, String> res2url = new HashMap<>();
            for (Resource res : createdResources) {
                String encUri = URLEncoder.encode(createdResources.get(0).getURI(), StandardCharsets.UTF_8);
                
                if(graphNode.equals(Quad.defaultGraphIRI)) {
                    res2url.put(res, path + "/" + encUri);
                } else {
                    String encGraph = URLEncoder.encode(graphUri, StandardCharsets.UTF_8);
                    res2url.put(res, path + "/" + encUri + "?graph=" + encGraph);
                }
                
            }

            if (res2url.size() == 1) {
                //no content when only one is created because location is in header
                ctx.status(HttpStatus.NO_CONTENT);
                Entry<Resource, String> entry = res2url.entrySet().iterator().next();
                ctx.header("Location", entry.getValue());
                //TODO should also be a public final static attribute somewhere
                ctx.header("Atic-Resource-URI", entry.getKey().getURI());
                
            } else {
                //created when more then one is created
                ctx.status(HttpStatus.CREATED);
                ctx.json(
                        Map.of(
                                "locations", res2url.values(),
                                "resources", res2url.keySet().stream().map((r) -> r.getURI()).collect(toList())
                        )
                );
            }
        }
    }

    private void handleDeleteInstance(Context ctx, SqliteAticDatasetGraph datasetGraph, Map<String, Object> cfg) {

        String uri = ctx.pathParam("uri");
        String sparql = (String) cfg.get("sparql");

        //the update query
        ParameterizedSparqlString pss = new ParameterizedSparqlString(sparql);
        pss.setIri("uri", uri);
        UpdateRequest update = UpdateFactory.create(pss.toString());

        // Prepare dataset with user context
        Dataset dataset = DatasetFactory.wrap(datasetGraph);
        AticServer.transferContext(ctx, dataset.getContext());
        
        //select graph
        String graphUri = ctx.queryParam("graph");
        if(graphUri == null) {
            graphUri = Quad.defaultGraphIRI.getURI();
        }
        
        dataset.begin(TxnType.WRITE);
        Model model = dataset.getNamedModel(graphUri);
        try {
            UpdateAction.execute(update, model);

            dataset.commit();

            ctx.status(204);
        } catch (PermissionDeniedException e) {
            datasetGraph.abort();
            ctx.status(HttpStatus.FORBIDDEN);
            ctx.result(e.getMessage());
        } catch (Exception e) {
            datasetGraph.abort();
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).result("SPARQL update error:\n" + e.getMessage());
        } finally {
            datasetGraph.end();
        }
    }

    //===============================================
    //helper
    /*
    ok this is really frustrating:
     "totalItems": {
        "@value": "0",
        "@type": "xsd:integer"
      },
    
    the titanium code does not work when toString
     */
    private String toJsonLdViaTitanium(Model model) {

        try {
            // --- 4) configure JSON‑LD options ---
            JsonLdOptions opts = new JsonLdOptions();
            opts.setUseNativeTypes(true);

            QuadsToJsonld consumer = JsonLd.fromRdf();
            consumer.options(opts);

            StmtIterator iter = model.listStatements();
            while (iter.hasNext()) {

                Statement stmt = iter.next();

                String subject = stmt.getSubject().getURI();
                String predicate = stmt.getPredicate().getURI();

                String object;
                String datatype = null;
                String language = null;

                if (stmt.getObject().isLiteral()) {
                    Literal lit = stmt.getObject().asLiteral();
                    object = lit.getLexicalForm();
                    datatype = lit.getDatatypeURI();
                    language = lit.getLanguage();  // empty if none
                } else {
                    object = stmt.getObject().asResource().getURI();
                }

                // direction is null for normal RDF
                // graph is null = default graph
                consumer.quad(subject, predicate, object,
                        datatype, language,
                        null, null);

            }

            var array = consumer.toJsonLd();

            String v = array.toString();

            return v;

            /*
            StringWriter writer = new StringWriter();
            RDFDataMgr.write(writer, model, RDFFormat.JSONLD11_PRETTY);
            return writer.toString();
             */
        } catch (RdfConsumerException | JsonLdError ex) {
            throw new RuntimeException(ex);
        }
    }

    private String toJsonLdViaJena(Model model) {
        StringWriter writer = new StringWriter();
        RDFDataMgr.write(writer, model, RDFFormat.JSONLD11_PRETTY);
        return writer.toString();
    }

    public static RDFPatch diff(Node graph, Model actual, Model expected) {

        RDFChangesCollector collector = new RDFChangesCollector();

        // triples that must be deleted
        Model toDelete = actual.difference(expected);
        StmtIterator itDel = toDelete.listStatements();
        while (itDel.hasNext()) {
            Statement s = itDel.next();
            collector.delete(
                    graph,
                    s.getSubject().asNode(),
                    s.getPredicate().asNode(),
                    s.getObject().asNode()
            );
        }

        // triples that must be added
        Model toAdd = expected.difference(actual);
        StmtIterator itAdd = toAdd.listStatements();
        while (itAdd.hasNext()) {
            Statement s = itAdd.next();
            collector.add(
                    graph,
                    s.getSubject().asNode(),
                    s.getPredicate().asNode(),
                    s.getObject().asNode()
            );
        }

        return collector.getRDFPatch();
    }

    public int getGlobalDefaultLimit() {
        return globalDefaultLimit;
    }

    public void setGlobalDefaultLimit(int globalDefaultLimit) {
        this.globalDefaultLimit = globalDefaultLimit;
    }
    
}
