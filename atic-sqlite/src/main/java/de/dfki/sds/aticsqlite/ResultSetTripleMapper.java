

package de.dfki.sds.aticsqlite;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.jena.graph.Triple;

/**
 *
 */
@FunctionalInterface
public interface ResultSetTripleMapper {
    Triple map(ResultSet rs) throws SQLException;
}
