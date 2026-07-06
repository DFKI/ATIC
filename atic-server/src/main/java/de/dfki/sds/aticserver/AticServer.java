package de.dfki.sds.aticserver;

import com.apicatalog.jsonld.JsonLd;
import com.apicatalog.jsonld.document.JsonDocument;
import com.auth0.jwt.exceptions.JWTVerificationException;
import de.dfki.sds.atic.ac.Agent;
import de.dfki.sds.atic.ac.Group;
import de.dfki.sds.atic.ac.Permission;
import de.dfki.sds.atic.ac.PermissionDeniedException;
import de.dfki.sds.atic.ac.Principal;
import de.dfki.sds.atic.ac.PrincipalPermission;
import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.agent.Message;
import de.dfki.sds.atic.agent.Session;
import de.dfki.sds.atic.agent.SessionListener;
import de.dfki.sds.atic.conf.ConfigLoader;
import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.AticVirtualGraph;
import de.dfki.sds.atic.jenatic.AticVirtualGraphResponse;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.Database;
import de.dfki.sds.aticsqlite.DatabaseLongLivedConnection;
import de.dfki.sds.aticsqlite.DatabaseOptions;
import de.dfki.sds.aticsqlite.RDFPatchEmitterTransactional;
import de.dfki.sds.aticsqlite.RDFPatchListener;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import de.dfki.sds.aticsqlite.SqliteAticGraph;
import de.dfki.sds.rdfpatchsqlite.Converter;
import io.javalin.Javalin;
import io.javalin.http.ContentType;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.HandlerType;
import io.javalin.http.HttpStatus;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.UploadedFile;
import io.javalin.http.sse.SseClient;
import io.javalin.http.staticfiles.Location;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonWriter;
import jakarta.json.JsonWriterFactory;
import jakarta.json.stream.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collectors;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.ARQ;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.query.TxnType;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.changes.RDFChangesBase;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.system.Txn;
import org.apache.jena.update.UpdateExecution;
import org.apache.jena.update.UpdateExecutionFactory;
import org.apache.jena.update.UpdateFactory;
import org.apache.jena.update.UpdateRequest;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.VOID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mindrot.jbcrypt.BCrypt;

/**
 *
 */
public class AticServer {

    private static final Logger LOGGER = Logger.getLogger(AticServer.class.getName());

    private Javalin app;

    private AticConfig config;
    private File dataFolder;
    private File logsFolder;
    private File cdceFolder;
    private File patchesFolder;

    private SqliteAticDatasetGraph datasetGraph;
    private MoleculeEndpoint moleculeEndpoint;

    private RDFPatchWriter rdfPatchWriter;

    public AticServer(AticConfig config) {
        this.config = config;
        initFolders();
        initLogging();

        //write rdf patches to folder
        rdfPatchWriter = new RDFPatchWriter(patchesFolder, Duration.ofSeconds(config.getRdfpatchRotationinterval()));

        //database
        File sqliteDatabaseFile = new File(dataFolder, "atic.sqlite");
        DatabaseOptions options
                = new DatabaseOptions.Builder(sqliteDatabaseFile.toString())
                        .busyTimeoutMs(config.databaseBusyTimeoutMs)
                        .enableWal(config.databaseWalEnabled)
                        .enableForeignKeys(config.databaseForeignKeysEnabled)
                        .build();
        Database database = new DatabaseLongLivedConnection(options);

        datasetGraph = new SqliteAticDatasetGraph(database, rdfPatchWriter, new SqliteAticDatasetGraph.Capabilities(config.isRdfStarEnabled()));
    }

    private void initFolders() {
        dataFolder = new File(config.home, "data");
        dataFolder.mkdirs();

        logsFolder = new File(config.home, "logs");
        logsFolder.mkdirs();

        cdceFolder = new File(config.home, "cdce");
        cdceFolder.mkdirs();

        patchesFolder = new File(config.home, "patches");
        patchesFolder.mkdirs();
    }

    private void initLogging() {
        try {
            if (config.getLocale() != null && !config.getLocale().isEmpty()) {
                Locale locale = Locale.forLanguageTag(config.locale);
                Locale.setDefault(locale);
            }

            File logFile = new File(logsFolder, "atic.log");

            // always disable parent handlers FIRST
            LOGGER.setUseParentHandlers(false);

            FileHandler fileHandler = new FileHandler(logFile.getAbsolutePath(), true);
            fileHandler.setFormatter(new SimpleFormatter());
            LOGGER.addHandler(fileHandler);

            if (config.isPrintLog()) {
                ConsoleHandler consoleHandler = new ConsoleHandler();
                consoleHandler.setFormatter(new SimpleFormatter());
                LOGGER.addHandler(consoleHandler);
            }

            LOGGER.info("Logging initialized at " + logFile.getAbsolutePath());

        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize logging", e);
        }
    }

    public void init() {
        init(null);
    }

