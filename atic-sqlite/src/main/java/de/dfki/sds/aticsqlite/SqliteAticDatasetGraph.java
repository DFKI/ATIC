package de.dfki.sds.aticsqlite;

import burp.model.TriplesMap;
import burp.parse.Parse;
import de.dfki.sds.atic.ac.Agent;
import de.dfki.sds.atic.ac.Group;
import de.dfki.sds.atic.ac.Permission;
import de.dfki.sds.atic.ac.PermissionDeniedException;
import de.dfki.sds.atic.ac.Principal;
import de.dfki.sds.atic.ac.PrincipalPermission;
import de.dfki.sds.atic.ac.SharingManagement;
import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.agent.Message;
import de.dfki.sds.atic.agent.RdfNodesAttachment;
import de.dfki.sds.atic.agent.Session;
import de.dfki.sds.atic.api.IdAndUri;
import de.dfki.sds.atic.jenatic.AticDatasetGraph;
import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.AticVirtualGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.agent.AgentSessionManager;
import edu.lehigh.swat.bench.uba.Generator;
import edu.lehigh.swat.bench.uba.StreamRDFWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.TxnType;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.changes.RDFChangesBase;
import org.apache.jena.riot.system.PrefixMap;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.shared.AddDeniedException;
import org.apache.jena.shared.DeleteDeniedException;
import org.apache.jena.shared.Lock;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.function.FunctionFactory;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.sparql.util.Symbol;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.util.iterator.NiceIterator;
import org.apache.jena.vocabulary.DCTerms;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mindrot.jbcrypt.BCrypt;

/**
 * A SQLite-backed implementation of the {@link AticDatasetGraph} interface that provides a virtual dataset graph over a SQLite database. It supports named
 * graphs, virtual graphs, access control via the ATiC authorization core, user and group management, RDF patch emission, and transaction management.
 */
public class SqliteAticDatasetGraph implements AticDatasetGraph, UserGroupManagement, SharingManagement {

    /**
     * Symbol key storing the absolute path of the SQLite database folder in the Jena {@link Context}.
     */
    public static final Symbol ATIC_LOCATION = Symbol.create("atic.location");

    //to simulate TDB2's Symbol
    /**
     * Symbol to use the union of named graphs as the default graph of a query.
     */
    //public static final Symbol UNION_DEFAULT_GRAPH = Symbol.create("http://jena.apache.org/TDB#unionDefaultGraph");
    /**
     * The Jena {@link Context} holding configuration symbols for this dataset graph instance.
     */
    private Context context;

    /**
     * The underlying {@link Database} wrapper providing SQLite read/write operations.
     */
    private Database db;

    /**
     * A {@link SqlitePrefixMap} instance managing RDF prefix mappings stored in the database.
     */
    private SqlitePrefixMap sqlitePrefixMap;

    /**
     * Maps virtual graph URIs to their in-memory {@link AticVirtualGraph} implementations.
     */
    private Map<Node, AticVirtualGraph> virtualGraphMap;

    /**
     * Maps blank nodes to their assigned URIs during a transaction for consistency.
     */
    private Map<Node, String> bnode2uri;

    /**
     * Caches {@link SqliteAticGraph} instances keyed by their graph ID/URI set.
     */
    private Map<Set<IdAndUri>, SqliteAticGraph> graphMap;

    /**
     * Flag indicating whether this dataset graph has been closed.
     */
    private boolean closed;

    /**
     * Emits RDF patch events (add/delete) for listeners when data changes.
     */
    private RDFPatchEmitterTransactional rdfPatchEmitter;

    private AgentSessionManager agentSessionManager;

    /**
     * The admin {@link User} object, loaded during bootstrap.
     */
    private User adminUser;

    /**
     * Dataset capabilities configuration (rdf-star support, etc.).
     */
    private Capabilities capabilities;

    /**
     * Capabilities configuration for this dataset graph instance.
     */
    public record Capabilities(boolean rdfStarEnabled) {

        public static final Capabilities DEFAULT = new Capabilities(true);
    }

    public SqliteAticDatasetGraph(Database db) {
        this(db, null);
    }

    public SqliteAticDatasetGraph(Database db, RDFPatchListener mainListener) {
        this(db, mainListener, Capabilities.DEFAULT);
    }

    public SqliteAticDatasetGraph(Database db, RDFPatchListener mainListener, Capabilities capabilities) {
        this.db = db;
        this.sqlitePrefixMap = new SqlitePrefixMap(this);
        this.context = new Context();
        this.context.put(ATIC_LOCATION, db.getFolder().getAbsolutePath());
        this.virtualGraphMap = new HashMap<>();
        this.bnode2uri = new HashMap<>();
        this.graphMap = new HashMap<>();
        this.rdfPatchEmitter = new RDFPatchEmitterTransactional();
        this.agentSessionManager = new AgentSessionManager();
        this.capabilities = capabilities;
        if (mainListener != null) {
            this.rdfPatchEmitter.addListener(mainListener);
        }
        bootstrap();
    }

    public Capabilities getCapabilities() {
        return capabilities;
    }

    //bootstrap ==============================================================================
    /**
     * Orchestrates the full bootstrap sequence: creates database tables, initializes the admin user and everyone group, creates the default graph, loads
     * virtual graphs, and registers SPARQL functions.
     */
    private void bootstrap() {
        this.executeWrite(() -> {
            try {
                bootstrapTables();
            } catch (SQLException ex) {
                throw new RuntimeException("Failed to bootstrap tables", ex);
            }
        });

        this.executeWrite(() -> {
            try {
                bootstrapResourceAclEffective();
            } catch (SQLException ex) {
                throw new RuntimeException("Failed to rebuild effective resource ACLs", ex);
            }
        });

        this.executeWrite(() -> {
            try {
                bootstrapEveryoneGroup();
                bootstrapAdmin();
                bootstrapDefaultGraph();
            } catch (SQLException ex) {
                throw new RuntimeException("Failed to bootstrap dataset graph", ex);
            }
        });

        this.executeRead(() -> {
            try {
                bootstrapVirtualGraphs();
            } catch (SQLException ex) {
                throw new RuntimeException("Failed to bootstrap virtual graphs", ex);
            }
        });

        bootstrapSparqlFunctions();

        this.executeWrite(() -> {
            try {
                bootstrapIndices();
            } catch (SQLException ex) {
                throw new RuntimeException("Failed to bootstrap indices", ex);
            }
        });
    }

    /**
     * Creates all SQLite tables required for users, groups, graphs, ACLs, resources, properties, and triple storage (SPoG, SPtG, RDF-star).
     */
    private void bootstrapTables() throws SQLException {
        db.writeQuery("create_table_user.sql");
        db.writeQuery("create_table_group.sql");
        db.writeQuery("create_table_user_group_assignment.sql");

        db.writeQuery("create_table_graph.sql");
        db.writeQuery("create_table_graph_acl.sql");

        db.writeQuery("create_table_prefixmap.sql");

        db.writeQuery("create_table_resource.sql");
        db.writeQuery("create_table_resource_uri.sql");
        db.writeQuery("create_table_resource_acl.sql");
        db.writeQuery("create_table_resource_acl_effective.sql");
        db.writeQuery("create_table_property.sql");

        db.writeQuery("create_table_spog.sql");
        db.writeQuery("create_table_splg.sql");

        //rdf-star
        db.writeQuery("create_table_resource_spo.sql");
        db.writeQuery("create_table_resource_spl.sql");

        //triggers
        db.writeQuery("create_trigger_resource_acl_ad.sql");
        db.writeQuery("create_trigger_resource_acl_ai.sql");
        db.writeQuery("create_trigger_resource_acl_au.sql");
        db.writeQuery("create_trigger_user_group_assignment_ad.sql");
        db.writeQuery("create_trigger_user_group_assignment_ai.sql");
    }

    /**
     * Rebuilds the materialized resource ACL cache if it has not been initialized yet. This is only needed when upgrading an existing database that already
     * contains resource_acl entries but predates the resource_acl_effective table.
     */
    private void bootstrapResourceAclEffective() throws SQLException {
        db.writeQuery("rebuild_resource_acl_effective.sql");
    }

    /**
     * Checks for and creates the admin user if it does not already exist, then stores a reference to it.
     */
    private void bootstrapAdmin() throws SQLException {
        boolean adminExists = db.read(
                "SELECT EXISTS(SELECT 1 FROM user WHERE username=?)",
                rs -> {
                    rs.next();
                    return rs.getInt(1) == 1;
                },
                UserGroupManagement.ADMIN_USERNAME
        );

        if (!adminExists) {
            //admin password is written to file passwords.json.generated
            addUser("Atic", "Admin", "", UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        }

        adminUser = getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
    }

    /**
     * Creates the {@code everyone} group if it does not already exist.
     */
    private void bootstrapEveryoneGroup() throws SQLException {

        boolean groupExists = db.read(
                "SELECT EXISTS(SELECT 1 FROM \"group\" WHERE groupname=?)",
                rs -> {
                    rs.next();
                    return rs.getInt(1) == 1;
                },
                UserGroupManagement.EVERYONE_GROUP
        );

        if (!groupExists) {
            addGroup(EVERYONE_GROUP, InvocationContext.EMPTY);
        }
    }

    /**
     * Creates the Jena default graph with ADMIN permission for the admin user and EDIT permission for the everyone group.
     */
    private void bootstrapDefaultGraph() throws SQLException {

        // Jena default graph URI
        String graphUri = Quad.defaultGraphIRI.getURI();

        // check if graph already exists
        Long graphId = db.read(
                "SELECT id FROM graph WHERE uri = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                graphUri
        );

        if (graphId == null) {

            // get admin user
            User admin = getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
            if (admin == null) {
                throw new IllegalStateException("Admin user must exist before bootstrapping default graph");
            }

            InvocationContext ictx = new InvocationContext.Builder().fromUser(admin).build();

            // insert graph
            graphId = db.writeReturningId(
                    "INSERT INTO graph(uri, creator) VALUES (?, ?)",
                    graphUri,
                    admin.getId()
            );

            //admin but also everyone gets ADMIN rights on default graph 
            for (String groupName : Arrays.asList(admin.getUsername(), EVERYONE_GROUP)) {
                IdAndUri group = db.read(
                        "SELECT id, uri FROM \"group\" WHERE groupname = ?",
                        rs -> rs.next()
                        ? new IdAndUri(rs.getLong("id"), rs.getString("uri"))
                        : null,
                        groupName
                );

                if (group == null) {
                    throw new IllegalStateException(groupName + " group missing");
                }

                Permission permission = groupName.equals(admin.getUsername()) ? Permission.ADMIN : Permission.EDIT;

                // grant admin permission
                db.write(
                        "INSERT INTO graph_acl(group_id, graph_id, permission, granted_by_group_id) VALUES (?, ?, ?, ?)",
                        group.getId(),
                        graphId,
                        permission.getCode(),
                        admin.getPrimaryGroup().getId()
                );

                rdfPatchEmitter.shareGraph(
                        IdAndUri.create(graphId, graphUri),
                        group,
                        permission,
                        ictx
                );
            }
        }

    }

    /**
     * Loads and executes index creation SQL definitions from {@code indices.json} in the classpath resources.
     */
    private void bootstrapIndices() throws SQLException {

        // load json from resource file
        String json;
        try (InputStream in = getClass().getResourceAsStream("/de/dfki/sds/aticsqlite/sql/indices.json")) {
            if (in == null) {
                throw new RuntimeException("Failed to find indices.json in resources");
            }
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

        // parse JSON array
        JSONArray arr = new JSONArray(json);

        // loop through definitions
        for (int i = 0; i < arr.length(); i++) {
            JSONObject idx = arr.getJSONObject(i);
            String sql = idx.getString("sql");
            db.write(sql);
        }
    }

    /**
     * Registers the {@code https://comem.ai/atic/fn#createURN} SPARQL function factory.
     */
    private void bootstrapSparqlFunctions() {
        SparqlFunctionCreateURN f = new SparqlFunctionCreateURN(this);

        SqliteAticDatasetGraph dg = this;

        FunctionFactory factory = new FunctionFactory() {
            @Override
            public org.apache.jena.sparql.function.Function create(String uri) {
                return new SparqlFunctionCreateURN(dg);
            }
        };

        org.apache.jena.sparql.function.FunctionRegistry.get().put(
                "https://comem.ai/atic/fn#createURN", factory
        );
    }

    /**
     * Loads all virtual graphs marked with {@code is_virtual = 1} from the {@code graph} table and registers them in {@code virtualGraphMap}.
     */
    private void bootstrapVirtualGraphs() throws SQLException {
        db.read(
                """
                SELECT uri, virtual_factory, virtual_config
                FROM graph
                WHERE is_virtual = 1
                """,
                rs -> {

                    while (rs.next()) {

                        String uri = rs.getString("uri");
                        String factory = rs.getString("virtual_factory");
                        String configStr = rs.getString("virtual_config");

                        JSONObject config = configStr == null
                                ? new JSONObject()
                                : new JSONObject(configStr);

                        AticVirtualGraph graph;
                        try {
                            graph = loadVirtualGraph(uri, factory, config);
                        } catch (Exception e) {
                            //TODO better logging
                            System.err.println("Could not load virtual graph: " + factory + " with config " + config);
                            continue;
                        }

                        Node graphNode = NodeFactory.createURI(uri);

                        virtualGraphMap.put(graphNode, graph);
                    }

                    return null;
                }
        );
    }

    /**
     * Generates a URN of the form {@code urn:atic:{type}-{UUID}} for creating unique resource identifiers.
     *
     * @param type the type prefix for the URN (e.g., "user", "group", "graph")
     * @return the generated URN string
     */
    /*package*/ String createURN(String type) {
        return "urn:atic:" + type + "-" + UUID.randomUUID();
    }

    //query log =============================================================
    /**
     * Enables the SQLite query logger, writing all queries to the specified file path.
     *
     * @param dbFilePath the path to the query log file
     */
    public void enableQueryLogger(String dbFilePath, InvocationContext ctx) {
        requireAdmin(ctx);
        db.enableQueryLogger(dbFilePath);
    }

    /**
     * Disables the SQLite query logger.
     */
    public void disableQueryLogger(InvocationContext ctx) {
        requireAdmin(ctx);
        db.disableQueryLogger();
    }

    //user & group management ================================================================
    /**
     * Creates a new user with a randomly generated 8-character password. The user is automatically assigned to the {@code everyone} group. The password is
     * appended to {@code passwords.json.generated}. Requires ADMIN permission.
     *
     * @param firstname the user's first name
     * @param lastname the user's last name
     * @param email the user's email address
     * @param username the user's login username
     * @param ctx the invocation context containing caller information
     * @return the randomly generated plain-text password
     */
    @Override
    public String addUser(String firstname, String lastname, String email, String username, InvocationContext ctx) {
        requireAdmin(ctx);

        String plainPassword = generateRandomPassword(8);

        try {
            String userUri = createURN("user");
            String hashed = BCrypt.hashpw(plainPassword, BCrypt.gensalt());

            long userId = db.writeReturningId(
                    """
                    INSERT INTO user (username, uri, password, firstname, lastname, email)
                    VALUES (?, ?, ?, ?, ?, ?);
                    """,
                    username,
                    userUri,
                    hashed,
                    firstname,
                    lastname,
                    email
            );

            String groupUri = createURN("group");

            long groupId = db.writeReturningId(
                    """
                    INSERT INTO "group" (groupname, user_id, uri)
                    VALUES (?, ?, ?);
                    """,
                    username,
                    userId,
                    groupUri
            );

            rdfPatchEmitter.addUser(IdAndUri.create(userId, userUri), IdAndUri.create(groupId, groupUri), ctx);

            //every user is assigned automatically to everyone group
            assignUserToGroup(username, EVERYONE_GROUP, ctx);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to add user: " + username, e);
        }

        appendToPasswordsFile(username, plainPassword);

        return plainPassword;
    }

    /**
     * Creates a new named group with a generated URN. Requires ADMIN permission.
     *
     * @param groupname the name of the group to create
     * @param ctx the invocation context containing caller information
     */
    @Override
    public void addGroup(String groupname, InvocationContext ctx) {

        requireAdmin(ctx);

        String groupUri = createURN("group");

        try {

            long groupId = db.writeReturningId(
                    "INSERT INTO \"group\"(groupname, uri) VALUES (?, ?)",
                    groupname,
                    groupUri
            );

            rdfPatchEmitter.addGroup(IdAndUri.create(groupId, groupUri), ctx);

        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to create group: " + groupname,
                    e
            );
        }
    }

    /**
     * Loads a {@link User} object by username, including their primary group and all assigned groups.
     *
     * @param username the username to look up
     * @param ctx the invocation context containing caller information
     * @return the {@link User} object
     */
    @Override
    public User getUser(String username, InvocationContext ctx) {

        String sql = """
        SELECT
            u.id        AS user_id,
            u.uri       AS user_uri,
            u.username,
            u.password,
            u.firstname,
            u.lastname,
            u.email,
            u.is_agent,
            u.agent_factory,
            u.agent_config,
            g.id        AS primary_group_id,
            g.groupname AS primary_group_name,
            g.uri       AS primary_group_uri
        FROM user u
        LEFT JOIN "group" g ON g.user_id = u.id
        WHERE u.username = ?
        """;

        try {
            return db.read(sql, rs -> mapUser(rs, username), username);
        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to load user: " + username,
                    e
            );
        }
    }

