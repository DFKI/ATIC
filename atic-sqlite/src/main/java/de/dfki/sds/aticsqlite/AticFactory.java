package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
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
        if (!location.exists()) {
            location.mkdirs();
        }

        File dbFile = new File(location, "atic.sqlite");

        DatabaseOptions options
                = new DatabaseOptions.Builder(dbFile.toString())
                        .build();

        Database db = new DatabaseLongLivedConnection(options);
        SqliteAticDatasetGraph sqliteAticDatasetGraph = new SqliteAticDatasetGraph(db);

        return DatasetFactory.wrap(sqliteAticDatasetGraph);
    }
    
    /**
     * Create or connect to a ATIC-backed dataset as admin user.
     *
     * @param location a folder
     * @return ATIC-backed dataset at given location from perspective of admin user
     */
    public static Dataset connectDatasetAsAdmin(File location) {
        if (!location.exists()) {
            location.mkdirs();
        }

        File dbFile = new File(location, "atic.sqlite");

        DatabaseOptions options
                = new DatabaseOptions.Builder(dbFile.toString())
                        .build();

        Database db = new DatabaseLongLivedConnection(options);
        SqliteAticDatasetGraph sqliteAticDatasetGraph = new SqliteAticDatasetGraph(db);
        
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
     * Creates a temporary, transactional, ATIC {@link DatasetGraph}.
     * <p>
     * The atic graph is stored in a temporary folder. This fully supports transactions, including abort to roll-back changes.
     * </p>
     *
     * @return a transactional, temporary, modifiable DatasetGraph
     */
    public static DatasetGraph createTxn() {
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
        SqliteAticDatasetGraph sqliteAticDatasetGraph = new SqliteAticDatasetGraph(db);

        return sqliteAticDatasetGraph;
    }

    /**
     * Creates a temporary, transactional, ATIC {@link Dataset}.
     *
     * @return a transactional, temporary, modifiable Dataset with admin user rights
     * @see #createTxn()
     */
    public static Dataset createTxnAdminDataset() {
        DatasetGraph dg = createTxn();

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
