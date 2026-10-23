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
import org.junit.jupiter.api.Disabled;
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

    @Disabled
    @Test
    public void testRoundtripFoundResources() {
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
            results = dataset.initializeQuery(options, ctx);
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

    @Test
    public void testRoundtripCategories() {
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
            results = dataset.initializeQuery(options, ctx);
        } catch (Exception ex) {
            fail(ex);
        }

        assertTrue(results.isSuccess());
        assertTrue(results.isQueryAccepted());

        String procID = results.getQueryProcessID();

        //TODO not yet implemented
        //do {
        results = dataset.calculateRead(() -> {
            try {
                return dataset.proceedWithQuery(procID);
            } catch (Exception ex) {
                fail(ex);
            }
            return null;
        });

        try {
            Thread.sleep(1000);
        } catch (InterruptedException ex) {
        }

        //} while(!results.isComplete());
        //assertTrue(results.isComplete());
        assertTrue(results.isQueryAccepted());
        assertTrue(results.isSuccess());
        assertNotNull(results.getCategorizedResults());
        assertEquals(4, results.getCategorizedResults().size());
    }

    @Test
    public void testRoundtripCategoriesFragments() {
        //set to admin
        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });
        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();
        ctx.transferContext(dataset.getContext());

        // write data
        String ttl = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix schema: <https://schema.org/> .
            @prefix ex: <urn:> .

            ex:person:alice rdf:type ex:type:Person ;
                rdfs:label "Alice" ;
                rdfs:comment "Alice is a software developer." ;
                schema:image <https://example.org/images/alice.jpg> .

            ex:person:bob rdf:type ex:type:Person ;
                rdfs:label "Bob" ;
                rdfs:comment "Bob is a data engineer." ;
                schema:image <https://example.org/images/bob.jpg> .

            ex:document:doc1 rdf:type ex:type:Document ;
                rdfs:label "Alice and Bob" ;
                rdfs:comment "A document about Alice and Bob." ;
                schema:image <https://example.org/images/doc1.jpg> .

            ex:type:Person rdf:type rdfs:Class ;
                rdfs:label "Person" ;
                rdfs:comment "Represents a person." .

            ex:type:Document rdf:type rdfs:Class ;
                rdfs:label "Document" ;
                rdfs:comment "Represents a document." .
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
            results = dataset.initializeQuery(options, ctx);
        } catch (Exception ex) {
            fail(ex);
        }

        assertTrue(results.isSuccess());
        assertTrue(results.isQueryAccepted());

        String procID = results.getQueryProcessID();

        //TODO not yet implemented
        //do {
        results = dataset.calculateRead(() -> {
            try {
                return dataset.proceedWithQuery(procID);
            } catch (Exception ex) {
                fail(ex);
            }
            return null;
        });

        try {
            Thread.sleep(1000);
        } catch (InterruptedException ex) {
        }

        //} while(!results.isComplete());
        //assertTrue(results.isComplete());
        assertTrue(results.isQueryAccepted());
        assertTrue(results.isSuccess());
        assertNotNull(results.getCategorizedResults());
        assertEquals(4, results.getCategorizedResults().size());
    }
}