    /**
     * Loads a {@link User} object by numeric ID, including their primary group and all assigned groups.
     *
     * @param userId the user ID to look up
     * @param ctx the invocation context containing caller information
     * @return the {@link User} object
     */
    @Override
    public User getUser(int userId, InvocationContext ctx) {

        String sql = """
        SELECT
            u.id        AS user_id,
            u.uri       AS user_uri,
            u.username,
            u.password,
            u.firstname,
            u.lastname,
            u.email,
            u.is_agent,
            u.agent_factory,
            u.agent_config,
            g.id        AS primary_group_id,
            g.groupname AS primary_group_name,
            g.uri       AS primary_group_uri
        FROM user u
        LEFT JOIN "group" g ON g.user_id = u.id
        WHERE u.id = ?
        """;

        try {
            return db.read(sql, rs -> mapUser(rs, userId), userId);
        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to load user: " + userId,
                    e
            );
        }
    }

    /**
     * Loads a {@link Group} object by its group name.
     *
     * @param groupname the name of the group to look up
     * @param ctx the invocation context containing caller information
     * @return the {@link Group} object
     */
    @Override
    public Group getGroup(String groupname, InvocationContext ctx) {

        try {
            return db.read(
                    "SELECT id, uri, groupname FROM \"group\" WHERE groupname = ?",
                    rs -> {
                        if (rs.next()) {
                            return new Group(
                                    rs.getInt("id"),
                                    rs.getString("uri"),
                                    rs.getString("groupname")
                            );
                        }
                        throw new IllegalStateException("Group not found: " + groupname);
                    },
                    groupname
            );

        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to fetch group: " + groupname,
                    e
            );
        }
    }

    /**
     * Assigns a user to a group via the {@code user_group_assignment} table. Requires ADMIN permission.
     *
     * @param username the username of the user to assign
     * @param groupname the name of the group to assign to
     * @param ctx the invocation context containing caller information
     */
    @Override
    public void assignUserToGroup(String username, String groupname, InvocationContext ctx) {
        requireAdmin(ctx);

        try {

            IdAndUri user = db.read(
                    "SELECT id, uri FROM user WHERE username = ?",
                    rs -> rs.next() ? new IdAndUri(rs.getLong("id"), rs.getString("uri")) : null,
                    username
            );

            if (user == null) {
                throw new RuntimeException("User not found: " + username);
            }

            IdAndUri group = db.read(
                    "SELECT id, uri FROM \"group\" WHERE groupname = ?",
                    rs -> rs.next() ? new IdAndUri(rs.getLong("id"), rs.getString("uri")) : null,
                    groupname
            );

            if (group == null) {
                throw new RuntimeException("Group not found: " + groupname);
            }

            rdfPatchEmitter.assignUserToGroup(user, group, ctx);

            db.write(
                    """
                INSERT OR IGNORE INTO user_group_assignment (user_id, group_id)
                VALUES (?, ?)
                """,
                    user.getId(),
                    group.getId()
            );

        } catch (SQLException e) {
            throw new RuntimeException("Failed to assign user to group: " + username + " -> " + groupname, e);
        }
    }

    /**
     * Removes a user's assignment from a group. Requires ADMIN permission.
     *
     * @param username the username of the user to unassign
     * @param groupname the name of the group to unassign from
     * @param ctx the invocation context containing caller information
     */
    @Override
    public void unassignUserFromGroup(String username, String groupname, InvocationContext ctx) {
        requireAdmin(ctx);

        try {

            //for emitting the patch
            IdAndUri user = db.read(
                    "SELECT id, uri FROM user WHERE username = ?",
                    rs -> rs.next() ? new IdAndUri(rs.getLong("id"), rs.getString("uri")) : null,
                    username
            );
            if (user == null) {
                throw new RuntimeException("User not found: " + username);
            }
            IdAndUri group = db.read(
                    "SELECT id, uri FROM \"group\" WHERE groupname = ?",
                    rs -> rs.next() ? new IdAndUri(rs.getLong("id"), rs.getString("uri")) : null,
                    groupname
            );
            if (group == null) {
                throw new RuntimeException("Group not found: " + groupname);
            }

            rdfPatchEmitter.unassignUserFromGroup(user, group, ctx);

            //the delete operation
            db.write(
                    """
                DELETE FROM user_group_assignment
                WHERE user_id = (SELECT id FROM user WHERE username = ?)
                AND group_id = (SELECT id FROM "group" WHERE groupname = ?)
                """,
                    username,
                    groupname
            );

        } catch (SQLException e) {
            throw new RuntimeException("Failed to unassign user from group: " + username + " -> " + groupname, e);
        }
    }

    /**
     * Maps a {@link java.sql.ResultSet} row to a {@link User} object, including loading all groups the user belongs to.
     *
     * @param rs the result set to map
     * @param id the user identifier
     * @return the mapped {@link User} object
     * @throws SQLException if a database error occurs
     */
    private User mapUser(java.sql.ResultSet rs, Object id) throws SQLException {

        if (!rs.next()) {
            throw new IllegalStateException("User not found: " + id);
        }

        int userId = rs.getInt("user_id");

        Group primaryGroup = null;

        int pgId = rs.getInt("primary_group_id");
        if (!rs.wasNull()) {
            primaryGroup = new Group(
                    pgId,
                    rs.getString("primary_group_uri"),
                    rs.getString("primary_group_name")
            );
        }

        List<Group> groups = loadUserGroups(userId);

        // ensure primary group is included
        final Group finalPrimaryGroup = primaryGroup;
        if (primaryGroup != null
                && groups.stream().noneMatch(g -> g.getId() == finalPrimaryGroup.getId())) {
            groups.add(primaryGroup);
        }

        boolean isAgent = rs.getBoolean("is_agent");

        if (isAgent) {

            return new Agent(
                    userId,
                    rs.getString("user_uri"),
                    rs.getString("username"),
                    primaryGroup,
                    groups,
                    rs.getString("firstname"),
                    rs.getString("lastname"),
                    rs.getString("email"),
                    rs.getString("password"),
                    rs.getString("agent_factory"),
                    rs.getString("agent_config")
            );
        }

        return new User(
                userId,
                rs.getString("user_uri"),
                rs.getString("username"),
                primaryGroup,
                groups,
                rs.getString("firstname"),
                rs.getString("lastname"),
                rs.getString("email"),
                rs.getString("password")
        );
    }

    /**
     * Searches for users whose username, first name, or last name contains the given query string (case-insensitive).
     *
     * @param query the search query string
     * @param ctx the invocation context containing caller information
     * @return a list of matching {@link User} objects
     */
    public List<User> searchUsers(String query, InvocationContext ctx) {

        String searchTerm = "%" + query.toLowerCase() + "%";

        String sql
                = """
            SELECT
                u.id        AS user_id,
                u.uri       AS user_uri,
                u.username,
                u.password,
                u.firstname,
                u.lastname,
                u.email,
                g.id        AS primary_group_id,
                g.groupname AS primary_group_name,
                g.uri       AS primary_group_uri
            FROM user u
            LEFT JOIN "group" g ON g.user_id = u.id
            WHERE LOWER(u.username) LIKE ?
               OR LOWER(u.firstname) LIKE ?
               OR LOWER(u.lastname) LIKE ?
            ORDER BY u.username
            """;

        try {

            List<User> users = db.read(
                    sql,
                    rs -> {

                        List<User> results = new ArrayList<>();

                        while (rs.next()) {

                            int userId = rs.getInt("user_id");

                            Group primaryGroup = null;

                            int pgId = rs.getInt("primary_group_id");
                            if (!rs.wasNull()) {
                                primaryGroup = new Group(
                                        pgId,
                                        rs.getString("primary_group_uri"),
                                        rs.getString("primary_group_name")
                                );
                            }

                            results.add(new User(
                                    userId,
                                    rs.getString("user_uri"),
                                    rs.getString("username"),
                                    primaryGroup,
                                    new ArrayList<>(), // TODO groups fill later
                                    rs.getString("firstname"),
                                    rs.getString("lastname"),
                                    rs.getString("email"),
                                    rs.getString("password")
                            ));
                        }

                        return results;
                    },
                    searchTerm, searchTerm, searchTerm
            );

            return users;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to search for users with query: " + query, e);
        }
    }

    /**
     * Searches for named groups (excluding user-specific groups) whose name contains the given query string.
     *
     * @param query the search query string
     * @param ctx the invocation context containing caller information
     * @return a list of matching {@link Group} objects
     */
    public List<Group> searchGroups(String query, InvocationContext ctx) {

        String searchTerm = "%" + query.toLowerCase() + "%";

        String sql = """
        SELECT
            g.id,
            g.uri,
            g.groupname
        FROM "group" g
        WHERE LOWER(g.groupname) LIKE ?
          AND g.user_id IS NULL
        ORDER BY g.groupname
        """;

        try {

            List<Group> groups = db.read(
                    sql,
                    rs -> {

                        List<Group> results = new ArrayList<>();

                        while (rs.next()) {
                            results.add(new Group(
                                    rs.getInt("id"),
                                    rs.getString("uri"),
                                    rs.getString("groupname")
                            ));
                        }

                        return results;
                    },
                    searchTerm
            );

            return groups;

        } catch (SQLException e) {
            throw new RuntimeException("Failed to search for groups with query: " + query, e);
        }
    }

    /**
     * Searches both users and groups for principals whose names match the given query.
     *
     * @param query the search query string
     * @param ctx the invocation context containing caller information
     * @return a list of matching {@link Principal} objects
     */
    public List<Principal> searchPrincipals(String query, InvocationContext ctx) {
        List<Principal> pricipals = new ArrayList<>();
        pricipals.addAll(searchUsers(query, ctx));
        pricipals.addAll(searchGroups(query, ctx));
        return pricipals;
    }

    /**
     * Loads all groups assigned to a specific user from the {@code user_group_assignment} table.
     *
     * @param userId the user ID
     * @return a list of {@link Group} objects
     */
    private List<Group> loadUserGroups(long userId) throws SQLException {

        return db.read(
                """
            SELECT g.id, g.uri, g.groupname
            FROM user_group_assignment uga
            JOIN "group" g ON g.id = uga.group_id
            WHERE uga.user_id = ?
            """,
                rs -> {
                    List<Group> groups = new ArrayList<>();
                    while (rs.next()) {
                        groups.add(new Group(
                                rs.getInt("id"),
                                rs.getString("uri"),
                                rs.getString("groupname")
                        ));
                    }

                    return groups;
                },
                userId
        );
    }

    /**
     * Writes or updates a username-password pair in the {@code passwords.json.generated} file.
     *
     * @param username the username
     * @param plainPassword the plain-text password
     */
    private void appendToPasswordsFile(String username, String plainPassword) {
        JSONObject json;

        // Step 1: Load existing file if it exists
        File passwordsFile = new File(db.getFolder(), "passwords.json.generated");

        if (passwordsFile.exists()) {
            try {
                String content = FileUtils.readFileToString(passwordsFile, StandardCharsets.UTF_8);
                json = new JSONObject(content);
            } catch (Exception e) {
                // If file is corrupted or empty, start fresh
                json = new JSONObject();
            }
        } else {
            json = new JSONObject();
        }

        // Step 2: Add/update entry
        json.put(username, plainPassword);

        // Step 3: Write back to file
        try {
            FileUtils.writeStringToFile(passwordsFile, json.toString(4), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            // ignore or log if needed
        }
    }

    /**
     * Generates a random password consisting of uppercase and lowercase letters of the specified length.
     *
     * @param length the desired password length
     * @return the generated random password
     */
    private String generateRandomPassword(int length) {
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder(length);
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

        for (int i = 0; i < length; i++) {
            sb.append(letters.charAt(rnd.nextInt(letters.length())));
        }
        return sb.toString();
    }

    /**
     * Throws {@link PermissionDeniedException} if the caller is not an admin. Allows empty context (no access control).
     *
     * @param ctx the invocation context containing caller information
     * @throws IllegalArgumentException if ctx is null or missing user ID
     * @throws PermissionDeniedException if the caller is not an admin
     */
    private void requireAdmin(InvocationContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("InvocationContext must not be null");
        }

        //empty means ignore the access control
        if (ctx.isEmpty()) {
            return;
        }

        if (ctx.getUserId() == null) {
            throw new IllegalArgumentException("UserId missing in context");
        }

        if (!isAdmin(ctx)) {
            throw new PermissionDeniedException(
                    "user",
                    ctx.getUserId().longValue(),
                    "",
                    Permission.ADMIN,
                    Set.of() // no permissions
            );
        }
    }

    /**
     * Returns {@code true} if the caller's user ID matches the admin user's ID.
     *
     * @param ctx the invocation context containing caller information
     * @return {@code true} if the caller is the admin user
     */
    /*package*/ boolean isAdmin(InvocationContext ctx) {
        return ctx.getUserId() == adminUser.getId();
    }

    /**
     * Validates the old password via BCrypt and updates to the new password. The new password must be at least 8 characters, contain an uppercase letter and a
     * digit.
     *
     * @param username the username of the user
     * @param oldPassword the current plain-text password
     * @param newPassword the new plain-text password
     * @throws IllegalStateException if the user does not exist
     * @throws SecurityException if the current password is incorrect
     */
    public void changePassword(String username, String oldPassword, String newPassword) {
        validatePassword(newPassword);

        try {
            String currentHash = db.read(
                    """
                SELECT password
                FROM user
                WHERE username = ?;
                """,
                    rs -> rs.getString("password"),
                    username
            );

            if (currentHash == null) {
                throw new IllegalStateException("User does not exist: " + username);
            }

            if (!BCrypt.checkpw(oldPassword, currentHash)) {
                throw new SecurityException("Current password is incorrect.");
            }

            String newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt());

            db.write(
                    """
                UPDATE user
                SET password = ?
                WHERE username = ?;
                """,
                    newHash,
                    username
            );

        } catch (SQLException e) {
            throw new RuntimeException("Failed to change password for user: " + username, e);
        }
    }

    /**
     * Validates that a password meets complexity requirements: at least 8 characters, one uppercase letter, and one digit.
     *
     * @param password the password to validate
     * @throws IllegalArgumentException if the password does not meet the requirements
     */
    private static void validatePassword(String password) {
        if (password == null) {
            throw new IllegalArgumentException(
                    "Password must be at least 8 characters long and contain at least one uppercase letter and one digit."
            );
        }

        if (password.length() < 8) {
            throw new IllegalArgumentException(
                    "Password must be at least 8 characters long."
            );
        }

        if (!password.chars().anyMatch(Character::isUpperCase)) {
            throw new IllegalArgumentException(
                    "Password must contain at least one uppercase letter."
            );
        }

        if (!password.chars().anyMatch(Character::isDigit)) {
            throw new IllegalArgumentException(
                    "Password must contain at least one digit."
            );
        }
    }

    //agents =================================================
    @Override
    public void enableAgent(
            String username,
            String factory,
            JSONObject config,
            InvocationContext ctx) {

        requireAdmin(ctx);

        try {

            Boolean exists = db.read(
                    "SELECT EXISTS(SELECT 1 FROM user WHERE username = ?)",
                    rs -> {
                        rs.next();
                        return rs.getInt(1) == 1;
                    },
                    username
            );

            if (exists == null || !exists) {
                throw new IllegalArgumentException(
                        "User not found: " + username
                );
            }

            Boolean alreadyAgent = db.read(
                    "SELECT is_agent FROM user WHERE username = ?",
                    rs -> rs.next() && rs.getBoolean(1),
                    username
            );

            if (Boolean.TRUE.equals(alreadyAgent)) {
                throw new IllegalStateException(
                        "User is already an agent: " + username
                );
            }

            db.write(
                    """
                UPDATE user
                SET
                    is_agent = 1,
                    agent_factory = ?,
                    agent_config = ?
                WHERE username = ?
                """,
                    factory,
                    config == null ? null : config.toString(),
                    username
            );

        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to enable agent for user: " + username,
                    e
            );
        }
    }

    @Override
    public void disableAgent(
            String username,
            InvocationContext ctx) {

        requireAdmin(ctx);

        try {

            Boolean exists = db.read(
                    "SELECT EXISTS(SELECT 1 FROM user WHERE username = ?)",
                    rs -> {
                        rs.next();
                        return rs.getInt(1) == 1;
                    },
                    username
            );

            if (exists == null || !exists) {
                throw new IllegalArgumentException(
                        "User not found: " + username
                );
            }

            Boolean isAgent = db.read(
                    "SELECT is_agent FROM user WHERE username = ?",
                    rs -> rs.next() && rs.getBoolean(1),
                    username
            );

            if (!Boolean.TRUE.equals(isAgent)) {
                return; // already disabled
            }

            db.write(
                    """
                UPDATE user
                SET
                    is_agent = 0,
                    agent_factory = NULL,
                    agent_config = NULL
                WHERE username = ?
                """,
                    username
            );

            agentSessionManager.closeSessionsForAgent(username);

        } catch (SQLException e) {
            throw new RuntimeException(
                    "Failed to disable agent for user: " + username,
                    e
            );
        }
    }

    private List<User> resolveTargetUsers(Set<String> groupUris) {

        try {
            if (groupUris.isEmpty()) {
                return List.of();
            }

            String placeholders = groupUris.stream()
                    .map(x -> "?")
                    .collect(Collectors.joining(","));

            String sql
                    = """
            SELECT
                u.id,
                u.uri,
                u.username,
                u.password,
                u.firstname,
                u.lastname,
                u.email,
                u.is_agent,
                u.agent_factory,
                u.agent_config,

                g.id,
                g.groupname,
                g.uri
            FROM "group" g
            JOIN user u
                ON u.id = g.user_id
            WHERE g.uri IN (%s)
            """.formatted(placeholders);

            return db.read(
                    sql,
                    rs -> {

                        List<User> users = new ArrayList<>();

                        while (rs.next()) {

                            Group primaryGroup = new Group(
                                    rs.getInt(11),
                                    rs.getString(12),
                                    rs.getString(13)
                            );

                            boolean isAgent = rs.getBoolean("is_agent");

                            User user;

                            if (isAgent) {

                                user = new Agent(
                                        rs.getInt("id"),
                                        rs.getString("uri"),
                                        rs.getString("username"),
                                        primaryGroup,
                                        List.of(),
                                        rs.getString("firstname"),
                                        rs.getString("lastname"),
                                        rs.getString("email"),
                                        rs.getString("password"),
                                        rs.getString("agent_factory"),
                                        rs.getString("agent_config")
                                );

                            } else {

                                user = new User(
                                        rs.getInt("id"),
                                        rs.getString("uri"),
                                        rs.getString("username"),
                                        primaryGroup,
                                        List.of(),
                                        rs.getString("firstname"),
                                        rs.getString("lastname"),
                                        rs.getString("email"),
                                        rs.getString("password")
                                );
                            }

                            users.add(user);
                        }

                        return users;
                    },
                    groupUris.toArray()
            );
        } catch (Exception e) {
            throw new RuntimeException("Error when target users is read", e);
        }
    }

    //sharing ===============================================================
    /**
     * Grants the specified permission on one or more graphs to one or more groups. Performs upsert on {@code graph_acl} entries. The caller must have at least
     * the requested permission on each graph.
     *
     * @param graphUris the URIs of the graphs to share
     * @param groupUris the URIs of the groups to grant permission to
     * @param permission the permission to grant
     * @param ctx the invocation context containing caller information
     */
    @Override
    public void shareGraphs(
            Set<String> graphUris,
            Set<String> groupUris,
            Permission permission,
            InvocationContext ctx
    ) {
        this.shareGraphs(graphUris, groupUris, permission, null, createURN("session"), ctx);
    }

    /**
     * Revokes permission grants on graphs that were granted by the caller's primary group. Requires ADMIN permission on each graph. Cannot unshare oneself.
     *
     * @param graphUris the URIs of the graphs to unshare
     * @param groupUris the URIs of the groups to revoke permission from
     * @param ctx the invocation context containing caller information
     */
    @Override
    public void unshareGraphs(
            Set<String> graphUris,
            Set<String> groupUris,
            InvocationContext ctx
    ) {
        this.unshareGraphs(graphUris, groupUris, null, createURN("session"), ctx);
    }

    /**
     * Grants the specified permission on one or more resources to one or more groups. Performs upsert on {@code resource_acl} entries. The caller must have at
     * least the requested permission on each resource.
     *
     * @param resourceUris the URIs of the resources to share
     * @param groupUris the URIs of the groups to grant permission to
     * @param permission the permission to grant
     * @param ctx the invocation context containing caller information
     */
    @Override
    public void shareResources(
            Set<String> resourceUris,
            Set<String> groupUris,
            Permission permission,
            InvocationContext ctx
    ) {
        this.shareResources(resourceUris, groupUris, permission, null, createURN("session"), ctx);
    }

    /**
     * Revokes permission grants on resources that were granted by the caller's primary group. Requires ADMIN permission on each resource. Cannot unshare
     * oneself.
     *
     * @param resourceUris the URIs of the resources to unshare
     * @param groupUris the URIs of the groups to revoke permission from
     * @param ctx the invocation context containing caller information
     */
    @Override
    public void unshareResources(
            Set<String> resourceUris,
            Set<String> groupUris,
            InvocationContext ctx
    ) {
        this.unshareResources(resourceUris, groupUris, null, createURN("session"), ctx);
    }

    /**
     * Grants the specified permission on one or more graphs to one or more groups. Performs upsert on {@code graph_acl} entries. The caller must have at least
     * the requested permission on each graph.
     *
     * @param graphUris the URIs of the graphs to share
     * @param groupUris the URIs of the groups to grant permission to
     * @param permission the permission to grant
     * @param message optional message
     * @param ctx the invocation context containing caller information
     */
    @Override
    public void shareGraphs(
            Set<String> graphUris,
            Set<String> groupUris,
            Permission permission,
            String message, String sessionId,
            InvocationContext ctx
    ) {

        boolean enableAC = !isAdmin(ctx);

        for (String graphUri : graphUris) {
            try {
                // -----------------------------------------------
                // 1) Validate graph existence
                // -----------------------------------------------
                Long graphId = db.read(
                        "SELECT id FROM graph WHERE uri = ?",
                        rs -> rs.next() ? rs.getLong(1) : null,
                        graphUri
                );

                if (graphId == null) {
                    throw new IllegalStateException("Graph does not exist: " + graphUri);
                }

                // -----------------------------------------------
                // 2) Validate group URIs
                // -----------------------------------------------
                for (String grUri : groupUris) {
                    Boolean groupExists = db.read(
                            "SELECT EXISTS(SELECT 1 FROM \"group\" WHERE uri = ?)",
                            rs -> {
                                rs.next();
                                return rs.getInt(1) == 1;
                            },
                            grUri
                    );
                    if (groupExists == null || !groupExists) {
                        throw new IllegalArgumentException("Group not found: " + grUri);
                    }
                }

                // -----------------------------------------------
                // 3) Check caller permission on graph
                // -----------------------------------------------
                Set<Integer> callerGroupIds = ctx.getGroupIds();
                if (callerGroupIds.isEmpty()) {
                    throw new PermissionDeniedException(
                            "graph",
                            graphId,
                            graphUri,
                            permission,
                            Set.of()
                    );
                }

                Integer callerGrantingGroup = null;

                if (enableAC) {

                    // compute highest caller permission
                    StringBuilder callerPermSql = new StringBuilder();
                    callerPermSql.append("SELECT group_id, permission FROM graph_acl ")
                            .append("WHERE graph_id = ? AND group_id IN (")
                            .append(callerGroupIds.stream().map(g -> "?")
                                    .collect(Collectors.joining(",")))
                            .append(")");

                    List<Object> callerParams = new ArrayList<>();
                    callerParams.add(graphId);
                    callerParams.addAll(callerGroupIds.stream().map(g -> (Object) g).toList());

                    List<Pair<Integer, Integer>> callerPerms = db.read(
                            callerPermSql.toString(),
                            rs -> {
                                List<Pair<Integer, Integer>> out = new ArrayList<>();
                                while (rs.next()) {
                                    out.add(Pair.of(rs.getInt(1), rs.getInt(2)));
                                }
                                return out;
                            },
                            callerParams.toArray()
                    );

                    // determine effective
                    Permission callerEffective = null;
                    for (Pair<Integer, Integer> p : callerPerms) {
                        Permission perm = Permission.fromCode(p.getRight());
                        if (callerEffective == null || perm.getCode() > callerEffective.getCode()) {
                            callerEffective = perm;
                            callerGrantingGroup = p.getLeft();
                        }
                    }

                    if (callerEffective == null
                            || callerEffective.getCode() < permission.getCode()) {
                        throw new PermissionDeniedException(
                                "graph",
                                graphId,
                                graphUri,
                                permission,
                                callerEffective == null ? Set.of() : Set.of(callerEffective)
                        );
                    }

                } else {
                    callerGrantingGroup = ctx.getPrimaryGroupId();
                }

                // -----------------------------------------------
                // 4) Perform upsert for each group with granted_by
                // -----------------------------------------------
                for (String grUri : groupUris) {

                    Long groupId = db.read(
                            "SELECT id FROM \"group\" WHERE uri = ?",
                            rs -> rs.next() ? rs.getLong(1) : null,
                            grUri
                    );

                    if (groupId == null) {
                        throw new IllegalStateException("Group ID not found: " + grUri);
                    }

                    // -----------------------------------------------
                    // 1) Check effective permission for this group
                    // -----------------------------------------------
                    Integer effectiveCode = db.read(
                            "SELECT MAX(permission) FROM graph_acl "
                            + "WHERE graph_id = ? AND group_id = ?",
                            rs -> {
                                rs.next();
                                int val = rs.getInt(1);
                                return rs.wasNull() ? null : val;
                            },
                            graphId,
                            groupId
                    );

                    // Prevent lowering own admin
                    if (effectiveCode != null
                            && ctx.getGroupIds().contains(groupId.intValue())
                            && effectiveCode > permission.getCode()) {

                        Permission existing = Permission.fromCode(effectiveCode);

                        throw new PermissionDeniedException(
                                "graph",
                                graphId,
                                graphUri,
                                permission,
                                Set.of(existing)
                        );
                    }

                    // -----------------------------------------------
                    // 2) Check if THIS grantor already has a row
                    // -----------------------------------------------
                    Integer existingCode = db.read(
                            "SELECT permission FROM graph_acl "
                            + "WHERE graph_id = ? AND group_id = ? AND granted_by_group_id = ?",
                            rs -> rs.next() ? rs.getInt(1) : null,
                            graphId,
                            groupId,
                            callerGrantingGroup
                    );

                    // -----------------------------------------------
                    // 3) Insert or upgrade this grantor's row
                    // -----------------------------------------------
                    if (existingCode == null) {

                        db.write(
                                "INSERT INTO graph_acl(graph_id, group_id, permission, granted_by_group_id) "
                                + "VALUES (?, ?, ?, ?)",
                                graphId,
                                groupId,
                                permission.getCode(),
                                callerGrantingGroup
                        );

                    } else {

                        Permission existing = Permission.fromCode(existingCode);

                        // upgrade only (downgrade should be possible too)
                        //if (existing.getCode() < permission.getCode()) {
                        db.write(
                                "UPDATE graph_acl "
                                + "SET permission = ? "
                                + "WHERE graph_id = ? "
                                + "AND group_id = ? "
                                + "AND granted_by_group_id = ?",
                                permission.getCode(),
                                graphId,
                                groupId,
                                callerGrantingGroup
                        );
                        //}
                    }

                    //insert or upgrade: it is like a set
                    //callerGrantingGroup not emitted, maybe ok
                    rdfPatchEmitter.shareGraph(
                            IdAndUri.create(graphId, graphUri),
                            IdAndUri.create(groupId, grUri),
                            permission,
                            ctx
                    );
                }

            } catch (SQLException e) {
                throw new RuntimeException(
                        "Failed to share graphs " + graphUris + " with groups " + groupUris,
                        e
                );
            }
        }
    }

    /**
     * Revokes permission grants on graphs that were granted by the caller's primary group. Requires ADMIN permission on each graph. Cannot unshare oneself.
     *
     * @param graphUris the URIs of the graphs to unshare
     * @param groupUris the URIs of the groups to revoke permission from
     * @param message optional message
     * @param ctx the invocation context containing caller information
     */
    @Override
    public void unshareGraphs(
            Set<String> graphUris,
            Set<String> groupUris,
            String message, String sessionId,
            InvocationContext ctx
    ) {

        boolean enableAC = !isAdmin(ctx);

        for (String graphUri : graphUris) {
            try {
                // -----------------------------------------------
                // 1) Validate graph existence
                // -----------------------------------------------
                Long graphId = db.read(
                        "SELECT id FROM graph WHERE uri = ?",
                        rs -> rs.next() ? rs.getLong(1) : null,
                        graphUri
                );

                if (graphId == null) {
                    throw new IllegalStateException("Graph does not exist: " + graphUri);
                }

                // -----------------------------------------------
                // 2) Validate group URIs
                // -----------------------------------------------
                for (String grUri : groupUris) {
                    Long groupId = db.read(
                            "SELECT id FROM \"group\" WHERE uri = ?",
                            rs -> rs.next() ? rs.getLong("id") : null,
                            grUri
                    );

                    if (groupId == null) {
                        throw new IllegalArgumentException("Group not found: " + grUri);
                    }

                    if (groupId == ctx.getPrimaryGroupId().longValue()) {
                        throw new IllegalArgumentException("You cannot unshare yourself: " + grUri);
                    }
                }

                // -----------------------------------------------
                // 3) Permission check – ensure caller has sufficient rights
                //    Here we require caller has at least admin on this graph
                // -----------------------------------------------
                Set<Integer> callerGroupIds = ctx.getGroupIds();
                if (callerGroupIds.isEmpty()) {
                    throw new PermissionDeniedException(
                            "graph",
                            graphId,
                            graphUri,
                            Permission.ADMIN,
                            Set.of()
                    );
                }

                if (enableAC) {

                    // get max permission for caller groups
                    StringBuilder sql = new StringBuilder();
                    sql.append("SELECT MAX(permission) FROM graph_acl ")
                            .append("WHERE graph_id = ? AND group_id IN (")
                            .append(callerGroupIds.stream().map(g -> "?")
                                    .collect(Collectors.joining(",")))
                            .append(")");

                    List<Object> params = new ArrayList<>();
                    params.add(graphId);
                    params.addAll(callerGroupIds.stream().map(g -> (Object) g).toList());

                    Integer callerMax = db.read(
                            sql.toString(),
                            rs -> rs.next() ? rs.getInt(1) : null,
                            params.toArray()
                    );

                    Permission callerEffective = callerMax == null
                            ? null
                            : Permission.fromCode(callerMax);

                    if (callerEffective == null
                            || callerEffective.getCode() < Permission.ADMIN.getCode()) {
                        throw new PermissionDeniedException(
                                "graph",
                                graphId,
                                graphUri,
                                Permission.ADMIN,
                                callerEffective == null ? Set.of() : Set.of(callerEffective)
                        );
                    }

                }

                // -----------------------------------------------
                // 4) Delete only ACL entries granted by ctx.getPrimaryGroupId()
                // -----------------------------------------------
                Integer primaryGranting = ctx.getPrimaryGroupId();

                for (String grUri : groupUris) {
                    Long targetGroupId = db.read(
                            "SELECT id FROM \"group\" WHERE uri = ?",
                            rs -> rs.next() ? rs.getLong(1) : null,
                            grUri
                    );

                    if (targetGroupId == null) {
                        throw new IllegalStateException("Group ID not found: " + grUri);
                    }

                    if (enableAC) {
                        db.write(
                                "DELETE FROM graph_acl "
                                + "WHERE graph_id = ? "
                                + "AND group_id = ? "
                                + "AND granted_by_group_id = ?",
                                graphId,
                                targetGroupId,
                                primaryGranting
                        );
                    } else {
                        db.write(
                                "DELETE FROM graph_acl "
                                + "WHERE graph_id = ? "
                                + "AND group_id = ? ",
                                graphId,
                                targetGroupId
                        );
                    }

                    rdfPatchEmitter.unshareGraph(
                            IdAndUri.create(graphId, graphUri),
                            IdAndUri.create(targetGroupId, grUri),
                            ctx
                    );
                }

            } catch (SQLException e) {
                throw new RuntimeException(
                        "Failed to unshare graphs " + graphUris + " for groups " + groupUris,
                        e
                );
            }
        }
    }

    /**
     * Grants the specified permission on one or more resources to one or more groups. Performs upsert on {@code resource_acl} entries. The caller must have at
     * least the requested permission on each resource.
     *
     * @param resourceUris the URIs of the resources to share
     * @param groupUris the URIs of the groups to grant permission to
     * @param permission the permission to grant
     * @param message optional message
     * @param ctx the invocation context containing caller information
     */
    @Override
    public void shareResources(
            Set<String> resourceUris,
            Set<String> groupUris,
            Permission permission,
            String message, String sessionId,
            InvocationContext ctx
    ) {

        boolean enableAC = !isAdmin(ctx);

        Set<Node> nodes = new HashSet<>();

        for (String resourceUri : resourceUris) {
            try {
                // -----------------------------------------------
                // 1) Validate resource existence
                // -----------------------------------------------
                Long resourceId = db.read(
                        "SELECT id FROM resource_uri WHERE uri = ?",
                        rs -> rs.next() ? rs.getLong(1) : null,
                        resourceUri
                );

                if (resourceId == null) {
                    throw new IllegalStateException("Resource does not exist: " + resourceUri);
                }

                // -----------------------------------------------
                // 2) Validate group URIs
                // -----------------------------------------------
                for (String grUri : groupUris) {
                    Boolean groupExists = db.read(
                            "SELECT EXISTS(SELECT 1 FROM \"group\" WHERE uri = ?)",
                            rs -> {
                                rs.next();
                                return rs.getInt(1) == 1;
                            },
                            grUri
                    );
                    if (groupExists == null || !groupExists) {
                        throw new IllegalArgumentException("Group not found: " + grUri);
                    }
                }

                // -----------------------------------------------
                // 3) Check caller permission on resource
                // -----------------------------------------------
                Set<Integer> callerGroupIds = ctx.getGroupIds();
                if (callerGroupIds.isEmpty()) {
                    throw new PermissionDeniedException(
                            "resource",
                            resourceId,
                            resourceUri,
                            permission,
                            Set.of()
                    );
                }

                Integer callerGrantingGroup = null;

                if (enableAC) {

                    StringBuilder callerPermSql = new StringBuilder();
                    callerPermSql.append("SELECT group_id, permission FROM resource_acl ")
                            .append("WHERE resource_id = ? AND group_id IN (")
                            .append(callerGroupIds.stream().map(g -> "?")
                                    .collect(Collectors.joining(",")))
                            .append(")");

                    List<Object> callerParams = new ArrayList<>();
                    callerParams.add(resourceId);
                    callerParams.addAll(callerGroupIds.stream().map(g -> (Object) g).toList());

                    List<Pair<Integer, Integer>> callerPerms = db.read(
                            callerPermSql.toString(),
                            rs -> {
                                List<Pair<Integer, Integer>> out = new ArrayList<>();
                                while (rs.next()) {
                                    out.add(Pair.of(rs.getInt(1), rs.getInt(2)));
                                }
                                return out;
                            },
                            callerParams.toArray()
                    );

                    Permission callerEffective = null;

                    for (Pair<Integer, Integer> p : callerPerms) {
                        Permission perm = Permission.fromCode(p.getRight());
                        if (callerEffective == null || perm.getCode() > callerEffective.getCode()) {
                            callerEffective = perm;
                            callerGrantingGroup = p.getLeft();
                        }
                    }

                    if (callerEffective == null
                            || callerEffective.getCode() < permission.getCode()) {
                        throw new PermissionDeniedException(
                                "resource",
                                resourceId,
                                resourceUri,
                                permission,
                                callerEffective == null ? Set.of() : Set.of(callerEffective)
                        );
                    }

                } else {
                    callerGrantingGroup = ctx.getPrimaryGroupId();
                }

                // -----------------------------------------------
                // 4) Upsert ACL entries
                // -----------------------------------------------
                for (String grUri : groupUris) {

                    Long groupId = db.read(
                            "SELECT id FROM \"group\" WHERE uri = ?",
                            rs -> rs.next() ? rs.getLong(1) : null,
                            grUri
                    );

                    if (groupId == null) {
                        throw new IllegalStateException("Group ID not found: " + grUri);
                    }

                    Integer effectiveCode = db.read(
                            "SELECT MAX(permission) FROM resource_acl "
                            + "WHERE resource_id = ? AND group_id = ?",
                            rs -> {
                                rs.next();
                                int val = rs.getInt(1);
                                return rs.wasNull() ? null : val;
                            },
                            resourceId,
                            groupId
                    );

                    // prevent lowering own permission
                    if (effectiveCode != null
                            && ctx.getGroupIds().contains(groupId.intValue())
                            && effectiveCode > permission.getCode()) {

                        Permission existing = Permission.fromCode(effectiveCode);

                        throw new PermissionDeniedException(
                                "resource",
                                resourceId,
                                resourceUri,
                                permission,
                                Set.of(existing)
                        );
                    }

                    Integer existingCode = db.read(
                            "SELECT permission FROM resource_acl "
                            + "WHERE resource_id = ? AND group_id = ? AND granted_by_group_id = ?",
                            rs -> rs.next() ? rs.getInt(1) : null,
                            resourceId,
                            groupId,
                            callerGrantingGroup
                    );

                    if (existingCode == null) {

                        db.write(
                                "INSERT INTO resource_acl(resource_id, group_id, permission, granted_by_group_id) "
                                + "VALUES (?, ?, ?, ?)",
                                resourceId,
                                groupId,
                                permission.getCode(),
                                callerGrantingGroup
                        );

                    } else {

                        db.write(
                                "UPDATE resource_acl "
                                + "SET permission = ? "
                                + "WHERE resource_id = ? "
                                + "AND group_id = ? "
                                + "AND granted_by_group_id = ?",
                                permission.getCode(),
                                resourceId,
                                groupId,
                                callerGrantingGroup
                        );
                    }

                    //insert or upgrade: it is like a set
                    //callerGrantingGroup not emitted, maybe ok
                    rdfPatchEmitter.shareResource(
                            IdAndUri.create(resourceId, resourceUri),
                            IdAndUri.create(groupId, grUri),
                            permission,
                            ctx
                    );
                }

            } catch (SQLException e) {
                throw new RuntimeException(
                        "Failed to share resources " + resourceUris + " with groups " + groupUris,
                        e
                );
            }

            nodes.add(NodeFactory.createURI(resourceUri));
        }

        List<User> targetUsers = resolveTargetUsers(groupUris);

        List<Agent> agents = targetUsers.stream().filter(u -> u.isAgent()).map(u -> (Agent) u).collect(Collectors.toList());

        User principal = this.getUser(ctx.getUserId(), InvocationContext.EMPTY);
        
        for (Agent agent : agents) {
            Session session = agentSessionManager.getOrAddSession(principal, sessionId, agent, this, ctx);
            
            session.submit(
                    Message.builder(principal, message, Message.TEXT_PLAIN)
                            .attachment(new RdfNodesAttachment(nodes))
                            .build()
            );
        }
    }

    /**
     * Revokes permission grants on resources that were granted by the caller's primary group. Requires ADMIN permission on each resource. Cannot unshare
     * oneself.
     *
     * @param resourceUris the URIs of the resources to unshare
     * @param groupUris the URIs of the groups to revoke permission from
     * @param message optional message
     * @param ctx the invocation context containing caller information
     */
    @Override
    public void unshareResources(
            Set<String> resourceUris,
            Set<String> groupUris,
            String message, String sessionId,
            InvocationContext ctx
    ) {

        boolean enableAC = !isAdmin(ctx);

        for (String resourceUri : resourceUris) {
            try {
                // -----------------------------------------------
                // 1) Validate resource existence
                // -----------------------------------------------
                Long resourceId = db.read(
                        "SELECT id FROM resource_uri WHERE uri = ?",
                        rs -> rs.next() ? rs.getLong(1) : null,
                        resourceUri
                );

                if (resourceId == null) {
                    throw new IllegalStateException("Resource does not exist: " + resourceUri);
                }

                // -----------------------------------------------
                // 2) Validate groups
                // -----------------------------------------------
                for (String grUri : groupUris) {
                    Long groupId = db.read(
                            "SELECT id FROM \"group\" WHERE uri = ?",
                            rs -> rs.next() ? rs.getLong("id") : null,
                            grUri
                    );

                    if (groupId == null) {
                        throw new IllegalArgumentException("Group not found: " + grUri);
                    }

                    if (groupId == ctx.getPrimaryGroupId().longValue()) {
                        throw new IllegalArgumentException("You cannot unshare yourself: " + grUri);
                    }
                }

                // -----------------------------------------------
                // 3) Require ADMIN permission
                // -----------------------------------------------
                Set<Integer> callerGroupIds = ctx.getGroupIds();
                if (callerGroupIds.isEmpty()) {
                    throw new PermissionDeniedException(
                            "resource",
                            resourceId,
                            resourceUri,
                            Permission.ADMIN,
                            Set.of()
                    );
                }

                if (enableAC) {

                    StringBuilder sql = new StringBuilder();
                    sql.append("SELECT MAX(permission) FROM resource_acl ")
                            .append("WHERE resource_id = ? AND group_id IN (")
                            .append(callerGroupIds.stream().map(g -> "?")
                                    .collect(Collectors.joining(",")))
                            .append(")");

                    List<Object> params = new ArrayList<>();
                    params.add(resourceId);
                    params.addAll(callerGroupIds.stream().map(g -> (Object) g).toList());

                    Integer callerMax = db.read(
                            sql.toString(),
                            rs -> rs.next() ? rs.getInt(1) : null,
                            params.toArray()
                    );

                    Permission callerEffective = callerMax == null
                            ? null
                            : Permission.fromCode(callerMax);

                    if (callerEffective == null
                            || callerEffective.getCode() < Permission.ADMIN.getCode()) {
                        throw new PermissionDeniedException(
                                "resource",
                                resourceId,
                                resourceUri,
                                Permission.ADMIN,
                                callerEffective == null ? Set.of() : Set.of(callerEffective)
                        );
                    }
                }

                // -----------------------------------------------
                // 4) Delete only entries granted by caller's primary group
                // -----------------------------------------------
                Integer primaryGranting = ctx.getPrimaryGroupId();

                for (String grUri : groupUris) {

                    Long targetGroupId = db.read(
                            "SELECT id FROM \"group\" WHERE uri = ?",
                            rs -> rs.next() ? rs.getLong(1) : null,
                            grUri
                    );

                    if (targetGroupId == null) {
                        throw new IllegalStateException("Group ID not found: " + grUri);
                    }

                    if (enableAC) {
                        db.write(
                                "DELETE FROM resource_acl "
                                + "WHERE resource_id = ? "
                                + "AND group_id = ? "
                                + "AND granted_by_group_id = ?",
                                resourceId,
                                targetGroupId,
                                primaryGranting
                        );
                    } else {
                        db.write(
                                "DELETE FROM resource_acl "
                                + "WHERE resource_id = ? "
                                + "AND group_id = ? ",
                                resourceId,
                                targetGroupId
                        );
                    }

                    rdfPatchEmitter.unshareGraph(
                            IdAndUri.create(resourceId, resourceUri),
                            IdAndUri.create(targetGroupId, grUri),
                            ctx
                    );
                }

            } catch (SQLException e) {
                throw new RuntimeException(
                        "Failed to unshare resources " + resourceUris + " for groups " + groupUris,
                        e
                );
            }
        }
    }

    //list the permissions of the caller
    /**
     * Returns a map from resource URI to the caller's highest effective permission for each resource.
     *
     * @param resourceUris the URIs of the resources to query
     * @param ctx the invocation context containing caller information
     * @return a map from resource URI to {@link Permission}
     */
    public Map<String, Permission> listResourcePermissions(Set<String> resourceUris, InvocationContext ctx) {

        if (resourceUris == null || resourceUris.isEmpty()) {
            return Map.of();
        }

        Set<Integer> grpIds = ctx.getGroupIds();
        if (grpIds == null || grpIds.isEmpty()) {
            return Map.of();
        }

        try {

            StringBuilder sql = new StringBuilder();
            List<Object> params = new ArrayList<>();

            sql.append("SELECT r.uri, MAX(ra.permission) AS perm ")
                    .append("FROM resource_uri r ")
                    .append("JOIN resource_acl ra ON ra.resource_id = r.id ")
                    .append("WHERE r.uri IN (")
                    .append(resourceUris.stream().map(u -> "?").collect(Collectors.joining(",")))
                    .append(") AND ra.group_id IN (")
                    .append(grpIds.stream().map(g -> "?").collect(Collectors.joining(",")))
                    .append(") GROUP BY r.uri");

            params.addAll(resourceUris);
            params.addAll(grpIds);

            return db.read(
                    sql.toString(),
                    rs -> {
                        Map<String, Permission> result = new HashMap<>();

                        while (rs.next()) {

                            String uri = rs.getString("uri");
                            int permCode = rs.getInt("perm");

                            Permission perm = Permission.fromCode(permCode);
                            if (perm != null) {
                                result.put(uri, perm);
                            }
                        }

                        return result;
                    },
                    params.toArray()
            );

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns a map from graph URI to the caller's highest effective permission for each graph.
     *
     * @param graphUris the URIs of the graphs to query
     * @param ctx the invocation context containing caller information
     * @return a map from graph URI to {@link Permission}
     */
    public Map<String, Permission> listGraphPermissions(Set<String> graphUris, InvocationContext ctx) {

        if (graphUris == null || graphUris.isEmpty()) {
            return Map.of();
        }

        Set<Integer> grpIds = ctx.getGroupIds();
        if (grpIds == null || grpIds.isEmpty()) {
            return Map.of();
        }

        try {

            StringBuilder sql = new StringBuilder();
            List<Object> params = new ArrayList<>();

            sql.append("SELECT g.uri, MAX(ga.permission) AS perm ")
                    .append("FROM graph g ")
                    .append("JOIN graph_acl ga ON ga.graph_id = g.id ")
                    .append("WHERE g.uri IN (")
                    .append(graphUris.stream().map(u -> "?").collect(Collectors.joining(",")))
                    .append(") AND ga.group_id IN (")
                    .append(grpIds.stream().map(g -> "?").collect(Collectors.joining(",")))
                    .append(") GROUP BY g.uri");

            params.addAll(graphUris);
            params.addAll(grpIds);

            return db.read(
                    sql.toString(),
                    rs -> {
                        Map<String, Permission> result = new HashMap<>();

                        while (rs.next()) {

                            String uri = rs.getString("uri");
                            int permCode = rs.getInt("perm");

                            Permission perm = Permission.fromCode(permCode);
                            if (perm != null) {
                                result.put(uri, perm);
                            }
                        }

                        return result;
                    },
                    params.toArray()
            );

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns a map from URI to a list of {@link PrincipalPermission} entries showing all principals (users/groups) that have access to each URI, sorted by
     * permission descending then name ascending.
     *
     * @param uris the URIs to query
     * @param forGraphs {@code true} if querying graph permissions, {@code false} for resource permissions
     * @param ctx the invocation context containing caller information
     * @return a map from URI to a list of {@link PrincipalPermission}
     */
    public Map<String, List<PrincipalPermission>> listPrincipalPermissions(
            Set<String> uris,
            boolean forGraphs,
            InvocationContext ctx
    ) {

        if (uris == null || uris.isEmpty()) {
            return Map.of();
        }

        Set<Integer> grpIds = ctx.getGroupIds();
        if (grpIds == null || grpIds.isEmpty()) {
            return Map.of();
        }

        String aclTable = forGraphs ? "graph_acl" : "resource_acl";
        String baseTable = forGraphs ? "graph" : "resource_uri";
        String fkColumn = forGraphs ? "graph_id" : "resource_id";

        try {

            StringBuilder sql = new StringBuilder();
            List<Object> params = new ArrayList<>();

            sql.append("""
            SELECT
                base.uri AS uri,

                gp.id AS group_id,
                gp.uri AS group_uri,
                gp.groupname AS groupname,
                gp.user_id AS user_id,

                u.id AS user_id_real,
                u.uri AS user_uri,
                u.username AS username,
                u.firstname AS firstname,
                u.lastname AS lastname,
                u.email AS email,

                acl.permission AS perm

            FROM %s base

            JOIN %s acl
                ON acl.%s = base.id

            JOIN "group" gp
                ON gp.id = acl.group_id

            LEFT JOIN user u
                ON u.id = gp.user_id

            WHERE base.uri IN (
        """.formatted(baseTable, aclTable, fkColumn));

            sql.append(
                    uris.stream()
                            .map(u -> "?")
                            .collect(Collectors.joining(","))
            );

            sql.append("""
            )

            AND EXISTS (

                SELECT 1

                FROM %s acl_check

                WHERE acl_check.%s = base.id
                  AND acl_check.group_id IN (
        """.formatted(aclTable, fkColumn));

            sql.append(
                    grpIds.stream()
                            .map(g -> "?")
                            .collect(Collectors.joining(","))
            );

            sql.append("""
                  )
                  AND acl_check.permission >= ?
            )
        """);

            // params for URI filter
            params.addAll(uris);

            // params for access check
            params.addAll(grpIds);

            // minimum required permission = READ
            params.add(Permission.READ.getCode());

            return db.read(
                    sql.toString(),
                    rs -> {

                        Map<String, List<PrincipalPermission>> result = new HashMap<>();

                        while (rs.next()) {

                            String uri = rs.getString("uri");

                            Permission perm
                            = Permission.fromCode(rs.getInt("perm"));

                            if (perm == null) {
                                continue;
                            }

                            Integer userId
                            = (Integer) rs.getObject("user_id");

                            Principal principal;

                            if (userId != null) {

                                Group primaryGroup = new Group(
                                        rs.getInt("group_id"),
                                        rs.getString("group_uri"),
                                        rs.getString("groupname")
                                );

                                principal = new User(
                                        rs.getInt("user_id_real"),
                                        rs.getString("user_uri"),
                                        rs.getString("username"),
                                        primaryGroup,
                                        List.of(),
                                        rs.getString("firstname"),
                                        rs.getString("lastname"),
                                        rs.getString("email"),
                                        null
                                );

                            } else {

                                principal = new Group(
                                        rs.getInt("group_id"),
                                        rs.getString("group_uri"),
                                        rs.getString("groupname")
                                );
                            }

                            result.computeIfAbsent(uri, k -> new ArrayList<>())
                                    .add(new PrincipalPermission(principal, perm));
                        }

                        // sort:
                        // permission DESC
                        // principal name ASC
                        result.values().forEach(list
                                -> list.sort(
                                Comparator
                                        .comparing(
                                                (PrincipalPermission pp)
                                                -> pp.getPermission().getCode()
                                        )
                                        .reversed()
                                        .thenComparing(
                                                pp -> pp.getPrincipal().getName(),
                                                String.CASE_INSENSITIVE_ORDER
                                        )
                        )
                        );

                        return result;
                    },
                    params.toArray()
            );

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    //graph management =====================================================
    //just calls getGraph
    /**
     * Returns the {@link AticGraph} for the Jena default graph. Delegates to {@link #getGraph(Node, InvocationContext)} with
     * {@link org.apache.jena.sparql.core.Quad#defaultGraphIRI}.
     *
     * @param ctx the invocation context containing caller information
     * @return the default {@link AticGraph}
     */
    @Override
    public AticGraph getDefaultGraph(InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, context);

        //boolean unionDefaultGraph = context.get(UNION_DEFAULT_GRAPH, false);
        return getGraph(org.apache.jena.sparql.core.Quad.defaultGraphIRI, ctx);
    }

    /**
     * Returns an {@link AticGraph} for the given node. Creates the graph if it does not exist. Handles virtual graphs and the union graph. Performs a READ
     * access control check.
     *
     * @param graphNode the graph node identifier
     * @param ctx the invocation context containing caller information
     * @return the {@link AticGraph} for the given node
     */
    //note: getGraph will create a graph if it does not exist
    //note: if Quad.unionGraph is used, getUnionGraph is called
    @Override
    public AticGraph getGraph(org.apache.jena.graph.Node graphNode, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, context);

        //special union Graph name
        if (graphNode.equals(Quad.unionGraph)) {
            return getUnionGraph(ctx);
        }

        //ensure that Quad.defaultGraphIRI is used if default graph is mentioned
        graphNode = ensureDefaultGraphIRI(graphNode);

        boolean enableAC = !isAdmin(ctx);

        final String graphTable = "graph";
        final Permission required = Permission.READ;

        String requestedUri = graphNode.getURI();

        try {
            // -------------------------------------------------
            // fetch the graph row
            // -------------------------------------------------
            Object[] graphInfo = db.read(
                    "SELECT id, uri, is_virtual FROM graph WHERE uri = ?",
                    rs -> {
                        if (!rs.next()) {
                            return null;
                        }
                        return new Object[]{
                            rs.getLong("id"),
                            rs.getString("uri"),
                            rs.getInt("is_virtual") == 1
                        };
                    },
                    requestedUri
            );

            if (graphInfo == null) {
                //throw new IllegalStateException("Graph not found: " + requestedUri);

                //create if not exist
                addGraph(graphNode, Graph.emptyGraph, ctx);
                return getGraph(graphNode, ctx);
            }

            long graphId = (Long) graphInfo[0];
            String uri = (String) graphInfo[1];
            boolean isVirtual = (Boolean) graphInfo[2];

            // -------------------------------------------------
            // build ACL query
            // -------------------------------------------------
            if (enableAC) {
                Set<Integer> groupIds = ctx.getGroupIds();

                StringBuilder sql = new StringBuilder();
                sql.append("SELECT MAX(permission) ");
                sql.append("FROM graph_acl ");
                sql.append("WHERE graph_id = ? ");

                if (!groupIds.isEmpty()) {
                    sql.append("AND group_id IN (");
                    sql.append(groupIds.stream()
                            .map(g -> "?")
                            .collect(Collectors.joining(",")));
                    sql.append(")");
                }

                List<Object> params = new ArrayList<>();
                params.add(graphId);
                params.addAll(groupIds.stream().map(g -> (Object) g).toList());

                Integer maxPermCode = db.read(
                        sql.toString(),
                        rs -> rs.next() ? rs.getInt(1) : null,
                        params.toArray()
                );

                Permission effective = (maxPermCode == null)
                        ? null
                        : Permission.fromCode(maxPermCode);

                Set<Permission> actual = (effective == null)
                        ? Set.of()
                        : Set.of(effective);

                // -------------------------------------------------
                // permission check
                // -------------------------------------------------
                if (effective == null || effective.getCode() < required.getCode()) {
                    throw new PermissionDeniedException(
                            graphTable,
                            graphId,
                            requestedUri,
                            required,
                            actual
                    );
                }
            }

            // -------------------------------------------------
            // return graph
            // -------------------------------------------------
            if (isVirtual) {
                AticGraph virtual = virtualGraphMap.get(graphNode);

                if (virtual == null) {
                    throw new IllegalStateException(
                            "Virtual graph not loaded in memory: " + requestedUri
                    );
                }

                return virtual;
            }

            Set<IdAndUri> key = Set.of(new IdAndUri(graphId, uri));

            if (graphMap.containsKey(key)) {
                return graphMap.get(key);
            }

            SqliteAticGraph sqliteAticGraph = new SqliteAticGraph(
                    List.of(new IdAndUri(graphId, uri)),
                    this
            );

            graphMap.put(key, sqliteAticGraph);

            return sqliteAticGraph;

        } catch (SQLException e) {
            throw new RuntimeException(
                    "Database error while fetching graph: " + requestedUri, e
            );
        }
    }

    /**
     * Returns a union graph over all named graphs the caller has permission to read.
     *
     * @param ctx the invocation context containing caller information
     * @return the union {@link AticGraph}
     */
    //reuses getUnionGraph(Iterator<Node> graphNodes)
    @Override
    public AticGraph getUnionGraph(InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, context);

        Iterator<Node> iter = listGraphNodes(ctx, false);

        return getUnionGraph(iter, ctx);
    }

    /**
     * Returns a union graph over the specified graph nodes, after validating the caller has READ permission on each.
     *
     * @param graphNodes an iterator of graph nodes to union
     * @param ctx the invocation context containing caller information
     * @return the union {@link AticGraph}
     */
    //note: getUnionGraph() is meant to be returning union of all visible graphs
    //therefore, getUnionGraph does not create graph if missing
    @Override
    public AticGraph getUnionGraph(Iterator<Node> graphNodes, InvocationContext ctx) {

        boolean enableAC = !isAdmin(ctx);

        // -----------------------------------------
        // 1. compute allowed graphs once
        // -----------------------------------------
        Set<Node> allowedGraphs = new HashSet<>();
        listGraphNodes(ctx, true).forEachRemaining(allowedGraphs::add);

        //not necessary anymore, because of withDefaultGraph option
        //since listGraphNodes does not return default graph we have to add it here
        //allowedGraphs.add(Quad.defaultGraphIRI);
        //TODO since defaultGraph is shared with everyone this is fine, but if this changes, this is not correct anymore
        // -----------------------------------------
        // 2. validate requested graphs
        // -----------------------------------------
        List<Node> requested = new ArrayList<>();
        graphNodes.forEachRemaining(requested::add);

        if (enableAC) {
            for (Node g : requested) {
                if (!allowedGraphs.contains(g)) {
                    throw new PermissionDeniedException(
                            "graph",
                            -1L,
                            g.getURI(),
                            Permission.READ,
                            Set.of()
                    );
                }
            }
        }

        // -----------------------------------------
        // 3. proceed (safe now)
        // -----------------------------------------
        List<IdAndUri> idAndUris = new ArrayList<>();

        for (Node graphNode : requested) {

            IdAndUri idAndUri;
            try {
                idAndUri = db.read(
                        "SELECT id, uri FROM graph WHERE uri = ?",
                        rs -> {
                            if (!rs.next()) {
                                return null;
                            }
                            return new IdAndUri(
                                    rs.getLong("id"),
                                    rs.getString("uri")
                            );
                        },
                        graphNode.getURI()
                );
            } catch (SQLException ex) {
                throw new RuntimeException("DB Error", ex);
            }

            if (idAndUri == null) {
                throw new IllegalArgumentException(
                        "Graph not found: " + graphNode.getURI()
                );
            }

            idAndUris.add(idAndUri);
        }

        Set<IdAndUri> key = new HashSet<>(idAndUris);

        if (graphMap.containsKey(key)) {
            return graphMap.get(key);
        }

        //TODO union graph needs to be read-only, even if union graph has just one graph (it seems)
        SqliteAticGraph sqliteAticGraph = new SqliteAticGraph(idAndUris, this);

        graphMap.put(key, sqliteAticGraph);

        return sqliteAticGraph;
    }

    /**
     * Returns an iterator over all named graph URIs the caller can access.
     *
     * @param ctx the invocation context containing caller information
     * @return an iterator over accessible graph URIs
     */
    @Override
    public Iterator<Node> listGraphNodes(InvocationContext ctx) {
        return listGraphNodes(ctx, false);
    }

    /**
     * Returns an iterator over accessible graph URIs, optionally including the default graph. Applies READ permission filtering.
     *
     * @param ctx the invocation context containing caller information
     * @param withDefaultGraph whether to include the default graph in the result
     * @return an iterator over accessible graph URIs
     */
    private Iterator<Node> listGraphNodes(InvocationContext ctx, boolean withDefaultGraph) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, context);

        boolean enableAC = !isAdmin(ctx);

        final Permission required = Permission.READ;
        final String defaultGraphUri = Quad.defaultGraphIRI.getURI();

        try {

            Set<Integer> groupIds = ctx.getGroupIds();

            if (groupIds == null || groupIds.isEmpty()) {
                return Collections.emptyIterator();
            }

            StringBuilder sql = new StringBuilder();
            sql.append("SELECT g.uri ");
            sql.append("FROM graph g ");
            sql.append("JOIN graph_acl a ON g.id = a.graph_id ");
            if (!withDefaultGraph) {
                sql.append("WHERE g.uri <> ? ");
            }
            if (enableAC) {
                sql.append("AND a.group_id IN (");
                sql.append(groupIds.stream()
                        .map(id -> "?")
                        .collect(Collectors.joining(",")));
                sql.append(") ");
                sql.append("GROUP BY g.id ");
                sql.append("HAVING MAX(a.permission) >= ?");
            }

            List<Object> params = new ArrayList<>();
            if (!withDefaultGraph) {
                params.add(defaultGraphUri);
            }

            if (enableAC) {
                params.addAll(groupIds.stream().map(g -> (Object) g).toList());
                params.add(required.getCode());
            }

            List<Node> nodes = db.read(
                    sql.toString(),
                    rs -> {
                        List<Node> result = new ArrayList<>();
                        while (rs.next()) {
                            result.add(NodeFactory.createURI(rs.getString("uri")));
                        }
                        return result;
                    },
                    params.toArray()
            );

            return nodes.iterator();

        } catch (SQLException e) {
            throw new RuntimeException("Database error while listing graphs", e);
        }
    }

    /**
     * Returns {@code true} if the given graph exists and the caller has READ permission on it.
     *
     * @param graphNode the graph node identifier
     * @param ctx the invocation context containing caller information
     * @return {@code true} if the graph exists and is accessible
     */
    @Override
    public boolean containsGraph(Node graphNode, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, context);

        boolean enableAC = !isAdmin(ctx);

        final Permission required = Permission.READ;
        String requestedUri = graphNode.getURI();

        try {

            Set<Integer> groupIds = ctx.getGroupIds();
            if (groupIds == null || groupIds.isEmpty()) {
                return false;
            }

            StringBuilder sql = new StringBuilder();

            sql.append("SELECT EXISTS (");
            sql.append(" SELECT 1 ");
            sql.append(" FROM graph g ");
            sql.append(" JOIN graph_acl a ON g.id = a.graph_id ");
            sql.append(" WHERE g.uri = ? ");

            if (enableAC) {
                sql.append(" AND a.group_id IN (");
                sql.append(groupIds.stream()
                        .map(id -> "?")
                        .collect(Collectors.joining(",")));
                sql.append(") ");
                sql.append(" GROUP BY g.id ");
                sql.append(" HAVING MAX(a.permission) >= ?");
            }
            sql.append(")");

            List<Object> params = new ArrayList<>();
            params.add(requestedUri);

            if (enableAC) {
                params.addAll(groupIds.stream().map(g -> (Object) g).toList());
                params.add(required.getCode());
            }

            Boolean exists = db.read(
                    sql.toString(),
                    rs -> rs.next() && rs.getInt(1) == 1,
                    params.toArray()
            );

            return exists;

        } catch (SQLException e) {
            throw new RuntimeException(
                    "Database error while checking graph existence: " + requestedUri, e
            );
        }
    }

    /**
     * Creates a new graph with the given name and transfers all triples from the source graph. The creator receives ADMIN permission.
     *
     * @param graphName the URI of the graph to create
     * @param graph the source graph whose triples will be copied
     * @param ctx the invocation context containing caller information
     */
    @Override
    public void addGraph(Node graphName, Graph graph, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, context);

        String graphUri = graphName.getURI();

        try {

            // ------------------------------------------------
            // check if graph already exists
            // ------------------------------------------------
            Boolean exists = db.read(
                    "SELECT EXISTS(SELECT 1 FROM graph WHERE uri = ?)",
                    rs -> {
                        rs.next();
                        return rs.getInt(1) == 1;
                    },
                    graphUri
            );

            if (exists) {
                throw new IllegalStateException("Graph already exists: " + graphUri);
            }

            // ------------------------------------------------
            // create graph row and obtain id
            // ------------------------------------------------
            long graphId = db.writeReturningId(
                    "INSERT INTO graph(uri, creator) VALUES (?, ?)",
                    graphUri,
                    ctx.getUserId()
            );

            // ------------------------------------------------
            // creator group receives ADMIN permission
            // ------------------------------------------------
            db.write(
                    "INSERT INTO graph_acl(group_id, graph_id, permission, granted_by_group_id) VALUES (?, ?, ?, ?)",
                    ctx.getPrimaryGroupId(),
                    graphId,
                    Permission.ADMIN.getCode(),
                    ctx.getPrimaryGroupId()
            );

            // ------------------------------------------------
            // obtain AticGraph instance
            // ------------------------------------------------
            SqliteAticGraph aticGraph = (SqliteAticGraph) getGraph(graphName, ctx);

            // ------------------------------------------------
            // transfer triples
            // ------------------------------------------------
            ExtendedIterator<Triple> it = graph.find();

            try {
                //we use this add implementation for speed
                aticGraph.add(it, ctx, 500, 500, -1);
            } finally {
                it.close();
            }

        } catch (SQLException e) {
            throw new RuntimeException("Database error while adding graph: " + graphUri, e);
        }
    }

    /**
     * Registers a virtual graph by resolving and invoking a factory method, then storing it in the database and in-memory map.
     *
     * @param graphName the URI of the virtual graph to create
     * @param factoryMethodPath the classpath path to the factory method (e.g., "com.example.Factory.createGraph")
     * @param config the configuration JSON for the virtual graph
     * @param ctx the invocation context containing caller information
     */
    public void addVirtualGraph(Node graphName, String factoryMethodPath, JSONObject config, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, context);

        String graphUri = graphName.getURI();

        AticVirtualGraph virtualGraph = loadVirtualGraph(graphUri, factoryMethodPath, config);

        try {
            // ------------------------------------------------
            // check if graph already exists
            // ------------------------------------------------
            Boolean exists = db.read(
                    "SELECT EXISTS(SELECT 1 FROM graph WHERE uri = ?)",
                    rs -> {
                        rs.next();
                        return rs.getInt(1) == 1;
                    },
                    graphUri
            );

            if (exists) {
                throw new IllegalStateException("Graph already exists: " + graphUri);
            }

            // ------------------------------------------------
            // create graph row and obtain id
            // ------------------------------------------------
            long graphId = db.writeReturningId(
                    "INSERT INTO graph(uri, creator, is_virtual, virtual_factory, virtual_config) VALUES (?, ?, 1, ?, ?)",
                    graphUri,
                    ctx.getUserId(),
                    factoryMethodPath,
                    config.toString(4)
            );

            // ------------------------------------------------
            // creator group receives ADMIN permission
            // ------------------------------------------------
            db.write(
                    "INSERT INTO graph_acl(group_id, graph_id, permission, granted_by_group_id) VALUES (?, ?, ?, ?)",
                    ctx.getPrimaryGroupId(),
                    graphId,
                    Permission.ADMIN.getCode(),
                    ctx.getPrimaryGroupId()
            );

            // ------------------------------------------------
            // register virtual graph
            // ------------------------------------------------
            virtualGraphMap.put(graphName, virtualGraph);

        } catch (SQLException e) {
            throw new RuntimeException("Database error while adding virtual graph: " + graphUri, e);
        }
    }

    /**
     * Dynamically loads a virtual graph by resolving the factory method from its class path string and invoking it with the given configuration.
     *
     * @param graphUri the URI of the virtual graph
     * @param factoryMethodPath the classpath path to the factory method
     * @param config the configuration JSON for the virtual graph
     * @return the loaded {@link AticVirtualGraph}
     */
    private AticVirtualGraph loadVirtualGraph(String graphUri, String factoryMethodPath, JSONObject config) {

        Method factoryMethod;
        try {
            // ------------------------------------------------
            // resolve factory method
            // ------------------------------------------------
            int sep = factoryMethodPath.lastIndexOf('.');
            if (sep < 0) {
                throw new IllegalArgumentException("Invalid factory method path: " + factoryMethodPath);
            }

            String className = factoryMethodPath.substring(0, sep);
            String methodName = factoryMethodPath.substring(sep + 1);

            Class<?> factoryClass = Class.forName(className);

            factoryMethod = factoryClass.getMethod(methodName, String.class, String.class, SqliteAticDatasetGraph.class);

        } catch (NoSuchMethodException ex) {
            throw new IllegalArgumentException(
                    "Method not found: " + factoryMethodPath, ex
            );
        } catch (SecurityException ex) {
            throw new IllegalArgumentException(
                    "Method not accessable: " + factoryMethodPath, ex
            );
        } catch (ClassNotFoundException ex) {
            throw new IllegalArgumentException(
                    "Class not found: " + factoryMethodPath, ex
            );
        }

        // ------------------------------------------------
        // invoke factory method
        // ------------------------------------------------
        AticVirtualGraph virtualGraph;
        try {
            Object result = factoryMethod.invoke(null, graphUri, config.toString(4), this);

            if (!(result instanceof AticGraph)) {
                throw new IllegalStateException(
                        "Factory method did not return AticGraph: " + factoryMethodPath
                );
            }

            virtualGraph = (AticVirtualGraph) result;

            return virtualGraph;

        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
            throw new IllegalStateException(
                    "Could not invoke factory method: " + factoryMethodPath, ex
            );
        }
    }

    /**
     * Creates a new graph with an auto-generated URN and transfers all triples from the source graph. Returns the new graph's node.
     *
     * @param graph the source graph whose triples will be copied
     * @param ctx the invocation context containing caller information
     * @return the node representing the new graph
     */
    //generates a URI for the graph
    public Node addGraph(Graph graph, InvocationContext ctx) {
        Node graphNode = NodeFactory.createURI(createURN("graph"));
        addGraph(graphNode, graph, ctx);
        return graphNode;
    }

    /**
     * Deletes a graph and all its ACL entries. Clears stored triples for physical graphs or removes from memory for virtual graphs. Requires ADMIN permission.
     * Cannot delete the default graph.
     *
     * @param graphName the URI of the graph to delete
     * @param ctx the invocation context containing caller information
     */
    @Override
    public void removeGraph(Node graphName, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, context);

        boolean enableAC = !isAdmin(ctx);

        String graphUri = graphName.getURI();

        try {

            // ------------------------------------------------
            // check if graph exists
            // ------------------------------------------------
            Object[] graphInfo = db.read(
                    "SELECT id, is_virtual FROM graph WHERE uri = ?",
                    rs -> rs.next() ? new Object[]{rs.getLong(1), rs.getInt(2) == 1} : null,
                    graphUri
            );

            if (graphInfo == null) {
                throw new IllegalStateException("Graph does not exist: " + graphUri);
            }

            long graphId = (Long) graphInfo[0];
            boolean isVirtual = (Boolean) graphInfo[1];

            if (graphName.equals(Quad.defaultGraphIRI)) {
                throw new IllegalArgumentException("Cannot delete default graph");
            }

            // ------------------------------------------------
            // check ADMIN permission
            // ------------------------------------------------
            if (enableAC) {
                Set<Integer> groupIds = ctx.getGroupIds();

                if (groupIds == null || groupIds.isEmpty()) {
                    throw new PermissionDeniedException(
                            "graph",
                            graphId,
                            graphUri,
                            Permission.ADMIN,
                            Set.of()
                    );
                }

                StringBuilder sql = new StringBuilder();
                sql.append("SELECT MAX(permission) ");
                sql.append("FROM graph_acl ");
                sql.append("WHERE graph_id = ? ");
                sql.append("AND group_id IN (");
                sql.append(groupIds.stream().map(g -> "?").collect(Collectors.joining(",")));
                sql.append(")");

                List<Object> params = new ArrayList<>();
                params.add(graphId);
                params.addAll(groupIds.stream().map(g -> (Object) g).toList());

                Integer maxPermCode = db.read(
                        sql.toString(),
                        rs -> rs.next() ? rs.getInt(1) : null,
                        params.toArray()
                );

                Permission effective = (maxPermCode == null)
                        ? null
                        : Permission.fromCode(maxPermCode);

                if (effective == null || effective.getCode() < Permission.ADMIN.getCode()) {
                    throw new PermissionDeniedException(
                            "graph",
                            graphId,
                            graphUri,
                            Permission.ADMIN,
                            effective == null ? Set.of() : Set.of(effective)
                    );
                }
            }

            // ------------------------------------------------
            // handle graph deletion
            // ------------------------------------------------
            if (isVirtual) {
                // remove virtual graph from memory
                virtualGraphMap.remove(graphName);
            } else {
                // clear stored triples
                AticGraph aticGraph = getGraph(graphName, ctx);
                aticGraph.clear(ctx);
            }

            // ------------------------------------------------
            // delete ACL rows
            // ------------------------------------------------
            db.write(
                    "DELETE FROM graph_acl WHERE graph_id = ?",
                    graphId
            );

            // ------------------------------------------------
            // delete graph row
            // ------------------------------------------------
            db.write(
                    "DELETE FROM graph WHERE id = ?",
                    graphId
            );

        } catch (SQLException e) {
            throw new RuntimeException(
                    "Database error while removing graph: " + graphUri,
                    e
            );
        }
    }

    //triple management ======================================================
    /**
     * Adds a single triple to the specified graph. If {@code g} is null, uses the default graph. Throws {@link org.apache.jena.shared.AddDeniedException} for
     * union graphs. Delegates to {@link SqliteAticGraph#add(org.apache.jena.graph.Triple, InvocationContext)} for edit access control.
     *
     * @param g the graph node (null for default graph)
     * @param s the subject node
     * @param p the predicate node
     * @param o the object node
     * @param ctx the invocation context containing caller information
     */
    @Override
    public void add(Node g, Node s, Node p, Node o, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, context);

        //null means default graph
        if (g == null) {
            g = Quad.defaultGraphIRI;
        }

        //ensure that Quad.defaultGraphIRI is used if default graph is mentioned
        g = ensureDefaultGraphIRI(g);

        if (g.equals(Quad.unionGraph)) {
            throw new AddDeniedException("Cannot add to union graph");
        }

        //this handles at least read access control and returns a SqliteAticGraph instance
        //note: graph needs to exist (called addGraph before)
        AticGraph graph = getGraph(g, ctx);

        //delegates to SqliteAticGraph
        //inside we check edit access control
        graph.add(Triple.create(s, p, o), ctx);
    }

    /**
     * Deletes a specific triple from the specified graph. Throws {@link org.apache.jena.shared.DeleteDeniedException} for union graphs. Delegates to
     * {@link SqliteAticGraph#delete(org.apache.jena.graph.Triple, InvocationContext)} for edit access control.
     *
     * @param g the graph node (null for default graph)
     * @param s the subject node
     * @param p the predicate node
     * @param o the object node
     * @param ctx the invocation context containing caller information
     */
    @Override
    public void delete(Node g, Node s, Node p, Node o, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, context);

        //null means default graph
        if (g == null) {
            g = Quad.defaultGraphIRI;
        }

        //ensure that Quad.defaultGraphIRI is used if default graph is mentioned
        g = ensureDefaultGraphIRI(g);

        if (g.equals(Quad.unionGraph)) {
            throw new DeleteDeniedException("Cannot delete from union graph");
        }

        //this handles at least read access control and returns a SqliteAticGraph instance
        //note: graph needs to exist (called addGraph before)
        AticGraph graph = getGraph(g, ctx);

        //delegates to SqliteAticGraph
        //inside we check edit access control
        graph.delete(Triple.create(s, p, o), ctx);
    }

    /**
     * Applies an {@link org.apache.jena.rdfpatch.RDFPatch} by iterating its changes and delegating each add/delete to the corresponding method.
     *
     * @param rdfPatch the RDF patch to apply
     * @param ctx the invocation context containing caller information
     */
    public void apply(RDFPatch rdfPatch, InvocationContext ctx) {
        SqliteAticDatasetGraph dg = this;

        rdfPatch.apply(new RDFChangesBase() {
            @Override
            public void add(Node g, Node s, Node p, Node o) {
                dg.add(g, s, p, o, ctx);
            }

            @Override
            public void delete(Node g, Node s, Node p, Node o) {
                dg.delete(g, s, p, o, ctx);
            }
        });
    }

    /**
     * Deletes all triples matching the given pattern. If {@code g} is {@link org.apache.jena.graph.Node#ANY}, iterates over all accessible graphs. Delegates to
     * {@link SqliteAticGraph#remove(Node, Node, Node, InvocationContext)} for bulk deletion with edit access control.
     *
     * @param g the graph node (Node.ANY for all graphs)
     * @param s the subject node (Node.ANY for any subject)
     * @param p the predicate node (Node.ANY for any predicate)
     * @param o the object node (Node.ANY for any object)
     * @param ctx the invocation context containing caller information
     */
    /*
        Delete any quads matching the pattern.
     */
    @Override
    public void deleteAny(Node g, Node s, Node p, Node o, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, context);

        //null means default graph
        if (g == null) {
            g = Quad.defaultGraphIRI;
        }

        //ensure that Quad.defaultGraphIRI is used if default graph is mentioned
        g = ensureDefaultGraphIRI(g);

        if (g.equals(Quad.unionGraph)) {
            throw new DeleteDeniedException("Cannot delete from union graph");
        }

        // ANY graph
        if (g.equals(Node.ANY)) {
            Iterator<Node> graphIter = listGraphNodes(ctx, true);
            while (graphIter.hasNext()) {
                Node graphNode = graphIter.next();
                AticGraph graph = getGraph(graphNode, ctx);
                graph.remove(s, p, o, ctx);
            }
            return;
        }

        //this handles at least read access control and returns a SqliteAticGraph instance
        //note: graph needs to exist (called addGraph before)
        AticGraph graph = getGraph(g, ctx);

        //delegates to SqliteAticGraph
        //inside we check edit access control
        graph.remove(s, p, o, ctx);
    }

    /**
     * Iterates over all quads matching the given pattern, including the default graph.
     *
     * @param g the graph node (Node.ANY for all graphs)
     * @param s the subject node (Node.ANY for any subject)
     * @param p the predicate node (Node.ANY for any predicate)
     * @param o the object node (Node.ANY for any object)
     * @param ctx the invocation context containing caller information
     * @return an iterator over matching {@link Quad} objects
     */
    /*
        Iterate over all quads in the dataset graph.
     */
    @Override
    public Iterator<Quad> find(Node g, Node s, Node p, Node o, InvocationContext ctx) {
        return findInternal(g, s, p, o, true, ctx);
    }

    /**
     * Iterates over all quads matching the given pattern in named graphs only (excludes the default graph).
     *
     * @param g the graph node (Node.ANY for all named graphs)
     * @param s the subject node (Node.ANY for any subject)
     * @param p the predicate node (Node.ANY for any predicate)
     * @param o the object node (Node.ANY for any object)
     * @param ctx the invocation context containing caller information
     * @return an iterator over matching {@link Quad} objects
     */
    /*
        Find matching quads in the dataset in named graphs (NG) only
     */
    @Override
    public Iterator<Quad> findNG(Node g, Node s, Node p, Node o, InvocationContext ctx) {
        return findInternal(g, s, p, o, false, ctx);
    }

    /**
     * Internal quad iteration implementation. Handles {@link org.apache.jena.graph.Node#ANY} graph by iterating over all accessible graphs, and single-graph
     * queries by delegating to the graph's triple iterator.
     *
     * @param g the graph node
     * @param s the subject node
     * @param p the predicate node
     * @param o the object node
     * @param includeDefaultGraph whether to include the default graph
     * @param ctx the invocation context containing caller information
     * @return an iterator over matching {@link Quad} objects
     */
    private ExtendedIterator<Quad> findInternal(
            Node g,
            Node s,
            Node p,
            Node o,
            boolean includeDefaultGraph,
            InvocationContext ctx
    ) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, context);
        final InvocationContext ctxFinal = ctx;

        final Node G = ensureDefaultGraphIRI(g);

        // =========================================================
        // ANY GRAPH
        // =========================================================
        if (G.equals(Node.ANY)) {

            final Iterator<Node> graphIter = listGraphNodes(ctxFinal);

            return new NiceIterator<Quad>() {

                private boolean defaultGraphPending = includeDefaultGraph;
                private Node currentGraph = null;
                private ExtendedIterator<Triple> tripleIter = db.emptyIterator();

                @Override
                public boolean hasNext() {
                    while (true) {

                        if (tripleIter.hasNext()) {
                            return true;
                        }

                        tripleIter.close();

                        // default graph first
                        if (defaultGraphPending) {
                            defaultGraphPending = false;

                            currentGraph = Quad.defaultGraphIRI;
                            AticGraph defaultGraph = getDefaultGraph(ctxFinal);

                            tripleIter = defaultGraph.find(s, p, o, ctxFinal);
                            continue;
                        }

                        // next named graph
                        if (!graphIter.hasNext()) {
                            return false;
                        }

                        currentGraph = graphIter.next();
                        AticGraph graph = getGraph(currentGraph, ctxFinal);

                        tripleIter = graph.find(s, p, o, ctxFinal);
                    }
                }

                @Override
                public Quad next() {
                    Triple t = tripleIter.next();
                    return Quad.create(currentGraph, t);
                }

                @Override
                public void close() {
                    tripleIter.close();
                    NiceIterator.close(graphIter);
                }
            };
        }

        // =========================================================
        // SINGLE GRAPH
        // =========================================================
        AticGraph graph = getGraph(G, ctxFinal);
        ExtendedIterator<Triple> iter = graph.find(s, p, o, ctxFinal);

        return new NiceIterator<Quad>() {

            @Override
            public boolean hasNext() {
                return iter.hasNext();
            }

            @Override
            public Quad next() {
                return Quad.create(G, iter.next());
            }

            @Override
            public void close() {
                iter.close();
            }
        };
    }

    /**
     * Returns {@code true} if any graph contains the specified triple. For {@link org.apache.jena.graph.Node#ANY}, short-circuits on the first match.
     *
     * @param g the graph node (Node.ANY for all graphs)
     * @param s the subject node
     * @param p the predicate node
     * @param o the object node
     * @param ctx the invocation context containing caller information
     * @return {@code true} if the triple is found
     */
    @Override
    public boolean contains(Node g, Node s, Node p, Node o, InvocationContext ctx) {
        ctx = InvocationContext.fromContextIfEmpty(ctx, context);

        //ensure that Quad.defaultGraphIRI is used if default graph is mentioned
        g = ensureDefaultGraphIRI(g);

        // ANY graph – return true as soon as any graph reports true
        if (g.equals(Node.ANY)) {
            Iterator<Node> graphIter = listGraphNodes(ctx, true);
            while (graphIter.hasNext()) {
                Node graphNode = graphIter.next();
                AticGraph graph = getGraph(graphNode, ctx);   // read‑access check inside getGraph
                if (graph.contains(s, p, o, ctx)) {
                    return true;    // short‑circuit on first match
                }
            }
            return false;           // no graph contained the pattern
        }

        // concrete graph – delegate to the AticGraph implementation
        AticGraph graph = getGraph(g, ctx);
        return graph.contains(s, p, o, ctx);
    }

    /**
     * Clears all data: triples, resources, properties, graphs, and ACLs. Re-bootstraps the default graph. Requires ADMIN permission.
     *
     * @param ctx the invocation context containing caller information
     */
    @Override
    public void clear(InvocationContext ctx) {
        requireAdmin(ctx);

        graphMap.clear();

        try {

            // ---------------------------------------
            // 1) delete all data tables
            // ---------------------------------------
            db.write("DELETE FROM spog");
            db.write("DELETE FROM splg");

            db.write("DELETE FROM resource_acl");
            db.write("DELETE FROM resource");
            db.write("DELETE FROM resource_uri");
            db.write("DELETE FROM resource_spo");
            db.write("DELETE FROM resource_spl");
            db.write("DELETE FROM property");

            // ---------------------------------------
            // 2) delete ALL graphs + ACLs
            // ---------------------------------------
            db.write("DELETE FROM graph_acl");
            db.write("DELETE FROM graph");

            // ---------------------------------------
            // 3) re-bootstrap default graph
            // ---------------------------------------
            bootstrapDefaultGraph();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear dataset", e);
        }
    }

    /**
     * Returns the total number of triples across all accessible graphs.
     *
     * @param ctx the invocation context containing caller information
     * @return the total triple count
     */
    @Override
    public long size(InvocationContext ctx) {
        long total = 0L;
        Iterator<Node> iter = listGraphNodes(ctx);
        while (iter.hasNext()) {
            Node graphNode = iter.next();
            AticGraph g = getGraph(graphNode, ctx);
            total += g.size(ctx);
        }
        return total;
    }

    /**
     * Converts {@code null} to {@link org.apache.jena.graph.Node#ANY} and auto-generated default graph nodes to
     * {@link org.apache.jena.sparql.core.Quad#defaultGraphIRI}.
     *
     * @param g the graph node to normalize
     * @return the normalized graph node
     */
    //note: turns also g == null to Node.ANY
    private Node ensureDefaultGraphIRI(Node g) {
        if (g == null) {
            return Node.ANY;
        }

        if (Quad.isDefaultGraphGenerated(g)) {
            return Quad.defaultGraphIRI;
        }
        return g;
    }

    //----------------------------------------------------------
    //delegate methods
    /**
     * Delegates to {@link #delete(Node, Node, Node, Node, InvocationContext)} using the quad's components.
     */
    @Override
    public void delete(Quad quad, InvocationContext ctx) {
        delete(quad.getGraph(), quad.getSubject(), quad.getPredicate(), quad.getObject(), ctx);
    }

    /**
     * Delegates to {@link #find(Node, Node, Node, Node, InvocationContext)} using the quad's components.
     */
    @Override
    public Iterator<Quad> find(Quad quad, InvocationContext ctx) {
        return find(quad.getGraph(), quad.getSubject(), quad.getPredicate(), quad.getObject(), ctx);
    }

    /**
     * Delegates to {@link #contains(Node, Node, Node, Node, InvocationContext)} using the quad's components.
     */
    @Override
    public boolean contains(Quad quad, InvocationContext ctx) {
        return contains(quad.getGraph(), quad.getSubject(), quad.getPredicate(), quad.getObject(), ctx);
    }

    /**
     * Delegates to {@link #add(Node, Node, Node, Node, InvocationContext)} using the quad's components.
     */
    @Override
    public void add(Quad quad, InvocationContext ctx) {
        add(quad.getGraph(), quad.getSubject(), quad.getPredicate(), quad.getObject(), ctx);
    }

    /**
     * Returns {@code true} if the total triple count across all graphs is zero.
     */
    @Override
    public boolean isEmpty(InvocationContext ctx) {
        return size(ctx) == 0;
    }

    //===================================================================
    //dataset management
    //TODO implement prefixes: SqlitePrefixMap
    //private PrefixMapStd prefixMap = new PrefixMapStd();
    /**
     * Returns the {@link SqlitePrefixMap} instance managing RDF prefix mappings.
     */
    @Override
    public PrefixMap prefixes(InvocationContext ctx) {
        return sqlitePrefixMap;
    }

    /**
     * Throws {@link UnsupportedOperationException} — lock is not yet implemented.
     */
    @Override
    public Lock getLock() {
        //TODO implement getLock - is this even used?
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * Returns the Jena {@link Context} associated with this dataset graph.
     */
    @Override
    public Context getContext() {
        return context;
    }

    /**
     * Marks the dataset graph as closed and clears the virtual graph map.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }

        //better not call this because of TestGraphsTDB2_A table1 test
        //if(db.isInTransaction()) {
        //    db.end();
        //}
        //TODO better close impementation later: closed should be checked in all methods
        virtualGraphMap.clear();

        agentSessionManager.close();

        closed = true;
    }

    /**
     * Returns {@code true} if the dataset graph has been closed.
     */
    public boolean isClosed() {
        return closed;
    }

    //transaction ================================================
    /**
     * Returns {@code true} — transactions are supported.
     */
    @Override
    public boolean supportsTransactions() {
        return true;
    }

    /**
     * Begins a transaction: delegates to the database, starts the RDF patch emitter, clears blank node mapping, and begins on all cached graphs.
     *
     * @param type the transaction type (read or write)
     */
    @Override
    public void begin(TxnType type) {
        db.begin(type);
        rdfPatchEmitter.begin(type);
        bnode2uri.clear();
        for (SqliteAticGraph graph : graphMap.values()) {
            graph.begin();
        }
    }

    /**
     * Promotes the current transaction mode (e.g., read to write). Delegates to the database and RDF patch emitter.
     *
     * @param mode the promotion mode
     * @return {@code true} if the promotion succeeded
     */
    @Override
    public boolean promote(Promote mode) {
        boolean p = db.promote(mode);
        rdfPatchEmitter.promote(mode);
        return p;
    }

    /**
     * Commits the transaction: commits all cached graphs, the database, and the RDF patch emitter.
     */
    @Override
    public void commit() {
        for (SqliteAticGraph graph : graphMap.values()) {
            graph.commit();
        }
        db.commit();
        rdfPatchEmitter.commit();
    }

    /**
     * Aborts the transaction: aborts all cached graphs, the database, and the RDF patch emitter.
     */
    @Override
    public void abort() {
        for (SqliteAticGraph graph : graphMap.values()) {
            graph.abort();
        }
        db.abort();
        rdfPatchEmitter.abort();
    }

    /**
     * Ends the current transaction: delegates to the database and RDF patch emitter, clears blank node mapping.
     */
    @Override
    public void end() {
        db.end();
        rdfPatchEmitter.end();
        bnode2uri.clear();
    }

    /**
     * Returns the current transaction mode (read/write) from the database.
     */
    @Override
    public ReadWrite transactionMode() {
        return db.transactionMode();
    }

    /**
     * Returns the current transaction type from the database.
     */
    @Override
    public TxnType transactionType() {
        return db.transactionType();
    }

    /**
     * Returns {@code true} if a transaction is currently active.
     */
    @Override
    public boolean isInTransaction() {
        return db.isInTransaction();
    }

    //=========================================================
    //rdf patch emitter
    /**
     * Registers an {@link RDFPatchListener} to receive RDF patch events.
     *
     * @param listener the listener to add
     */
    public void addListener(RDFPatchListener listener) {
        rdfPatchEmitter.addListener(listener);
    }

    /**
     * Unregisters an {@link RDFPatchListener}.
     *
     * @param listener the listener to remove
     */
    public void removeListener(RDFPatchListener listener) {
        rdfPatchEmitter.removeListener(listener);
    }


    //==========================================================
    //getter
    /**
     * Returns the underlying {@link Database} instance.
     */
    /*package*/ Database getDatabase() {
        return db;
    }

    /**
     * Returns the blank-node-to-URI mapping used during the current transaction.
     */
    /*package*/ Map<Node, String> getBnode2uri() {
        return bnode2uri;
    }

    /**
     * Returns an unmodifiable view of the virtual graph map.
     */
    public Map<Node, AticVirtualGraph> getVirtualGraphMap() {
        return Collections.unmodifiableMap(virtualGraphMap);
    }

    /**
     * Returns the {@link RDFPatchEmitterTransactional} instance.
     */
    /*package*/ RDFPatchEmitterTransactional getRDFPatchEmitter() {
        return rdfPatchEmitter;
    }

    public AgentSessionManager getAgentSessionManager() {
        return agentSessionManager;
    }

    //==========================================================
    //extra
    /**
     * Generates LUBM benchmark data into a new graph using the UBA benchmark generator.
     *
     * @param graphName the URI of the target graph
     * @param univNum the number of universities
     * @param startIndex the starting index
     * @param seed the random seed
     * @param names whether to generate names
     * @param docs whether to generate documents
     * @param bufferSize the buffer size
     * @param batchSize the batch size
     * @param ctx the invocation context containing caller information
     */
    public void generateLUBMftGraph(Node graphName, int univNum, int startIndex, int seed, boolean names, boolean docs, int bufferSize, int batchSize, InvocationContext ctx) {
        addGraph(graphName, Graph.emptyGraph, ctx);
        SqliteAticGraph graph = (SqliteAticGraph) getGraph(graphName, ctx);

        StreamRDF stream = graph.asStreamRDF(ctx, bufferSize, batchSize, -1);

        Generator generator = new Generator();
        StreamRDFWriter writer = new StreamRDFWriter(stream, generator);

        generator.start(univNum, startIndex, seed, names ? 1 : 0, docs, "http://example.org/", writer);
    }

    /**
     * Parses and executes RML mapping code stored in the database, generates triples, writes them to the target graph, and records execution status (success,
     * modification time, stack trace).
     *
     * @param rmlProjectGraphNode the graph node containing the RML project
     * @param rmlProjectResource the resource identifying the RML project
     * @param bufferSize the buffer size for triple generation
     * @param ctx the invocation context containing caller information
     */
    public synchronized void runRML(Node rmlProjectGraphNode, Node rmlProjectResource, int bufferSize, InvocationContext ctx) {

        //TODO later from vocab class
        Node stackTraceProperty = NodeFactory.createURI("urn:atic:ontology:stackTrace");
        Node modifiedProperty = DCTerms.modified.asNode();
        Node successfulProperty = NodeFactory.createURI("urn:atic:ontology:successful");
        Node defaultGraphProperty = NodeFactory.createURI("urn:atic:ontology:defaultGraph");
        Node clearGraphsProperty = NodeFactory.createURI("urn:atic:ontology:clearGraphs");

        AticGraph rmlProjectGraph = getGraph(rmlProjectGraphNode, ctx);

        String stackTrace = null;
        try {
            Path tempDir = Files.createTempDirectory("atic-run-rml-");

            //Path cwd = Paths.get("").toAbsolutePath();
            //System.out.println(cwd);
            //tempDir = cwd;
            //from project description to mapping file
            //Node rmlCodeLiteral = this.calculateRead(() -> {
            ExtendedIterator<Triple> iter = rmlProjectGraph.find(rmlProjectResource, DCTerms.description.asNode(), Node.ANY, ctx);
            if (!iter.hasNext()) {
                iter.close();
                throw new IllegalArgumentException("No RML code found in: " + rmlProjectResource);
            }
            Node rmlCodeLiteral = iter.next().getObject();
            iter.close();

            //});
            String rmlCode = rmlCodeLiteral.getLiteralLexicalForm();
            Path mappingFile = tempDir.resolve("mapping.ttl");
            File f = mappingFile.toFile();
            FileUtils.writeStringToFile(f, rmlCode, StandardCharsets.UTF_8);

            //default graph setting
            Node defaultGraph = Quad.defaultGraphIRI;
            ExtendedIterator<Triple> iterDefaultGraph = rmlProjectGraph.find(
                    rmlProjectResource,
                    defaultGraphProperty,
                    Node.ANY,
                    ctx);
            try {
                if (iterDefaultGraph.hasNext()) {
                    String defaultGraphStr = iterDefaultGraph.next()
                            .getObject()
                            .getLiteralLexicalForm();

                    if (!defaultGraphStr.isBlank()) {
                        defaultGraph = NodeFactory.createURI(defaultGraphStr);
                    }
                }
            } finally {
                iterDefaultGraph.close();
            }

            //});
            //clear graphs setting
            //boolean clearGraphs = this.calculateRead(() -> {
            boolean clearGraphs = false;
            ExtendedIterator<Triple> iterClearGraphs = rmlProjectGraph.find(
                    rmlProjectResource,
                    clearGraphsProperty,
                    Node.ANY,
                    ctx);

            try {
                if (iterClearGraphs.hasNext()) {
                    clearGraphs = (boolean) iterClearGraphs.next()
                            .getObject()
                            .getLiteral()
                            .getValue();
                }
            } finally {
                iterClearGraphs.close();
            }

            //return b;
            //});
            String baseIRI = mappingFile.toUri().toString();

            //parsing RML code
            List<TriplesMap> triplesMaps;
            try {
                triplesMaps = Parse.parseMappingFile(mappingFile.toAbsolutePath().toString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (triplesMaps.isEmpty()) {
                throw new IllegalArgumentException("No triples map found in RML code of: " + rmlProjectResource);
            }

            burp.Generator generator = new burp.Generator();

            SqliteAticGraph.setDefaultBufferAndBatchSize(bufferSize);

            //keep track of what graphs were cleared
            boolean finalClearGraphs = clearGraphs;
            Set<Node> clearedGraphs = new HashSet<>();

            //this.executeWrite(() -> {
            generator.generate(triplesMaps, baseIRI, defaultGraph, quad -> {

                //clear beforehand
                if (finalClearGraphs) {
                    //only if not cleared yet
                    if (!clearedGraphs.contains(quad.getGraph())) {
                        deleteAny(quad.getGraph(), Node.ANY, Node.ANY, Node.ANY, ctx);
                        clearedGraphs.add(quad.getGraph());
                    }
                }

                //System.out.println(quad);
                add(quad, ctx);
            });
            //});

        } catch (Exception ex) {
            stackTrace = ExceptionUtils.getStackTrace(ex);
        }

        String finalStackTrace = stackTrace;
        boolean successful = stackTrace == null;

        //update project state
        //successful
        this.deleteAny(rmlProjectGraphNode, rmlProjectResource, successfulProperty, Node.ANY, ctx);
        this.add(
                rmlProjectGraphNode,
                rmlProjectResource,
                successfulProperty,
                NodeFactory.createLiteralByValue(successful),
                ctx
        );

        //modification time
        this.deleteAny(rmlProjectGraphNode, rmlProjectResource, modifiedProperty, Node.ANY, ctx);
        this.add(
                rmlProjectGraphNode,
                rmlProjectResource,
                modifiedProperty,
                NodeFactory.createLiteralDT(OffsetDateTime.now().toString(), XSDDatatype.XSDdateTime),
                ctx
        );

        //write error if available
        if (finalStackTrace != null) {
            this.deleteAny(rmlProjectGraphNode, rmlProjectResource, stackTraceProperty, Node.ANY, ctx);
            this.add(rmlProjectGraphNode, rmlProjectResource, stackTraceProperty, NodeFactory.createLiteralString(finalStackTrace), ctx);
        }
        //});
    }

}
