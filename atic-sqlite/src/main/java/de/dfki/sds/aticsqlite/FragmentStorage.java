

package de.dfki.sds.aticsqlite;

import java.sql.SQLException;

/**
 * For perstisting fragment settings and for a fragment cache.
 */
public class FragmentStorage {
    
    private SqliteAticDatasetGraph parent;
    private Database db;

    public FragmentStorage(SqliteAticDatasetGraph parent) {
        this.parent = parent;
        this.db = parent.getDatabase();
    }
    
    public void bootstrap() throws SQLException {
        db.writeQuery("create_table_fragment_settings.sql");
        db.writeQuery("create_table_fragment_cache.sql");
    }

}
