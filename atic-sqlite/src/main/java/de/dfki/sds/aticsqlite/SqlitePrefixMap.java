

package de.dfki.sds.aticsqlite;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import org.apache.jena.riot.system.PrefixMapBase;

public class SqlitePrefixMap extends PrefixMapBase {

    private final SqliteAticDatasetGraph parent;

    public SqlitePrefixMap(SqliteAticDatasetGraph parent) {
        this.parent = parent;
    }

    @Override
    public String get(String prefix) {
        return parent.calculateRead(() -> {
            try {
                return parent.getDatabase().read(
                    "SELECT uri FROM prefixmap WHERE prefix = ? LIMIT 1",
                    rs -> rs.next() ? rs.getString("uri") : null,
                    prefix
                );
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public Map<String, String> getMapping() {
        return parent.calculateRead(() -> {
            try {
                return parent.getDatabase().read(
                    "SELECT prefix, uri FROM prefixmap",
                    rs -> {
                        Map<String, String> map = new HashMap<>();
                        while (rs.next()) {
                            map.put(rs.getString("prefix"), rs.getString("uri"));
                        }
                        return map;
                    }
                );
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void add(String prefix, String iriString) {
        parent.executeWrite(() -> {
            try {
                parent.getDatabase().write(
                    "INSERT OR REPLACE INTO prefixmap(prefix, uri) VALUES (?, ?)",
                    prefix, iriString
                );
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void delete(String prefix) {
        parent.executeWrite(() -> {
            try {
                parent.getDatabase().write(
                    "DELETE FROM prefixmap WHERE prefix = ?",
                    prefix
                );
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void clear() {
        parent.executeWrite(() -> {
            try {
                parent.getDatabase().write("DELETE FROM prefixmap");
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public boolean containsPrefix(String prefix) {
        return parent.calculateRead(() -> {
            try {
                return parent.getDatabase().read(
                    "SELECT 1 FROM prefixmap WHERE prefix = ? LIMIT 1",
                    rs -> rs.next(),
                    prefix
                );
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public boolean isEmpty() {
        return parent.calculateRead(() -> {
            try {
                return parent.getDatabase().read(
                    "SELECT 1 FROM prefixmap LIMIT 1",
                    rs -> !rs.next()
                );
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public int size() {
        return parent.calculateRead(() -> {
            try {
                return parent.getDatabase().read(
                    "SELECT COUNT(*) FROM prefixmap",
                    rs -> rs.next() ? rs.getInt(1) : 0
                );
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }
}