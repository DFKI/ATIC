

package de.dfki.sds.aticsqlite;

import java.nio.file.Path;

/**
 *
 */
public class TL {
    
    public static Database createDatabase(Path tempDir) {
        Path dbFile = tempDir.resolve("atic.sqlite");
        
        DatabaseOptions options
                = new DatabaseOptions.Builder(dbFile.toString())
                        .build();

        // create transactional database and bootstrap default graph
        Database db = new DatabaseLongLivedConnection(options);
        
        return db;
    }

    public static SqliteAticDatasetGraph createDatasetGraph(Path tempDir) {
        Database db = createDatabase(tempDir);
        return new SqliteAticDatasetGraph(db);
    }
    
}
