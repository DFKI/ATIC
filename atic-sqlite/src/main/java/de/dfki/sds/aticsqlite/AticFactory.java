package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph.Capabilities;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.sparql.core.DatasetGraph;

/**
 *
 */
public class AticFactory {

    private AticFactory() {
    }

    /**
     * Create or connect to a ATIC-backed dataset.
     *
     * @param location a folder
     * @return ATIC-backed dataset at given location
     */
    public static Dataset connectDataset(File location) {
        return connectDataset(location, Capabilities.DEFAULT);
    }

    /**
     * Create or connect to a ATIC-backed dataset with specific capabilities.
     *
     * @param location a folder
     * @param capabilities dataset capabilities (e.g. rdf-star enabled/disabled)
     * @return ATIC-backed dataset at given location
     */
    public static Dataset connectDataset(File location, Capabilities capabilities) {
        if (!location.exists()) {
            location.mkdirs();
        }

        File dbFile = new File(location, "atic.sqlite");

        DatabaseOptions options
                = new DatabaseOptions.Builder(dbFile.toString())
                        .build();

        Database db = new DatabaseLongLivedConnection(options);
        SqliteAticDatasetGraph sqliteAticDatasetGraph = new SqliteAticDatasetGraph(db, null, capabilities);

        return DatasetFactory.wrap(sqliteAticDatasetGraph);
    }
    
    /**
     * Create or connect to a ATIC-backed dataset as admin user.
     *
     * @param location a folder
     * @return ATIC-backed dataset at given location from perspective of admin user
     */
    public static Dataset connectDatasetAsAdmin(File location) {
        return connectDatasetAsAdmin(location, Capabilities.DEFAULT);
    }

    /**
     * Create or connect to a ATIC-backed dataset as admin user with specific capabilities.
     *
     * @param location a folder
     * @param capabilities dataset capabilities (e.g. rdf-star enabled/disabled)
     * @return ATIC-backed dataset at given location from perspective of admin user
     */
    public static Dataset connectDatasetAsAdmin(File location, Capabilities capabilities) {
        if (!location.exists()) {
            location.mkdirs();
        }

        File dbFile = new File(location, "atic.sqlite");

        DatabaseOptions options
                = new DatabaseOptions.Builder(dbFile.toString())
                        .build();

        Database db = new DatabaseLongLivedConnection(options);
        SqliteAticDatasetGraph sqliteAticDatasetGraph = new SqliteAticDatasetGraph(db, null, capabilities);
        
        //set to admin
        User adminUser = sqliteAticDatasetGraph.calculateRead(() -> {
            return sqliteAticDatasetGraph.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });
        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();
        ctx.transferContext(sqliteAticDatasetGraph.getContext());

        return DatasetFactory.wrap(sqliteAticDatasetGraph);
    }

    /**
     * Create or connect to a ATIC-backed dataset.
     *
     * @param location path to a folder
     * @return ATIC-backed dataset at given location
     * @see #connectDataset(java.io.File)
     */
    public static Dataset connectDataset(String location) {
        return connectDataset(new File(location));
    }

    /**
     * Create or connect to a ATIC-backed dataset with specific capabilities.
     *
     * @param location path to a folder
     * @param capabilities dataset capabilities (e.g. rdf-star enabled/disabled)
     * @return ATIC-backed dataset at given location
     * @see #connectDataset(java.io.File, Capabilities)
     */
    public static Dataset connectDataset(String location, Capabilities capabilities) {
        return connectDataset(new File(location), capabilities);
    }

    /**
     * Creates a temporary, transactional, ATIC {@link DatasetGraph}.
     * <p>
     * The atic graph is stored in a temporary folder. This fully supports transactions, including abort to roll-back changes.
     * </p>
     *
     * @return a transactional, temporary, modifiable DatasetGraph
     */
    public static DatasetGraph createTxn() {
        return createTxn(Capabilities.DEFAULT);
    }

    /**
     * Creates a temporary, transactional, ATIC {@link DatasetGraph} with specific capabilities.
     * <p>
     * The atic graph is stored in a temporary folder. This fully supports transactions, including abort to roll-back changes.
     * </p>
     *
     * @param capabilities dataset capabilities (e.g. rdf-star enabled/disabled)
     * @return a transactional, temporary, modifiable DatasetGraph
     */
    public static DatasetGraph createTxn(Capabilities capabilities) {
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("atic");
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

        File dbFile = tempDir.resolve("atic.sqlite").toFile();

        DatabaseOptions options
                = new DatabaseOptions.Builder(dbFile.getAbsolutePath())
                        .build();

        Database db = new DatabaseLongLivedConnection(options);
        //Database db = new DatabaseConnectionPerTransaction(options);
        SqliteAticDatasetGraph sqliteAticDatasetGraph = new SqliteAticDatasetGraph(db, null, capabilities);

        return sqliteAticDatasetGraph;
    }

    /**
     * Creates a temporary, transactional, ATIC {@link Dataset}.
     *
     * @return a transactional, temporary, modifiable Dataset with admin user rights
     * @see #createTxn()
     */
    public static Dataset createTxnAdminDataset() {
        return createTxnAdminDataset(Capabilities.DEFAULT);
    }

    /**
     * Creates a temporary, transactional, ATIC {@link Dataset} with specific capabilities.
     *
     * @param capabilities dataset capabilities (e.g. rdf-star enabled/disabled)
     * @return a transactional, temporary, modifiable Dataset with admin user rights
     * @see #createTxn(Capabilities)
     */
    public static Dataset createTxnAdminDataset(Capabilities capabilities) {
        DatasetGraph dg = createTxn(capabilities);

        SqliteAticDatasetGraph aticDatasetGraph = (SqliteAticDatasetGraph) dg;

        Dataset ds = DatasetFactory.wrap(dg);

        //set to admin
        User adminUser = aticDatasetGraph.calculateRead(() -> {
            return aticDatasetGraph.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });
        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();
        ctx.transferContext(dg.getContext());

        return ds;
    }
}