    public void init(BiConsumer<Javalin, AticConfig> additionalInit) {
        app = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/app";        // endpoints under /app
                staticFiles.directory = "/de/dfki/sds/aticserver/www/app";
                staticFiles.location = Location.CLASSPATH;
            });
        });

        app.exception(PermissionDeniedException.class, (e, ctx) -> {
            ctx.status(HttpStatus.FORBIDDEN);
            ctx.result(e.getMessage());
        });

        app.exception(Exception.class, (e, ctx) -> {
            System.err.println("==========================================");
            System.err.println(LocalDateTime.now().toString());
            e.printStackTrace();
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            ctx.result(e.getMessage());
        });

        app.before(ctx -> {
            String path = ctx.path();
            if (!path.equals("/") && path.endsWith("/")) {
                ctx.redirect(path.substring(0, path.length() - 1));
            }
        });

        initRoutes();

        initCDCE();
        initMoleculeEndpoint();

        if (additionalInit != null) {
            additionalInit.accept(app, config);
        }

        app.start(config.getHost(), config.getPort());
        LOGGER.info(() -> "atic server running at http://" + config.getHost() + ":" + config.getPort());
    }

    // Configuration-Driven CRUD Endpoints (CDCE)
    private void initCDCE() {
        if (!cdceFolder.exists() || !cdceFolder.isDirectory()) {
            LOGGER.warning("CDCE folder not found: " + cdceFolder.getAbsolutePath());
            return;
        }

        String resourcePath = "/de/dfki/sds/aticserver/cdce/";
        List<String> resourceNames = List.of("rml-project.yml");
        for (String resourceName : resourceNames) {
            LOGGER.info("Load CDCE config from resources: " + resourceName);

            ConfigDrivenCrudEndpoints cdce = new ConfigDrivenCrudEndpoints(resourcePath + resourceName);
            cdce.register(app, config.cdceEndpointPath, getDatasetGraph());
        }

        File[] files = cdceFolder.listFiles();

        if (files == null) {
            return;
        }

        for (File file : files) {

            // only *.yml files
            if (file.isFile() && file.getName().endsWith(".yml")) {

                LOGGER.info("Found CDCE config: " + file.getAbsolutePath());

                ConfigDrivenCrudEndpoints cdce = new ConfigDrivenCrudEndpoints(file);
                cdce.register(app, config.cdceEndpointPath, getDatasetGraph());
            }
        }
    }

    private void initMoleculeEndpoint() {
        moleculeEndpoint = new MoleculeEndpoint();
        moleculeEndpoint.register(app, "", datasetGraph);
    }

    public void close() {
        app.stop();
    }

    private void initRoutes() {
        app.before("/*", this::authorizationMiddleware);

        app.get("/", ctx -> ctx.redirect("/app"));
        app.get("/app", ctx -> ctx.redirect("/app/login.html"));
        app.get("/app/login.html", this::getAppLogin);

        app.get("/about", this::getAbout);

        app.post("/auth/token", this::postToken);
        app.post("/auth/register", this::postRegister);
        app.get("/auth/me", this::getAuthMe);
        app.post("/auth/logout", this::postLogout);
        app.put("/auth/password", this::putPassword);

        app.get("/config", this::getAllConfig);
        app.get("/config/{name}", this::getSingleConfig);

        // SPARQL endpoint (supports GET + POST)
        app.get("/sparql", this::handleSparql);
        app.post("/sparql", this::handleSparql);
        // SPARQL update endpoint
        app.post("/update", this::handleSparqlUpdate);

        //Share/Unshare Graphs
        app.post("/graph/share", this::postShareGraphs);
        app.delete("/graph/share", this::deleteShareGraphs);
        app.get("/graph/access", this::getGraphAccess);

        //Share/Unshare Resources
        app.post("/resource/share", this::postShareResources);
        app.delete("/resource/share", this::deleteShareResources);
        app.get("/resource/access", this::getResourceAccess);

        app.get("/graph", this::getGraph);
        app.post("/graph", this::postGraph);
        app.delete("/graph/{uri}", this::deleteGraph);

        app.post("/rml/execution", this::postRmlExecution);

        app.post("/querylogger:enable", this::postQueryLoggerEnable);
        app.post("/querylogger:disable", this::postQueryLoggerDisable);

        app.post("/agent:enable", this::postAgentEnable);
        app.post("/agent:disable", this::postAgentDisable);

        app.post("/session", this::postSessionAdd);
        app.post("/session/{agentUsername}/{sessionId}/messages", this::postSessionMessage);
        app.sse("/session/{agentUsername}/{sessionId}/stream", this::sessionStream);
        app.get("/session", this::getSessionList);
        app.get("/session/{agentUsername}/{sessionId}", this::getSessionGet);
        app.delete("/session/{agentUsername}/{sessionId}", this::postSessionRemove);
        app.put("/session/{agentUsername}/{sessionId}/title", this::putSessionTitle);

        app.post("/upload", this::postUpload);

        app.get("/user", this::getQueryUser);
        app.get("/users", this::getUsers);
        app.get("/agents", this::getAgents);
        app.get("/principal", this::getQueryPrincipal);

        app.get("/vkg/{uri}/**", this::handleVirtualGraphRequest);
    }

    private void getAppLogin(Context ctx) throws IOException {
        InputStream is = AticServer.class.getResourceAsStream(
                "/de/dfki/sds/aticserver/www/app/login.html"
        );

        String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        html = html.replace("{{instanceName}}", config.instanceName);

        ctx.html(html);
    }

    private void getAbout(Context ctx) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(Main.ASCII_LOGO).append("\n");
        sb.append(VersionUtil.getVersion()).append("\n");
        sb.append("https://github.com/DFKI/ATIC").append("\n\n");
        sb.append("Instance Name: ").append(config.instanceName).append("\n");
        ctx.contentType(ContentType.TEXT_PLAIN);
        ctx.result(sb.toString());
    }

    //------------------------------------------
    //graph
    private void getGraph(Context ctx) {
        InvocationContext ictx = fromJavalinContext(ctx);

        boolean showTriples = ctx.queryParamMap().containsKey("triples");
        boolean showPermissions = ctx.queryParamMap().containsKey("permissions");

        try {
            Graph result = GraphFactory.createDefaultGraph();

            Txn.executeRead(datasetGraph, () -> {

                List<Node> graphs = new ArrayList<>();

                //also add default graph only if readable
                try {
                    SqliteAticGraph defaultGraph = (SqliteAticGraph) datasetGraph.getDefaultGraph(ictx);
                    graphs.add(defaultGraph.asNodes().get(0));
                } catch (PermissionDeniedException e) {
                    //ignore
                }

                Iterator<Node> it = datasetGraph.listGraphNodes(ictx);
                while (it.hasNext()) {
                    graphs.add(it.next());
                }

                //permissions
                Map<String, Permission> permissions = Map.of();
                if (showPermissions) {
                    Set<String> graphUris = graphs.stream()
                            .map(Node::getURI)
                            .collect(Collectors.toSet());

                    permissions = datasetGraph.listGraphPermissions(graphUris, ictx);
                }

                Node container = NodeFactory.createBlankNode();

                for (Node graph : graphs) {
                    result.add(container, RDF.type.asNode(), RDFS.Container.asNode());
                    result.add(container, RDFS.member.asNode(), graph);

                    result.add(graph, RDF.type.asNode(), VOID.Dataset.asNode());

                    //on query param also provide statistics (void:triples)
                    if (showTriples) {
                        AticGraph g = datasetGraph.getGraph(graph, ictx);
                        result.add(graph, VOID.triples.asNode(), NodeFactory.createLiteralByValue(g.size(ictx)));
                    }

                    //also add permission from viewpoint of ictx
                    if (showPermissions) {
                        Permission perm = permissions.get(graph.getURI());
                        if (perm != null) {
                            result.add(
                                    graph,
                                    NodeFactory.createURI("urn:atic:permission"), //TODO better vocabulary
                                    perm.asNode()
                            );
                            result.add(
                                    perm.asNode(),
                                    RDFS.label.asNode(),
                                    NodeFactory.createLiteralString(perm.toString())
                            );
                        }
                    }
                }

            });

            StringWriter writer = new StringWriter();
            RDFDataMgr.write(writer, result, RDFFormat.JSONLD11_PRETTY);
            String jsonld = writer.toString();

            //TODO use Jsonld.compact with extra context to cleanup the not so pretty jena jsonld
            String frameCfg = """
                            {
                              "@context": {
                                "rdfs": "http://www.w3.org/2000/01/rdf-schema#",
                                "void": "http://rdfs.org/ns/void#",
                                "items": {
                                  "@id": "http://www.w3.org/2000/01/rdf-schema#member",
                                  "@container": "@set"
                                },
                                "triples": {
                                  "@id": "void:triples",
                                  "@type": "xsd:integer"
                                }
                              },
                              "@type": "rdfs:Container",
                              "items": {
                                "@embed": "@always",
                                "@default": []
                              }
                            }
                              """;

            JsonDocument input = JsonDocument.of(
                    Json.createReader(new StringReader(jsonld)).read()
            );

            JsonDocument frame = JsonDocument.of(
                    Json.createReader(new StringReader(frameCfg)).read()
            );

            JsonObject framed = JsonLd.frame(input, frame).get();
            ctx.result(framed.toString());

            Map<String, Object> config = Map.of(
                    JsonGenerator.PRETTY_PRINTING, true
            );

            JsonWriterFactory writerFactory = Json.createWriterFactory(config);

            StringWriter sw = new StringWriter();
            try (JsonWriter jsonWriter = writerFactory.createWriter(sw)) {
                jsonWriter.writeObject(framed);
            }

            //return as json-ld
            ctx.contentType("application/ld+json");
            ctx.result(sw.toString());

        } catch (Exception e) {
            ctx.status(500).json(Map.of(
                    "success", false,
                    "error", "Failed to list graphs"
            ));
        }
    }

    private void postGraph(Context ctx) {
        InvocationContext ictx = fromJavalinContext(ctx);

        try {
            Node graphNode;

            // ------------------------------------------------
            // case 1: empty body -> auto-create graph
            // ------------------------------------------------
            if (ctx.body().isBlank()) {

                graphNode = Txn.calculateWrite(datasetGraph, () -> {
                    return datasetGraph.addGraph(Graph.emptyGraph, ictx);
                });

            } else {

                JSONObject body = new JSONObject(ctx.body());

                String graphNameStr = body.optString("graph", null);
                if (graphNameStr == null || graphNameStr.isBlank()) {
                    ctx.status(400).json(Map.of(
                            "success", false,
                            "error", "Missing graph name"
                    ));
                    return;
                }

                graphNode = NodeFactory.createURI(graphNameStr);

                String method = body.optString("method", null);
                JSONObject config = body.optJSONObject("config");

                // ------------------------------------------------
                // case 2: virtual graph
                // ------------------------------------------------
                if (method != null && config != null) {

                    Txn.executeWrite(datasetGraph, () -> {
                        datasetGraph.addVirtualGraph(
                                graphNode,
                                method,
                                config,
                                ictx
                        );
                    });

                } else {

                    // ------------------------------------------------
                    // case 3: normal graph
                    // ------------------------------------------------
                    Txn.executeWrite(datasetGraph, () -> {
                        datasetGraph.addGraph(
                                graphNode,
                                Graph.emptyGraph,
                                ictx
                        );
                    });
                }
            }

            // ------------------------------------------------
            // response
            // ------------------------------------------------
            ctx.header(
                    "Location",
                    "/graph/" + URLEncoder.encode(graphNode.getURI(), StandardCharsets.UTF_8)
            );
            ctx.header("Atic-Resource-URI", graphNode.getURI());

            ctx.status(201).json(Map.of(
                    "success", true,
                    "graph", graphNode.getURI()
            ));

        } catch (Exception e) {
            ctx.status(500).json(Map.of(
                    "success", false,
                    "error", "Failed to create graph"
            ));
        }
    }

    private void deleteGraph(Context ctx) {
        InvocationContext ictx = fromJavalinContext(ctx);

        try {
            String graphNameStr = ctx.pathParam("uri");

            if (graphNameStr == null || graphNameStr.isBlank()) {
                ctx.status(400).json(Map.of(
                        "success", false,
                        "error", "Missing 'uri' query parameter"
                ));
                return;
            }

            Node graphNode = NodeFactory.createURI(graphNameStr);

            Txn.executeWrite(datasetGraph, () -> {
                try {
                    datasetGraph.removeGraph(graphNode, ictx);

                    ctx.json(Map.of(
                            "success", true,
                            "graph", graphNameStr
                    ));

                } catch (IllegalArgumentException e) {
                    ctx.status(HttpStatus.BAD_REQUEST).json(Map.of(
                            "success", false,
                            "error", e.getMessage()
                    ));
                }
            });

        } catch (Exception e) {
            ctx.status(500).json(Map.of(
                    "success", false,
                    "error", "Failed to delete graph"
            ));
        }
    }

    //------------------------------------------
    //rml
    private void postRmlExecution(Context ctx) {
        InvocationContext ictx = fromJavalinContext(ctx);
        JSONObject body = new JSONObject(ctx.body());

        //if no graph is given, we assume default graph
        String graphNameStr = body.optString("graph", Quad.defaultGraphIRI.getURI());

        String projectStr = body.optString("project", null);
        if (projectStr == null || projectStr.isBlank()) {
            ctx.status(400).json(Map.of(
                    "success", false,
                    "error", "Missing project resource"
            ));
            return;
        }

        Node rmlProjectGraph = NodeFactory.createURI(graphNameStr);
        Node rmlProjectResource = NodeFactory.createURI(projectStr);
        int bufferSize = body.optInt("bufferSize", 100_000);

        //never throws, will add stacktracke to rmlProjectResource
        synchronized (this) {
            datasetGraph.executeWrite(() -> {
                datasetGraph.runRML(rmlProjectGraph, rmlProjectResource, bufferSize, ictx);
            });
        }

        //because everything is attached to rmlProjectResource in RDF graph
        ctx.status(HttpStatus.NO_CONTENT);
    }

    //-------------------------------------------
    //share
    private void postShareGraphs(Context ctx) {
        handleShareUnshare(ctx, "graph", true);
    }

    private void deleteShareGraphs(Context ctx) {
        handleShareUnshare(ctx, "graph", false);
    }

    private void getGraphAccess(Context ctx) {
        handleGetAccess(ctx, "graph");
    }

    private void postShareResources(Context ctx) {
        handleShareUnshare(ctx, "resource", true);
    }

    private void deleteShareResources(Context ctx) {
        handleShareUnshare(ctx, "resource", false);
    }

    private void getResourceAccess(Context ctx) {
        handleGetAccess(ctx, "resource");
    }

    private void handleShareUnshare(
            Context ctx,
            String mode,
            boolean isShare
    ) {
        JSONObject json = new JSONObject(ctx.body());

        // pick the key based on mode
        String itemsKey = mode.equals("graph") ? "graphs" : "resources";
        JSONArray itemsArray = json.getJSONArray(itemsKey);

        Set<String> items = new HashSet<>();
        for (int i = 0; i < itemsArray.length(); i++) {
            items.add(itemsArray.getString(i));
        }

        JSONArray groupArray = json.getJSONArray("groups");
        Set<String> groupUris = new HashSet<>();
        for (int i = 0; i < groupArray.length(); i++) {
            groupUris.add(groupArray.getString(i));
        }

        String message = json.optString("message", "");
        String sessionId = json.optString("sessionId", "session-" + UUID.randomUUID().toString());

        InvocationContext ictx = fromJavalinContext(ctx);

        Txn.executeWrite(datasetGraph, () -> {

            if (isShare) {
                String permStr = json.getString("permission");
                Permission permission = Permission.valueOf(permStr);

                if (mode.equals("graph")) {
                    datasetGraph.shareGraphs(items, groupUris, permission, message, sessionId, ictx);
                } else {
                    datasetGraph.shareResources(items, groupUris, permission, message, sessionId, ictx);
                }
                ctx.status(204);//.json(Map.of("success", true));
            } else {
                if (mode.equals("graph")) {
                    datasetGraph.unshareGraphs(items, groupUris, message, sessionId, ictx);
                } else {
                    datasetGraph.unshareResources(items, groupUris, message, sessionId, ictx);
                }
                ctx.status(204);
            }

        });
    }

    private void handleGetAccess(
            Context ctx,
            String mode
    ) {

        List<String> uris = ctx.queryParams("uri");
        if (uris == null || uris.isEmpty()) {
            uris = ctx.queryParams("uri[]");

            if (uris == null || uris.isEmpty()) {
                ctx.json(Map.of());
                return;
            }
        }

        InvocationContext ictx = fromJavalinContext(ctx);

        boolean forGraphs = mode.equals("graph");

        Set<String> uriSet = new HashSet<>(uris);
        Map<String, List<PrincipalPermission>> result = Txn.calculateRead(datasetGraph, ()
                -> datasetGraph.listPrincipalPermissions(uriSet, forGraphs, ictx)
        );

        // convert to JSON
        JSONObject response = new JSONObject();

        for (Map.Entry<String, List<PrincipalPermission>> entry : result.entrySet()) {

            JSONArray principalsArray = new JSONArray();

            for (PrincipalPermission pp : entry.getValue()) {

                JSONObject ppJson = new JSONObject();

                ppJson.put("principalName", pp.getPrincipal().getName());
                ppJson.put("principalUri", pp.getPrincipal().getUri());
                ppJson.put("principalShareUri", pp.getPrincipal().getShareUri());
                ppJson.put("permission", pp.getPermission().name());

                if (pp.getPrincipal() instanceof User) {
                    ppJson.put("type", "user");
                } else if (pp.getPrincipal() instanceof Group) {
                    ppJson.put("type", "group");
                }

                principalsArray.put(ppJson);
            }

            response.put(entry.getKey(), principalsArray);
        }

        ctx.json(response.toMap());
    }

    private void getQueryUser(Context ctx) {

        InvocationContext ictx = fromJavalinContext(ctx);

        String query = ctx.queryParam("query");
        if (query == null || query.isBlank()) {
            ctx.json(Map.of("users", List.of()));
            return;
        }

        List<User> users = datasetGraph.calculateRead(() -> {
            return datasetGraph.searchUsers(query, ictx);
        });

        List<Map<String, Object>> result = new ArrayList<>();

        for (User user : users) {
            result.add(user.toMap());
        }

        ctx.json(Map.of("users", result));
    }

    private void getUsers(Context ctx) {

        InvocationContext ictx = fromJavalinContext(ctx);

        List<User> users = datasetGraph.calculateRead(() -> {
            return datasetGraph.getAllUsers(ictx);
        });

        List<Map<String, Object>> result = new ArrayList<>();

        for (User user : users) {
            result.add(user.toMap());
        }

        ctx.json(Map.of("users", result));
    }

    private void getAgents(Context ctx) {

        InvocationContext ictx = fromJavalinContext(ctx);

        List<Agent> agents = datasetGraph.calculateRead(() -> {
            return datasetGraph.getAllAgents(ictx);
        });

        List<Map<String, Object>> result = new ArrayList<>();

        for (Agent agent : agents) {
            result.add(agent.toMap());
        }

        ctx.json(Map.of("agents", result));
    }

    private void getQueryPrincipal(Context ctx) {

        InvocationContext ictx = fromJavalinContext(ctx);

        String query = ctx.queryParam("query");
        if (query == null || query.isBlank()) {
            ctx.json(Map.of("principals", List.of()));
            return;
        }

        List<Principal> principals = datasetGraph.calculateRead(() -> {
            return datasetGraph.searchPrincipals(query, ictx);
        });

        List<Map<String, Object>> result = new ArrayList<>();

        for (Principal principal : principals) {
            result.add(principal.toMap());
        }

        //TODO later maybe use json-ld (RDF) here too
        ctx.json(Map.of("principals", result));
    }

    //-----------------------------------------
    //sparql
    private void handleSparql(Context ctx) throws IOException {
        String accept = ctx.header("Accept");

        if (accept != null && accept.contains("text/html")) {

            try (InputStream is = AticServer.class.getResourceAsStream(
                    "/de/dfki/sds/aticserver/www/app/sparql.html")) {

                if (is == null) {
                    ctx.status(404).result("sparql.html not found");
                    return;
                }

                String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                //TODO later use better html render engine
                html = html.replace("{{defaultTimeout}}", "" + config.sparqlTimeout);
                html = html.replace("{{defaultQuery}}", config.sparqlDefaultquery.trim());
                html = html.replace("{{defaultPrefixes}}", config.sparqlDefaultprefixes.trim());
                html = html.replace("{{instanceName}}", config.instanceName);
                html = html.replace("{{token}}", getToken(ctx));

                ctx.html(html);
                return;
            }
        }

        HandlerType method = ctx.method();
        String queryString = null;

        if (HandlerType.GET.equals(method)) {
            queryString = ctx.queryParam("query");
        } else if (HandlerType.POST.equals(method)) {
            String contentType = ctx.contentType();
            if ("application/sparql-query".equalsIgnoreCase(contentType)) {
                queryString = ctx.body();
            } else {
                queryString = ctx.formParam("query");
            }
        }

        if (queryString == null || queryString.isEmpty()) {
            ctx.status(400).result("Missing SPARQL query");
            return;
        }

        if (accept == null || accept.isEmpty()) {
            accept = "application/sparql-results+json";
        }

        Query queryObj = QueryFactory.create(queryString);

        //wrap with context
        Dataset dataset = DatasetFactory.wrap(datasetGraph);
        AticServer.transferContext(ctx, dataset.getContext());

        String timeoutHeader = ctx.header("Atic-Timeout");

        // Parse timeout safely
        int timeout;
        try {
            timeout = timeoutHeader != null ? Integer.parseInt(timeoutHeader) : config.sparqlTimeout;
        } catch (NumberFormatException e) {
            timeout = config.sparqlTimeout;
        }

        //the engine
        datasetGraph.begin(TxnType.READ);
        try (QueryExecution qExec = QueryExecutionFactory.create(queryObj, dataset)) {
            qExec.getContext().set(ARQ.queryTimeout, timeout);

            if (queryObj.isSelectType()) {
                ResultSet rs = qExec.execSelect();
                ByteArrayOutputStream out = new ByteArrayOutputStream();

                if (accept.contains("csv")) {
                    ResultSetFormatter.outputAsCSV(out, rs);
                    ctx.contentType("text/csv");
                } else {
                    ResultSetFormatter.outputAsJSON(out, rs);
                    ctx.contentType("application/sparql-results+json");
                }

                ctx.result(out.toString(StandardCharsets.UTF_8));
                return;

            } else if (queryObj.isAskType()) {
                boolean boolResult = qExec.execAsk();
                ByteArrayOutputStream out = new ByteArrayOutputStream();

                if (accept.contains("csv")) {
                    ResultSetFormatter.outputAsCSV(out, boolResult);
                    ctx.contentType("text/csv");
                } else {
                    ResultSetFormatter.outputAsJSON(out, boolResult);
                    ctx.contentType("application/sparql-results+json");
                }

                ctx.result(out.toString(StandardCharsets.UTF_8));
                return;

            } else if (queryObj.isConstructType()) {
                Model model = qExec.execConstruct();
                String format;
                if (accept.contains("json") && accept.contains("ld+json")) {
                    format = "JSON-LD";
                    ctx.contentType("application/ld+json");
                } else if (accept.contains("turtle")) {
                    format = "TURTLE";
                    ctx.contentType("text/turtle");
                } else if (accept.contains("rdf+xml")) {
                    format = "RDF/XML";
                    ctx.contentType("application/rdf+xml");
                } else {
                    format = "N-TRIPLES";
                    ctx.contentType("application/n-triples");
                }

                StringWriter sw = new StringWriter();
                model.write(sw, format);
                ctx.result(sw.toString());
                return;
            }

            ctx.status(400).result("Unsupported SPARQL query type");

        } catch (PermissionDeniedException e) {
            ctx.status(HttpStatus.FORBIDDEN).result(e.getMessage());
        } catch (Exception e) {
            ctx.status(500).result("SPARQL execution error:\n" + e.getMessage() + "\n\n" + ExceptionUtils.getStackTrace(e));
        } finally {
            datasetGraph.end();
        }
    }

    private void handleSparqlUpdate(Context ctx) {
        // Extract the update string
        String contentType = ctx.contentType();
        String updateString = null;

        if ("application/sparql-update".equalsIgnoreCase(contentType)) {
            updateString = ctx.body();
        } else {
            // x‑www‑form‑urlencoded: parameter name = "update"
            updateString = ctx.formParam("update");
        }

        if (updateString == null || updateString.isEmpty()) {
            ctx.status(400).result("Missing SPARQL update");
            return;
        }

        Dataset dataset = DatasetFactory.wrap(datasetGraph);
        AticServer.transferContext(ctx, dataset.getContext());

        String timeoutHeader = ctx.header("Atic-Timeout");

        // Parse timeout safely
        int timeout;
        try {
            timeout = timeoutHeader != null ? Integer.parseInt(timeoutHeader) : config.sparqlTimeout;
        } catch (NumberFormatException e) {
            timeout = config.sparqlTimeout;
        }

        datasetGraph.begin(TxnType.WRITE);
        try {
            // Parse and execute the update
            UpdateRequest request = UpdateFactory.create(updateString);
            UpdateExecution updateExecution = UpdateExecutionFactory.create(request, dataset);

            updateExecution.getContext().set(ARQ.queryTimeout, timeout);

            updateExecution.execute();

            datasetGraph.commit();

            ctx.status(204); // No Content on success (protocol allows various 2xx)
        } catch (PermissionDeniedException e) {
            ctx.status(HttpStatus.FORBIDDEN).result(e.getMessage());
            datasetGraph.abort();
        } catch (Exception e) {
            datasetGraph.abort();
            ctx.status(500).result("SPARQL update error:\n" + e.getMessage());
        } finally {
            datasetGraph.end();
        }
    }

    //-----------------------------------------
    //vkg
    private void handleVirtualGraphRequest(Context ctx) {

        InvocationContext ictx = fromJavalinContext(ctx);

        HandlerType method = ctx.method();
        String uri = ctx.pathParam("uri");
        String path = ctx.path();
        Map<String, List<String>> queryParamMap = ctx.queryParamMap();

        //we remove the /vkg/{uri} part
        String[] parts = path.split("/");
        String cleanPath = "/" + (parts.length > 3
                ? Arrays.stream(parts).skip(3).collect(Collectors.joining("/")) : "");

        Map<Node, AticVirtualGraph> vkgMap = datasetGraph.getVirtualGraphMap();

        AticVirtualGraph graph = vkgMap.get(NodeFactory.createURI(uri));

        if (graph == null) {
            throw new IllegalStateException("Virtal graph not loaded: " + uri);
        }

        try {
            //we need read transaction because of getUser
            AticVirtualGraphResponse resp = datasetGraph.calculateRead(() -> {
                return graph.handleRequest(method.toString(), cleanPath, queryParamMap, ictx);
            });

            if (resp == null) {
                throw new RuntimeException("response is null");
            }

            ctx.status(resp.getStatus());
            ctx.contentType(resp.getContentType());
            ctx.result(resp.getInputStream());

        } catch (Exception e) {
            ctx.status(500).result("Virtual graph failed to handle request");
        }
    }

    //-------------------------------------
    //config
    private void getAllConfig(Context ctx) {
        try {
            ctx.json(ConfigLoader.toJson(config).toMap());
        } catch (Exception e) {
            ctx.status(500).result("Failed to serialize config");
        }
    }

    private void getSingleConfig(Context ctx) {
        String name = ctx.pathParam("name");

        try {
            Object value = ConfigLoader.getConfig(name, config);

            ctx.contentType(ContentType.TEXT_PLAIN);
            ctx.result(String.valueOf(value));

        } catch (IllegalArgumentException e) {
            ctx.status(404).result(e.getMessage());
        } catch (Exception e) {
            ctx.status(500).result("Failed to read config");
        }
    }

    //-------------------------------------
    //upload
    private void postUpload(Context ctx) {

        // read config parameters
        String graphUri = ctx.formParam("graph");
        List<String> groups = ctx.formParams("group"); //it is the group URI (not group name)

        String permissionStr = ctx.formParam("permission");
        Permission permission = null;
        if (permissionStr != null) {
            permission = Permission.valueOf(permissionStr);
        }

        String bufferSizeStr = ctx.formParam("buffer-size");
        int bufferSize = 500;
        if (bufferSizeStr != null) {
            bufferSize = Integer.parseInt(bufferSizeStr);
        }

        String batchSizeStr = ctx.formParam("batch-size");
        int batchSize = 500;
        if (batchSizeStr != null) {
            batchSize = Integer.parseInt(batchSizeStr);
        }

        InvocationContext ictx = fromJavalinContext(ctx);

        // select graph
        SqliteAticGraph graph;
        datasetGraph.begin(ReadWrite.READ);
        try {
            if (graphUri != null) {
                Node graphNode = NodeFactory.createURI(graphUri);
                graph = (SqliteAticGraph) datasetGraph.getGraph(graphNode, ictx);
            } else {
                graph = (SqliteAticGraph) datasetGraph.getDefaultGraph(ictx);
            }
        } catch (PermissionDeniedException e) {
            datasetGraph.abort();
            ctx.status(HttpStatus.FORBIDDEN).result(e.getMessage());
            return;
        } finally {
            datasetGraph.end();
        }

        // get uploaded files
        List<UploadedFile> files = ctx.uploadedFiles("file");

        for (UploadedFile file : files) {

            // detect RDF language from filename
            Lang lang = RDFLanguages.filenameToLang(file.filename());
            if (lang == null) {
                lang = Lang.TURTLE; // fallback
            }

            boolean share = groups != null && !groups.isEmpty() && permission != null;

            //TODO could be optimized later
            Permission finalPermission = permission;
            RDFPatchListener listener = null;
            if (share) {
                listener = new RDFPatchListener() {
                    @Override
                    public void handlePatch(RDFPatch patch) {
                        Set<String> resourceUris = new HashSet<>();

                        patch.apply(new RDFChangesBase() {
                            @Override
                            public void add(Node g, Node s, Node p, Node o) {

                                //ignore special graphs for permission and group assignment
                                g = Converter.unwrapNode(g);
                                if (RDFPatchEmitterTransactional.isSpecialGraph(g)) {
                                    return;
                                }

                                String suri = Converter.unwrapUri(s);
                                String ouri = Converter.unwrapUri(o);
                                resourceUris.add(suri);
                                if (ouri != null) {
                                    resourceUris.add(ouri);
                                }
                            }
                        });

                        Set<String> groupUris = new HashSet<>(groups);

                        datasetGraph.executeWrite(() -> {
                            datasetGraph.shareResources(
                                    resourceUris,
                                    groupUris,
                                    finalPermission,
                                    ictx
                            );
                        });
                    }
                };

                datasetGraph.addListener(listener);
            }

            //write fast into graph
            datasetGraph.begin(ReadWrite.WRITE);
            try (InputStream in = file.content()) {
                // Create StreamRDF sink from your graph
                StreamRDF streamRDF = graph.asStreamRDF(ictx, bufferSize, batchSize, -1);

                // Start streaming
                streamRDF.start();

                // Use Jena RDFParser to send triples directly to StreamRDF
                RDFParser.create()
                        .source(in)
                        .lang(lang)
                        .parse(streamRDF);

                // Finish streaming
                streamRDF.finish();

                datasetGraph.commit();

            } catch (Exception e) {
                datasetGraph.abort();
                throw new RuntimeException("Failed to process file: " + file.filename(), e);
            } finally {
                datasetGraph.end();
            }

            if (share) {
                datasetGraph.removeListener(listener);
            }

            /*
            // share resources if requested
            if (groups != null && !groups.isEmpty() && permission != null) {

                Model model = ModelFactory.createDefaultModel();

                datasetGraph.begin(ReadWrite.WRITE);
                try (InputStream in = file.content()) {
                    RDFDataMgr.read(model, in, lang);

                    // collect resources
                    Set<Resource> resources = new HashSet<>();

                    model.listStatements().forEachRemaining(stmt -> {

                        Resource s = stmt.getSubject();
                        if (s.isURIResource()) {
                            resources.add(s);
                        }

                        RDFNode o = stmt.getObject();
                        if (o.isResource()) {
                            Resource r = o.asResource();
                            if (r.isURIResource()) {
                                resources.add(r);
                            }
                        }
                    });

                    // convert to URI set
                    Set<String> resourceUris = resources.stream()
                            .map(Resource::getURI)
                            .collect(Collectors.toSet());

                    Set<String> groupUris = new HashSet<>(groups);

                    datasetGraph.shareResources(
                            resourceUris,
                            groupUris,
                            permission,
                            ictx
                    );

                    datasetGraph.commit();

                } catch (Exception e) {
                    datasetGraph.abort();
                    throw new RuntimeException("Failed to process file: " + file.filename(), e);
                } finally {
                    datasetGraph.end();
                }
            }*/
        }

        ctx.status(200).result("Upload successful");
    }

    //-------------------------------------
    //query logger
    private void postQueryLoggerEnable(Context ctx) {
        InvocationContext ictx = fromJavalinContext(ctx);
        JSONObject body = new JSONObject(ctx.body());
        String path = body.getString("path");
        datasetGraph.enableQueryLogger(path, ictx);
    }

    private void postQueryLoggerDisable(Context ctx) {
        InvocationContext ictx = fromJavalinContext(ctx);
        datasetGraph.disableQueryLogger(ictx);
    }

    private void postAgentEnable(Context ctx) {
        InvocationContext ictx = fromJavalinContext(ctx);
        JSONObject body = new JSONObject(ctx.body());
        String username = body.getString("username");
        String factory = body.getString("factory");
        JSONObject config = body.optJSONObject("config");
        datasetGraph.executeWrite(() -> {
            datasetGraph.enableAgent(username, factory, config, ictx);
        });
        ctx.json(Map.of("success", true));
    }

    private void postAgentDisable(Context ctx) {
        InvocationContext ictx = fromJavalinContext(ctx);
        JSONObject body = new JSONObject(ctx.body());
        String username = body.getString("username");
        datasetGraph.executeWrite(() -> {
            datasetGraph.disableAgent(username, ictx);
        });
        ctx.json(Map.of("success", true));
    }

    private void postSessionAdd(Context ctx) {
        InvocationContext ictx = fromJavalinContext(ctx);
        JSONObject body = new JSONObject(ctx.body());
        String sessionId = body.getString("sessionId");
        String agentUsername = body.getString("agentUsername");

        User principal = Txn.calculateRead(datasetGraph, () -> datasetGraph.getUser(ictx.getUserId(), ictx));
        Agent agent = Txn.calculateRead(datasetGraph, () -> datasetGraph.getUser(agentUsername, ictx).asAgent());

        Session session = datasetGraph.getAgentSessionManager().addSession(principal, sessionId, agent, datasetGraph, ictx);
        ctx.json(sessionToMap(session));
    }

    private void postSessionMessage(Context ctx) {
        InvocationContext ictx = fromJavalinContext(ctx);
        String agentUsername = ctx.pathParam("agentUsername");
        String sessionId = ctx.pathParam("sessionId");
        JSONObject body = new JSONObject(ctx.body());
        String messageText = body.getString("message");

        Session session = datasetGraph.getAgentSessionManager().getSession(sessionId, agentUsername, ictx);
        if (session == null) {
            ctx.status(404).json(Map.of("error", "Session not found"));
            return;
        }

        User principal = Txn.calculateRead(datasetGraph, () -> datasetGraph.getUser(ictx.getUserId(), ictx));
        Message msg = Message.plainText(principal, messageText);
        session.submit(msg);

        ctx.json(Map.of("success", true));
    }

    private void sessionStream(SseClient client) {

        Context ctx = client.ctx();
        InvocationContext ictx = fromJavalinContext(ctx);

        String agentUsername = ctx.pathParam("agentUsername");
        String sessionId = ctx.pathParam("sessionId");

        Session session
                = datasetGraph.getAgentSessionManager()
                        .getSession(sessionId, agentUsername, ictx);

        if (session == null) {
            client.close();
            return;
        }

        // initial replay
        for (Message m : session.getMessages()) {
            client.sendEvent("message", new JSONObject(messageToMap(m)).toString());
        }
        for (LogRecord r : session.getLogRecords()) {
            client.sendEvent("log", logRecordToJson(r).toString());
        }

        SessionListener listener = new SessionListener() {

            @Override
            public void onMessage(Session session, Message message) {
                client.sendEvent("message", new JSONObject(messageToMap(message)).toString());
            }

            @Override
            public void onMessageProcessingStarted(Session session, Message message) {
                client.sendEvent("started", new JSONObject(messageToMap(message)).toString());
            }

            @Override
            public void onMessageProcessingFinished(Session session, Message message) {
                client.sendEvent("finished", new JSONObject(messageToMap(message)).toString());
            }

            @Override
            public void onLog(Session session, LogRecord record) {
                client.sendEvent("log", logRecordToJson(record).toString());
            }

            @Override
            public void onError(Session session, Throwable error) {
                client.sendEvent("error",
                        new JSONObject()
                                .put("message", error.getMessage())
                                .put("stacktrace", ExceptionUtils.getStackTrace(error))
                                .toString()
                );
            }

            @Override
            public void onClosed(Session session) {
                client.sendEvent("closed", session.getSessionId());
                client.close();
            }
        };

        session.addListener(listener);

        client.onClose(() -> session.removeListener(listener));

        client.keepAlive();
    }

    private JSONObject logRecordToJson(LogRecord record) {

        JSONObject json = new JSONObject();

        json.put("sequenceNumber", record.getSequenceNumber());
        json.put("instant", record.getInstant().toString());
        json.put("millis", record.getMillis());

        json.put("level", record.getLevel().getName());
        json.put("loggerName", record.getLoggerName());

        json.put("message", record.getMessage());

        json.put("threadId", record.getLongThreadID());

        json.put("sourceClassName", record.getSourceClassName());
        json.put("sourceMethodName", record.getSourceMethodName());

        json.put("resourceBundleName", record.getResourceBundleName());

        if (record.getParameters() != null) {
            JSONArray params = new JSONArray();

            for (Object parameter : record.getParameters()) {
                params.put(parameter);
            }

            json.put("parameters", params);
        }

        if (record.getThrown() != null) {

            Throwable t = record.getThrown();

            JSONObject thrown = new JSONObject();

            thrown.put("type", t.getClass().getName());
            thrown.put("message", t.getMessage());
            thrown.put("stackTrace", ExceptionUtils.getStackTrace(t));

            json.put("thrown", thrown);
        }

        return json;
    }

    private Map<String, Object> messageToMap(Message m) {
        return Map.of(
                "sender", m.sender().toMap(),
                "timestamp", m.timestamp().toString(),
                "content", m.content(),
                "contentType", m.contentType()
        );
    }

    private Map<String, Object> userToMap(User user) {
        return user.toMap();
    }

    private void getSessionList(Context ctx) {
        InvocationContext ictx = fromJavalinContext(ctx);
        List<Session> sessions = datasetGraph.getAgentSessionManager().listSessions(ictx);
        ctx.json(Map.of("sessions", sessions.stream().map(this::sessionToMap).toList()));
    }

    private void getSessionGet(Context ctx) {
        InvocationContext ictx = fromJavalinContext(ctx);
        String agentUsername = ctx.pathParam("agentUsername");
        String sessionId = ctx.pathParam("sessionId");

        Session session = datasetGraph.getAgentSessionManager().getSession(sessionId, agentUsername, ictx);
        if (session == null) {
            ctx.status(404).json(Map.of("error", "Session not found"));
            return;
        }
        ctx.json(sessionToMap(session));
    }

    private void postSessionRemove(Context ctx) {
        InvocationContext ictx = fromJavalinContext(ctx);
        String agentUsername = ctx.pathParam("agentUsername");
        String sessionId = ctx.pathParam("sessionId");

        datasetGraph.getAgentSessionManager().removeSession(sessionId, agentUsername, ictx);
        ctx.json(Map.of("success", true));
    }

    private Map<String, Object> sessionToMap(Session session) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message m : session.getMessages()) {
            messages.add(messageToMap(m));
        }
        return Map.of(
                "sessionId", session.getSessionId(),
                "agentUsername", session.getAgent().getUsername(),
                "agentFactory", session.getAgent().getFactory(),
                "principalId", session.getPrincipal().getId(),
                "principalUsername", session.getPrincipal().getUsername(),
                "title", session.getTitle(),
                "expiresAt", session.getExpiresAt().toString(),
                "messages", messages
        );
    }

    private void putSessionTitle(Context ctx) {
        InvocationContext ictx = fromJavalinContext(ctx);
        String agentUsername = ctx.pathParam("agentUsername");
        String sessionId = ctx.pathParam("sessionId");
        String title = ctx.body();

        Session session = datasetGraph.getAgentSessionManager().getSession(sessionId, agentUsername, ictx);
        if (session == null) {
            ctx.status(404).json(Map.of("error", "Session not found"));
            return;
        }
        session.setTitle(title);
        ctx.json(Map.of("success", true));
    }

    //---------------------------------------
    //auth
    private void postRegister(Context ctx) {
        String firstname = ctx.formParam("firstname");
        String lastname = ctx.formParam("lastname");
        String email = ctx.formParam("email");
        String username = ctx.formParam("username");

        // Basic validation
        if (firstname == null || lastname == null || email == null || username == null
                || firstname.isBlank() || lastname.isBlank() || email.isBlank() || username.isBlank()) {

            ctx.status(400);
            ctx.json(Map.of(
                    "success", false,
                    "error", "Missing required form fields"
            ));
            return;
        }

        try {

            //check if user exists
            User user = Txn.calculateRead(datasetGraph, () -> {
                try {
                    return datasetGraph.getUser(username, InvocationContext.EMPTY);
                } catch (IllegalStateException e) {
                    return null;
                }
            });

            if (user != null) {
                ctx.status(400);
                ctx.json(Map.of(
                        "success", false,
                        "error", "Username already exists"
                ));
                return;
            }

            String generatedPassword = Txn.calculateWrite(datasetGraph, () -> {
                return datasetGraph.addUser(
                        firstname,
                        lastname,
                        email,
                        username,
                        InvocationContext.EMPTY
                );
            });

            ctx.status(201);
            ctx.json(Map.of(
                    "success", true,
                    "username", username,
                    "generatedPassword", generatedPassword
            ));

        } catch (Exception e) {
            e.printStackTrace();
            ctx.status(500);
            ctx.json(Map.of(
                    "success", false,
                    "error", "Failed to register user"
            ));
        }
    }

    private void postToken(Context ctx) {
        String username = ctx.formParam("username");
        String password = ctx.formParam("password");

        User user;
        try {
            user = Txn.calculateRead(datasetGraph, () -> {
                return datasetGraph.getUser(username, InvocationContext.EMPTY);
            });

        } catch (IllegalStateException ex) {
            ctx.status(401);
            ctx.json(Map.of("successful", false));
            return;
        }

        if (!BCrypt.checkpw(password, user.getHashedPassword())) {
            ctx.status(401);
            ctx.json(Map.of("successful", false));
            return;
        }

        String jwt = JwtUtil.createToken(user);

        Cookie cookie = new Cookie("access_token", jwt);
        cookie.setHttpOnly(true);
        cookie.setPath("/"); // send on every request
        cookie.setMaxAge(60 * 60 * 24); // 1 hour
        cookie.setSecure(ctx.req().isSecure());
        ctx.cookie(cookie);

        ctx.json(Map.of(
                "access_token", jwt,
                "successful", true,
                "redirect", getConfig().getLandingpage()
        ));
    }

    private void postLogout(Context ctx) {
        Cookie cookie = new Cookie("access_token", "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0); // delete cookie immediately
        ctx.cookie(cookie);

        //TODO later keep a revoke token list in sqlite
        ctx.json(Map.of(
                "successful", true,
                "redirect", "/"
        ));
    }

    private void getAuthMe(Context ctx) {
        InvocationContext ictx = fromJavalinContext(ctx);

        User user = Txn.calculateRead(datasetGraph, () -> {
            return datasetGraph.getUser(ctx.attribute("user.username"), ictx);
        });

        ctx.json(user.toMap());
    }

    private void putPassword(Context ctx) {
        String username = ctx.attribute("user.username");

        try {
            JSONObject body = new JSONObject(ctx.body());

            String oldPassword = body.getString("oldPassword");
            String newPassword = body.getString("newPassword");

            Txn.executeWrite(datasetGraph, () -> {
                datasetGraph.changePassword(username, oldPassword, newPassword);
            });

            ctx.status(204);

        } catch (JSONException e) {
            ctx.status(400).result("Request must contain 'oldPassword' and 'newPassword'.");
        } catch (IllegalArgumentException e) {
            ctx.status(400).result(e.getMessage());
        } catch (SecurityException e) {
            ctx.status(401).result(e.getMessage());
        } catch (Exception e) {
            ctx.status(500).result("Failed to change password.");
        }
    }

    private void authorizationMiddleware(Context ctx) {
        // Allow static files without auth
        String path = ctx.path();
        // Skip JWT auth for public static paths
        if (path.equals("/")
                || path.equals("/about")
                || path.equals("/favicon.ico")
                || path.equals("/auth/token")
                || path.equals("/auth/register")
                || path.startsWith("/app")) {
            return;
        }

        String token = getToken(ctx);

        try {
            JSONObject payload = JwtUtil.validateToken(token);

            // -------------------------
            // 3. Put payload into ctx.attribute
            // -------------------------
            for (String key : payload.keySet()) {
                Object value = payload.get(key);
                ctx.attribute("user." + key, value);
            }

        } catch (JWTVerificationException e) {
            throw new UnauthorizedResponse("Unauthorized: " + e.getMessage());
        }
    }

    private String getToken(Context ctx) {
        String token = null;

        // -------------------------
        // 1. Authorization header
        // -------------------------
        String auth = ctx.header("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            token = auth.substring("Bearer ".length());
        }

        // -------------------------
        // 2. Fallback to cookie
        // -------------------------
        if (token == null) {
            token = ctx.cookie("access_token");
        }

        if (token == null || token.isEmpty()) {
            throw new UnauthorizedResponse("Missing token");
        }

        return token;
    }

    public AticConfig getConfig() {
        return config;
    }

    /*package*/ static InvocationContext fromJavalinContext(Context ctx) {
        InvocationContext.Builder builder = new InvocationContext.Builder();

        int userId = ctx.attribute("user.id");
        builder.userId(userId);

        int primaryGroupId = ctx.attribute("user.primaryGroupId");
        builder.primaryGroupId(primaryGroupId);

        List<Map> groups = ctx.attribute("user.groups");
        for (Map group : groups) {
            builder.addGroupId((int) group.get("id"));
        }

        return builder.build();
    }

    /*package*/ static void transferContext(io.javalin.http.Context javalinContext, org.apache.jena.sparql.util.Context jenaContext) {
        int userId = javalinContext.attribute("user.id");
        jenaContext.put(InvocationContext.USER_ID, userId);

        int primaryGroupId = javalinContext.attribute("user.primaryGroupId");
        jenaContext.put(InvocationContext.PRIMARY_GROUP_ID, primaryGroupId);

        Set<Integer> groupIds = new HashSet<>();
        List<Map> groups = javalinContext.attribute("user.groups");
        for (Map group : groups) {
            groupIds.add((int) group.get("id"));
        }
        jenaContext.put(InvocationContext.GROUP_IDS, groupIds);
    }

    /*package*/ static void transferContext(InvocationContext ictx, org.apache.jena.sparql.util.Context jenaContext) {
        ictx.transferContext(jenaContext);
    }

    /*package*/ SqliteAticDatasetGraph getDatasetGraph() {
        return datasetGraph;
    }

    /*package*/ File getDataFolder() {
        return dataFolder;
    }

    /*package*/ File getLogsFolder() {
        return logsFolder;
    }

    /*package*/ File getCdceFolder() {
        return cdceFolder;
    }

    /*package*/ File getPatchesFolder() {
        return patchesFolder;
    }

    /*package*/ MoleculeEndpoint getMoleculeEndpoint() {
        return moleculeEndpoint;
    }

    /*package*/ RDFPatchWriter getRdfPatchWriter() {
        return rdfPatchWriter;
    }

}
