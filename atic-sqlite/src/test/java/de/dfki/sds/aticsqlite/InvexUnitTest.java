package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.invex.AticQueryResults;
import de.dfki.sds.atic.invex.QueryOptions;
import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.io.StringReader;
import java.nio.file.Path;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/**
 *
 */
@EnabledIf("isInvexOnClasspath")
public class InvexUnitTest {

    private SqliteAticDatasetGraph dataset;

    @BeforeEach
    void setup(@TempDir Path tempDir) throws Exception {
        System.out.println(tempDir);
        dataset = TL.createDatasetGraph(tempDir, Capabilities.builder().invexEnabled(true).build());
    }

    static boolean isInvexOnClasspath() {
        try {
            Thread.currentThread()
                    .getContextClassLoader()
                    .loadClass("de.dfki.sds.invex.core.InvexEmbeddedImpl");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Test
    public void isInvexAvailable() {
        assertTrue(dataset.isInvexAvailable());
    }

    @Test
    public void testRoundtrip() {
        //set to admin
        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });
        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();
        ctx.transferContext(dataset.getContext());

        //write data
        String ttl = """
                     <urn:person:alice> <urn:property:name> "alice" .
                     <urn:person:bob> <urn:property:name> "bob" .
                     <urn:document:doc1> <urn:property:name> "alice and bob" .
                     """;
        dataset.executeWrite(() -> {
            AticGraph g = dataset.getDefaultGraph(ctx);
            RDFDataMgr.read(g, new StringReader(ttl), null, Lang.TURTLE);
        });

        //rebuild invex
        try {
            dataset.rebuildInvex();
        } catch (Exception ex) {
            fail(ex);
        }

        // query
        QueryOptions options = new QueryOptions();
        options.setSearchString("alice");
        options.setUserID(adminUser.getId());
        options.setLimit(100);

        AticQueryResults results = null;
        try {
            results = dataset.initializeQuery(options);
        } catch (Exception ex) {
            fail(ex);
        }

        assertTrue(results.isSuccess());
        assertTrue(results.isQueryAccepted());

        String procID = results.getQueryProcessID();
        results = dataset.calculateRead(() -> {
            try {
                return dataset.proceedWithQuery(procID);
            } catch (Exception ex) {
                fail(ex);
            }
            return null;
        });

        assertTrue(results.isQueryAccepted());
        assertTrue(results.isSuccess());

        assertEquals(2, results.getFoundNodes().size());

        assertTrue(results.getFoundNodes().contains(NodeFactory.createURI("urn:person:alice")));
        assertTrue(results.getFoundNodes().contains(NodeFactory.createURI("urn:document:doc1")));
    }

}
