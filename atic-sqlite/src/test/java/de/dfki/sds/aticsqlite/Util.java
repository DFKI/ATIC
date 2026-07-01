

package de.dfki.sds.aticsqlite;

import java.sql.SQLException;
import org.apache.jena.system.Txn;

/**
 *
 */
public class Util {
    
    public static void dumpAclState(SqliteAticDatasetGraph dataset, String label) {
        Txn.executeRead(dataset, () -> {
            try {

                Database db = dataset.getDatabase();

                System.out.println("\n================ " + label + " ================");

                // ---------------------------------------
                // resource_acl (source of truth)
                // ---------------------------------------
                System.out.println("\n--- resource_acl ---");
                db.read(
                        "SELECT resource_id, group_id, permission FROM resource_acl ORDER BY resource_id, group_id",
                        rs -> {
                            while (rs.next()) {
                                System.out.printf(
                                        "resource=%d group=%d perm=%d%n",
                                        rs.getInt(1),
                                        rs.getInt(2),
                                        rs.getInt(3)
                                );
                            }
                            return null;
                        }
                );

                // ---------------------------------------
                // resource_acl_effective (materialized view)
                // ---------------------------------------
                System.out.println("\n--- resource_acl_effective ---");
                db.read(
                        "SELECT resource_id, user_id, permission FROM resource_acl_effective ORDER BY resource_id, user_id",
                        rs -> {
                            while (rs.next()) {
                                System.out.printf(
                                        "resource=%d user=%d perm=%d%n",
                                        rs.getInt(1),
                                        rs.getInt(2),
                                        rs.getInt(3)
                                );
                            }
                            return null;
                        }
                );

                // ---------------------------------------
                // users
                // ---------------------------------------
                System.out.println("\n--- user ---");
                db.read(
                        "SELECT id, username, uri FROM user ORDER BY id",
                        rs -> {
                            while (rs.next()) {
                                System.out.printf(
                                        "user=%d username=%s uri=%s%n",
                                        rs.getInt(1),
                                        rs.getString(2),
                                        rs.getString(3)
                                );
                            }
                            return null;
                        }
                );

                // ---------------------------------------
                // groups (including primary group ownership)
                // ---------------------------------------
                System.out.println("\n--- group (ownership mapping) ---");
                db.read(
                        "SELECT id, groupname, user_id FROM \"group\" ORDER BY id",
                        rs -> {
                            while (rs.next()) {
                                System.out.printf(
                                        "group=%d name=%s owner_user=%s%n",
                                        rs.getInt(1),
                                        rs.getString(2),
                                        rs.getObject(3)
                                );
                            }
                            return null;
                        }
                );

                // ---------------------------------------
                // explicit group membership
                // ---------------------------------------
                System.out.println("\n--- user_group_assignment ---");
                db.read(
                        "SELECT user_id, group_id FROM user_group_assignment ORDER BY user_id, group_id",
                        rs -> {
                            while (rs.next()) {
                                System.out.printf(
                                        "user=%d -> group=%d%n",
                                        rs.getInt(1),
                                        rs.getInt(2)
                                );
                            }
                            return null;
                        }
                );

                // ---------------------------------------
                // resolved membership view (VERY IMPORTANT DEBUG)
                // ---------------------------------------
                System.out.println("\n--- resolved user → group mapping ---");
                db.read(
                        """
                    SELECT u.id AS user_id, uga.group_id
                    FROM user u
                    LEFT JOIN user_group_assignment uga ON uga.user_id = u.id

                    UNION

                    SELECT g.user_id AS user_id, g.id AS group_id
                    FROM "group" g
                    WHERE g.user_id IS NOT NULL

                    ORDER BY user_id, group_id
                    """,
                        rs -> {
                            while (rs.next()) {
                                System.out.printf(
                                        "user=%s -> group=%s%n",
                                        rs.getObject(1),
                                        rs.getObject(2)
                                );
                            }
                            return null;
                        }
                );

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });
    }

}
