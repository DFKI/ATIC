package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class PropertyTypeUnitTest {

    private SqliteAticDatasetGraph datasetEnabled;
    private SqliteAticDatasetGraph datasetDisabled;

    private InvocationContext adminCtxEnabled;
    private InvocationContext adminCtxDisabled;

    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        //System.out.println(tempDir);

        Path enabledPath = tempDir.resolve("enabled");
        Files.createDirectories(enabledPath);

        Capabilities cEnabled = Capabilities.builder()
                .propertyTypeAware(true)
                .build();

        datasetEnabled = TL.createDatasetGraph(enabledPath, cEnabled);

        User adminUser = datasetEnabled.calculateRead(()
                -> datasetEnabled.getUser(
                        UserGroupManagement.ADMIN_USERNAME,
                        InvocationContext.EMPTY
                )
        );

        adminCtxEnabled = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();

        // --------
        // disabled
        Path disabledPath = tempDir.resolve("disabled");
        Files.createDirectories(disabledPath);

        Capabilities cDisabled = Capabilities.builder()
                .propertyTypeAware(false)
                .build();

        datasetDisabled = TL.createDatasetGraph(disabledPath, cDisabled);

        adminUser = datasetDisabled.calculateRead(()
                -> datasetDisabled.getUser(
                        UserGroupManagement.ADMIN_USERNAME,
                        InvocationContext.EMPTY
                )
        );

        adminCtxDisabled = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();
    }

    @Test
    void testOk() {
        AticGraph g = datasetEnabled.calculateRead(() -> datasetEnabled.getDefaultGraph(adminCtxEnabled));

        Node p = NodeFactory.createURI("http://example.org/property");

        //two times a literal
        datasetEnabled.executeWrite(() -> {
            g.add(Triple.create(NodeFactory.createURI("http://example.org/s1"), p, NodeFactory.createLiteralString("hello")), adminCtxEnabled);
        });

        datasetEnabled.executeWrite(() -> {
            g.add(Triple.create(NodeFactory.createURI("http://example.org/s2"), p, NodeFactory.createLiteralString("world")), adminCtxEnabled);
        });

        PropertyType type = datasetEnabled.calculateRead(() -> {
            try {

                return datasetEnabled.getDatabase().read(
                        "SELECT type FROM property WHERE uri = ?",
                        rs -> {
                            if (!rs.next()) {
                                return null;
                            }
                            return PropertyType.fromValue(rs.getInt("type"));
                        },
                        "http://example.org/property"
                );

            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });

        //check type
        assertEquals(PropertyType.LITERAL, type);
    }

    @Test
    void testNotOkInOneTransaction() {
        AticGraph g = datasetEnabled.calculateRead(() -> datasetEnabled.getDefaultGraph(adminCtxEnabled));

        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    datasetEnabled.executeWrite(() -> {
                        Node p = NodeFactory.createURI("http://example.org/property");
                        g.add(Triple.create(NodeFactory.createURI("http://example.org/s1"), p, NodeFactory.createLiteralString("hello")), adminCtxEnabled);
                        g.add(Triple.create(NodeFactory.createURI("http://example.org/s2"), p, NodeFactory.createURI("http://example.org/s3")), adminCtxEnabled);
                    });
                }
        );
    }

    @Test
    void testNotOkInSeparateTransaction() {
        AticGraph g = datasetEnabled.calculateRead(() -> datasetEnabled.getDefaultGraph(adminCtxEnabled));

        Node p = NodeFactory.createURI("http://example.org/property");

        datasetEnabled.executeWrite(() -> {
            g.add(Triple.create(NodeFactory.createURI("http://example.org/s1"), p, NodeFactory.createLiteralString("hello")), adminCtxEnabled);
        });

        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    datasetEnabled.executeWrite(() -> {
                        g.add(Triple.create(NodeFactory.createURI("http://example.org/s2"), p, NodeFactory.createURI("http://example.org/s3")), adminCtxEnabled);
                    });
                }
        );
    }

    @Test
    void testNotOkInSeparateTransaction2() {
        AticGraph g = datasetEnabled.calculateRead(() -> datasetEnabled.getDefaultGraph(adminCtxEnabled));

        Node p = NodeFactory.createURI("http://example.org/property");

        //first URI
        datasetEnabled.executeWrite(() -> {
            g.add(Triple.create(NodeFactory.createURI("http://example.org/s2"), p, NodeFactory.createURI("http://example.org/s3")), adminCtxEnabled);
        });

        //second Literal
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    datasetEnabled.executeWrite(() -> {
                        g.add(Triple.create(NodeFactory.createURI("http://example.org/s1"), p, NodeFactory.createLiteralString("hello")), adminCtxEnabled);
                    });
                }
        );
    }

    @Test
    void testFailedTransactionDoesNotChangeExistingPropertyType() {
        AticGraph g = datasetEnabled.calculateRead(() -> datasetEnabled.getDefaultGraph(adminCtxEnabled));

        Node p = NodeFactory.createURI("http://example.org/property");

        // Initial type: LITERAL
        datasetEnabled.executeWrite(() -> {
            g.add(
                    Triple.create(
                            NodeFactory.createURI("http://example.org/s1"),
                            p,
                            NodeFactory.createLiteralString("hello")
                    ),
                    adminCtxEnabled
            );
        });

        // Try to add an incompatible URI value
        assertThrows(
                IllegalArgumentException.class,
                () -> datasetEnabled.executeWrite(() -> {
                    g.add(
                            Triple.create(
                                    NodeFactory.createURI("http://example.org/s2"),
                                    p,
                                    NodeFactory.createURI("http://example.org/s3")
                            ),
                            adminCtxEnabled
                    );
                })
        );

        // The failed transaction must not have changed the property type
        PropertyType type = datasetEnabled.calculateRead(() -> {
            try {
                return datasetEnabled.getDatabase().read(
                        "SELECT type FROM property WHERE uri = ?",
                        rs -> {
                            if (!rs.next()) {
                                return null;
                            }
                            return PropertyType.fromValue(rs.getInt("type"));
                        },
                        "http://example.org/property"
                );
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        });

        assertEquals(PropertyType.LITERAL, type);
    }

    @Test
    void testPropertyTypeCheckDisabled() {
        AticGraph g = datasetDisabled.calculateRead(
                () -> datasetDisabled.getDefaultGraph(adminCtxDisabled)
        );

        Node p = NodeFactory.createURI("http://example.org/property");

        // Different property types in the same transaction must be allowed.
        assertDoesNotThrow(() -> {
            datasetDisabled.executeWrite(() -> {
                g.add(
                        Triple.create(
                                NodeFactory.createURI("http://example.org/s1"),
                                p,
                                NodeFactory.createLiteralString("hello")
                        ),
                        adminCtxDisabled
                );

                g.add(
                        Triple.create(
                                NodeFactory.createURI("http://example.org/s2"),
                                p,
                                NodeFactory.createURI("http://example.org/s3")
                        ),
                        adminCtxDisabled
                );
            });
        });

        // Different property types in separate transactions must also be allowed.
        assertDoesNotThrow(() -> {
            datasetDisabled.executeWrite(() -> {
                g.add(
                        Triple.create(
                                NodeFactory.createURI("http://example.org/s3"),
                                p,
                                NodeFactory.createLiteralString("world")
                        ),
                        adminCtxDisabled
                );
            });
        });

        assertDoesNotThrow(() -> {
            datasetDisabled.executeWrite(() -> {
                g.add(
                        Triple.create(
                                NodeFactory.createURI("http://example.org/s4"),
                                p,
                                NodeFactory.createURI("http://example.org/s5")
                        ),
                        adminCtxDisabled
                );
            });
        });
    }
}
