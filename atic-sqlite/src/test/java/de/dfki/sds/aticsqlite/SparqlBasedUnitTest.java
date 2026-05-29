package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.nio.file.Path;
import java.util.Set;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.system.Txn;
import org.apache.jena.update.UpdateExecutionFactory;
import org.apache.jena.update.UpdateFactory;
import org.apache.jena.update.UpdateProcessor;
import org.apache.jena.update.UpdateRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 *
 */
public class SparqlBasedUnitTest {

    private Dataset dataset;

    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        
        SqliteAticDatasetGraph sqliteAticDatasetGraph = TL.createDatasetGraph(tempDir);
        
        //set to admin
        User adminUser = sqliteAticDatasetGraph.calculateRead(() -> {
            return sqliteAticDatasetGraph.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });
        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();
        ctx.transferContext(sqliteAticDatasetGraph.getContext());

        dataset = DatasetFactory.wrap(sqliteAticDatasetGraph);
    }

    
    @Test
    void testInsertDefaultGraph(@TempDir Path tempDir) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT DATA {\n");
        sb.append("    <http://example.org/s> <http://example.org/p> \"l\" .\n");
        sb.append("}\n");
        String update = sb.toString();

        UpdateRequest request = UpdateFactory.create(update);
        UpdateProcessor processor = UpdateExecutionFactory.create(request, dataset);

        Txn.executeWrite(dataset, () -> {
            processor.execute();
        });

        //ok graph is Quad.defaultGraphNodeGenerated, but we turn it to Quad.defaultGraphIRI
    }

    @Test
    void testInsertQuad(@TempDir Path tempDir) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT DATA {\n");
        sb.append("  GRAPH <http://example.org/graph> {\n");
        sb.append("    <http://example.org/s> <http://example.org/p> \"l\" .\n");
        sb.append("  }\n");
        sb.append("}\n");
        String update = sb.toString();

        //have to create it first
        Txn.executeWrite(dataset, () -> {
            dataset.addNamedModel("http://example.org/graph", ModelFactory.createDefaultModel());
        });

        UpdateRequest request = UpdateFactory.create(update);
        UpdateProcessor processor = UpdateExecutionFactory.create(request, dataset);

        Txn.executeWrite(dataset, () -> {
            processor.execute();
        });
    }

    @Test
    void testFind(@TempDir Path tempDir) throws Exception {
        SqliteAticDatasetGraph datasetGraph = (SqliteAticDatasetGraph) dataset.asDatasetGraph();
        
        //set to admin
        User adminUser = datasetGraph.calculateRead(() -> {
            return datasetGraph.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });
        InvocationContext ictx = new InvocationContext.Builder().fromUser(adminUser).build();

        datasetGraph.executeWrite(() -> {
            datasetGraph.addGraph(NodeFactory.createURI("urn:graph:1"), Graph.emptyGraph, ictx);

            datasetGraph.add(
                    NodeFactory.createURI("urn:graph:1"),
                    NodeFactory.createURI("urn:resource:s"),
                    NodeFactory.createURI("urn:resource:p"),
                    NodeFactory.createURI("urn:resource:o"),
                    ictx
            );
        });

        StringBuilder sb = new StringBuilder();
        sb.append("SELECT * {\n");
        sb.append("  GRAPH ?g {\n");
        sb.append("    ?s ?p ?o .\n");
        sb.append("  }\n");
        sb.append("}\n");
        String sparql = sb.toString();

        Query query = QueryFactory.create(sparql);
        QueryExecution qExec = QueryExecutionFactory.create(query, datasetGraph);

        Txn.executeRead(dataset, () -> {

            ResultSet rs = qExec.execSelect();

            assertTrue(rs.hasNext(), "Query should return at least one result");

            QuerySolution sol = rs.next();

            assertEquals("urn:graph:1", sol.getResource("g").getURI());
            assertEquals("urn:resource:s", sol.getResource("s").getURI());
            assertEquals("urn:resource:p", sol.getResource("p").getURI());
            assertEquals("urn:resource:o", sol.getResource("o").getURI());

            assertFalse(rs.hasNext(), "Query should return exactly one result");
        });
    }

    @Test
    void testFindInGraph(@TempDir Path tempDir) throws Exception {
        //have to create it first
        Txn.executeWrite(dataset, () -> {
            dataset.addNamedModel("http://example.org/graph", ModelFactory.createDefaultModel());
        });

        StringBuilder sb = new StringBuilder();
        sb.append("SELECT * {\n");
        sb.append("  GRAPH <http://example.org/graph> {\n");
        sb.append("    ?s ?p ?o .\n");
        sb.append("  }\n");
        sb.append("}\n");
        String sparql = sb.toString();

        Query query = QueryFactory.create(sparql);
        QueryExecution qExec = QueryExecutionFactory.create(query, dataset);

        //ok this calls ExtendedIterator<Triple> find(Node s, Node p, Node o, InvocationContext ctx)
        Txn.executeRead(dataset, () -> {

            ResultSet rs = qExec.execSelect();

            int count = 0;
            while (rs.hasNext()) {
                rs.next();
                count++;
            }

            assertEquals(0, count, "Graph should be empty");
        });
    }

    @Test
    void testInsertAndFindTriple(@TempDir Path tempDir) throws Exception {
        // create the named graph – required before we can INSERT into it
        Txn.executeWrite(dataset, ()
                -> dataset.addNamedModel("http://example.org/graph",
                        ModelFactory.createDefaultModel()));

        // INSERT the triple via SPARQL UPDATE
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT DATA {\n");
        sb.append("  GRAPH <http://example.org/graph> {\n");
        sb.append("    <http://example.org/s> <http://example.org/p> \"l\" .\n");
        sb.append("  }\n");
        sb.append("}\n");
        String update = sb.toString();

        UpdateRequest request = UpdateFactory.create(update);
        UpdateProcessor processor = UpdateExecutionFactory.create(request, dataset);
        Txn.executeWrite(dataset, processor::execute);

        // SELECT the triple back
        StringBuilder qsb = new StringBuilder();
        qsb.append("SELECT * {\n");
        qsb.append("  GRAPH <http://example.org/graph> {\n");
        qsb.append("    ?s ?p ?o .\n");
        qsb.append("  }\n");
        qsb.append("}\n");
        String sparql = qsb.toString();

        Txn.executeRead(dataset, () -> {

            Query query = QueryFactory.create(sparql);
            try (QueryExecution qExec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qExec.execSelect();

                // verify that exactly one solution is returned and that it matches the inserted triple
                assertTrue(rs.hasNext(), "ResultSet should contain a solution");
                QuerySolution sol = rs.next();

                assertEquals("http://example.org/s", sol.getResource("s").getURI(),
                        "Subject URI does not match");
                assertEquals("http://example.org/p", sol.getResource("p").getURI(),
                        "Predicate URI does not match");
                assertEquals("l", sol.getLiteral("o").getString(),
                        "Object literal does not match");

                assertFalse(rs.hasNext(), "ResultSet should contain only one solution");
            }

        });

    }

    @Test
    void testInsertAndFindDefaultGraphTriple(@TempDir Path tempDir) throws Exception {
        // INSERT the triple via SPARQL UPDATE into the default graph
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT DATA {\n");
        sb.append("    <http://example.org/s> <http://example.org/p> \"val\" .\n");
        sb.append("}\n");
        String update = sb.toString();

        UpdateRequest request = UpdateFactory.create(update);
        UpdateProcessor processor = UpdateExecutionFactory.create(request, dataset);

        Txn.executeWrite(dataset, processor::execute);

        // SELECT the triple back from the default graph
        StringBuilder qsb = new StringBuilder();
        qsb.append("SELECT * WHERE {\n");
        qsb.append("    ?s ?p ?o .\n");
        qsb.append("}\n");
        String sparql = qsb.toString();

        Txn.executeRead(dataset, () -> {

            Query query = QueryFactory.create(sparql);
            try (QueryExecution qExec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qExec.execSelect();

                // verify result
                assertTrue(rs.hasNext(), "ResultSet should contain a solution");
                QuerySolution sol = rs.next();

                assertEquals("http://example.org/s", sol.getResource("s").getURI(),
                        "Subject URI does not match");
                assertEquals("http://example.org/p", sol.getResource("p").getURI(),
                        "Predicate URI does not match");
                assertEquals("val", sol.getLiteral("o").getString(),
                        "Object literal does not match");

                assertFalse(rs.hasNext(), "ResultSet should contain only one solution");

            }
        });
    }

    @Test
    void testFindByLiteralObject(@TempDir Path tempDir) throws Exception {
        // create the named graph
        Txn.executeWrite(dataset, ()
                -> dataset.addNamedModel("http://example.org/graph",
                        ModelFactory.createDefaultModel()));

        // insert the triple <s> <p> "l"
        StringBuilder ins = new StringBuilder();
        ins.append("INSERT DATA {\n");
        ins.append("  GRAPH <http://example.org/graph> {\n");
        ins.append("    <http://example.org/s> <http://example.org/p> \"l\" .\n");
        ins.append("  }\n");
        ins.append("}\n");
        UpdateRequest req = UpdateFactory.create(ins.toString());
        UpdateProcessor proc = UpdateExecutionFactory.create(req, dataset);
        Txn.executeWrite(dataset, proc::execute);

        // query that binds the object to the literal "l"
        StringBuilder q = new StringBuilder();
        q.append("SELECT * {\n");
        q.append("  GRAPH <http://example.org/graph> {\n");
        q.append("    ?s ?p \"l\" .\n");
        q.append("  }\n");
        q.append("}\n");
        String sparql = q.toString();

        Txn.executeRead(dataset, () -> {
            Query query = QueryFactory.create(sparql);
            try (QueryExecution qExec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qExec.execSelect();

                // exactly one solution must be returned
                assertTrue(rs.hasNext(), "ResultSet should contain a solution");
                QuerySolution sol = rs.next();

                // verify subject and predicate
                assertEquals("http://example.org/s",
                        sol.getResource("s").getURI(),
                        "Subject URI does not match");
                assertEquals("http://example.org/p",
                        sol.getResource("p").getURI(),
                        "Predicate URI does not match");

                // no further results
                assertFalse(rs.hasNext(), "ResultSet should contain only one solution");
            }
        });
    }

    @Test
    void testFindByTypedLiteral(@TempDir Path tempDir) throws Exception {
        // create the named graph
        Txn.executeWrite(dataset, ()
                -> dataset.addNamedModel("http://example.org/graph",
                        ModelFactory.createDefaultModel()));

        // insert a triple with a typed literal (xsd:integer)
        StringBuilder ins = new StringBuilder();
        ins.append("INSERT DATA {\n");
        ins.append("  GRAPH <http://example.org/graph> {\n");
        ins.append("    <http://example.org/s> <http://example.org/p> \"42\"^^<http://www.w3.org/2001/XMLSchema#integer> .\n");
        ins.append("  }\n");
        ins.append("}\n");
        UpdateRequest req = UpdateFactory.create(ins.toString());
        UpdateProcessor proc = UpdateExecutionFactory.create(req, dataset);
        Txn.executeWrite(dataset, proc::execute);

        // query that binds the object to the same typed literal
        StringBuilder q = new StringBuilder();
        q.append("SELECT * {\n");
        q.append("  GRAPH <http://example.org/graph> {\n");
        q.append("    ?s ?p \"42\"^^<http://www.w3.org/2001/XMLSchema#integer> .\n");
        q.append("  }\n");
        q.append("}\n");
        String sparql = q.toString();

        Txn.executeRead(dataset, () -> {
            Query query = QueryFactory.create(sparql);
            try (QueryExecution qExec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qExec.execSelect();

                assertTrue(rs.hasNext(), "ResultSet should contain a solution");
                QuerySolution sol = rs.next();

                assertEquals("http://example.org/s",
                        sol.getResource("s").getURI(),
                        "Subject URI does not match");
                assertEquals("http://example.org/p",
                        sol.getResource("p").getURI(),
                        "Predicate URI does not match");

                assertFalse(rs.hasNext(), "ResultSet should contain only one solution");
            }
        });
    }

    @Test
    void testFindMultipleLiterals(@TempDir Path tempDir) throws Exception {
        // create the named graph
        Txn.executeWrite(dataset, ()
                -> dataset.addNamedModel("http://example.org/graph",
                        ModelFactory.createDefaultModel()));

        // insert several triples with different subjects and literals
        StringBuilder ins = new StringBuilder();
        ins.append("INSERT DATA {\n");
        ins.append("  GRAPH <http://example.org/graph> {\n");
        ins.append("    <http://example.org/s1> <http://example.org/p> \"a\" .\n");
        ins.append("    <http://example.org/s2> <http://example.org/p> \"b\" .\n");
        ins.append("    <http://example.org/s3> <http://example.org/p> \"a\" .\n");
        ins.append("  }\n");
        ins.append("}\n");
        UpdateRequest req = UpdateFactory.create(ins.toString());
        UpdateProcessor proc = UpdateExecutionFactory.create(req, dataset);
        Txn.executeWrite(dataset, proc::execute);

        // ---- query for literal "a" -------------------------------------------------
        StringBuilder qA = new StringBuilder();
        qA.append("SELECT * {\n");
        qA.append("  GRAPH <http://example.org/graph> {\n");
        qA.append("    ?s ?p \"a\" .\n");
        qA.append("  }\n");
        qA.append("}\n");
        String sparqlA = qA.toString();

        Txn.executeRead(dataset, () -> {
            Query query = QueryFactory.create(sparqlA);
            try (QueryExecution qExec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qExec.execSelect();
                Set<String> subjects = new java.util.HashSet<>();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    subjects.add(sol.getResource("s").getURI());
                    assertEquals("http://example.org/p",
                            sol.getResource("p").getURI(),
                            "Predicate URI does not match for literal \"a\"");
                }
                assertEquals(Set.of("http://example.org/s1", "http://example.org/s3"),
                        subjects,
                        "Incorrect subjects returned for literal \"a\"");
            }
        });

        // ---- query for literal "b" -------------------------------------------------
        StringBuilder qB = new StringBuilder();
        qB.append("SELECT * {\n");
        qB.append("  GRAPH <http://example.org/graph> {\n");
        qB.append("    ?s ?p \"b\" .\n");
        qB.append("  }\n");
        qB.append("}\n");
        String sparqlB = qB.toString();

        Txn.executeRead(dataset, () -> {
            Query query = QueryFactory.create(sparqlB);
            try (QueryExecution qExec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qExec.execSelect();
                Set<String> subjects = new java.util.HashSet<>();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    subjects.add(sol.getResource("s").getURI());
                    assertEquals("http://example.org/p",
                            sol.getResource("p").getURI(),
                            "Predicate URI does not match for literal \"b\"");
                }
                assertEquals(Set.of("http://example.org/s2"),
                        subjects,
                        "Incorrect subjects returned for literal \"b\"");
            }
        });
    }

    @Test
    void testFoafMixedQueries(@TempDir Path tempDir) throws Exception {
        // create the named graph
        Txn.executeWrite(dataset, ()
                -> dataset.addNamedModel("http://example.org/foaf",
                        ModelFactory.createDefaultModel()));

        // insert a small FOAF example containing both SPO and SPL triples
        String insert = """
            INSERT DATA {
              GRAPH <http://example.org/foaf> {
                <http://example.org/alice> a <http://xmlns.com/foaf/0.1/Person> .
                <http://example.org/alice> <http://xmlns.com/foaf/0.1/name> "Alice" .
                <http://example.org/alice> <http://xmlns.com/foaf/0.1/knows> <http://example.org/bob> .
                <http://example.org/bob>   a <http://xmlns.com/foaf/0.1/Person> .
                <http://example.org/bob>   <http://xmlns.com/foaf/0.1/name> "Bob" .
              }
            }
            """;
        UpdateProcessor insProc = UpdateExecutionFactory.create(UpdateFactory.create(insert), dataset);
        Txn.executeWrite(dataset, insProc::execute);

        // 1️⃣  Query for all resources that are a foaf:Person (variable in subject and object)
        String qPerson = """
            SELECT * {
              GRAPH <http://example.org/foaf> {
                ?s a ?type .
              }
            }
            """;
        Txn.executeRead(dataset, () -> {
            Query query = QueryFactory.create(qPerson);
            try (QueryExecution qExec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qExec.execSelect();
                Set<String> subjects = new java.util.HashSet<>();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    subjects.add(sol.getResource("s").getURI());
                    assertEquals("http://xmlns.com/foaf/0.1/Person",
                            sol.getResource("type").getURI(),
                            "Type should be foaf:Person");
                }
                assertEquals(Set.of("http://example.org/alice", "http://example.org/bob"),
                        subjects,
                        "Both Alice and Bob should be returned as foaf:Person");
            }
        });

        // 2️⃣  Query for all names (variable in subject, literal in object)
        String qNames = """
            SELECT * {
              GRAPH <http://example.org/foaf> {
                ?s <http://xmlns.com/foaf/0.1/name> ?name .
              }
            }
            """;
        Txn.executeRead(dataset, () -> {
            Query query = QueryFactory.create(qNames);
            try (QueryExecution qExec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qExec.execSelect();
                Set<String> names = new java.util.HashSet<>();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    names.add(sol.getLiteral("name").getString());
                    assertTrue(sol.getResource("s").getURI().startsWith("http://example.org/"),
                            "Subject should be a FOAF person URI");
                }
                assertEquals(Set.of("Alice", "Bob"), names,
                        "Both Alice and Bob names should be returned");
            }
        });

        // 3️⃣  Query with variable in predicate (subject fixed, object variable)
        String qAliceOutgoing = """
            SELECT * {
              GRAPH <http://example.org/foaf> {
                <http://example.org/alice> ?p ?o .
              }
            }
            """;
        Txn.executeRead(dataset, () -> {
            Query query = QueryFactory.create(qAliceOutgoing);
            try (QueryExecution qExec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qExec.execSelect();
                Set<String> predicates = new java.util.HashSet<>();
                Set<String> objects = new java.util.HashSet<>();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    predicates.add(sol.getResource("p").getURI());
                    if (sol.get("o").isLiteral()) {
                        objects.add(sol.getLiteral("o").getString());
                    } else {
                        objects.add(sol.getResource("o").getURI());
                    }
                }
                assertEquals(Set.of(
                        "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
                        "http://xmlns.com/foaf/0.1/name",
                        "http://xmlns.com/foaf/0.1/knows"),
                        predicates,
                        "Alice should have three outgoing predicates");
                assertTrue(objects.contains("http://example.org/bob")
                        || objects.contains("Alice")
                        || objects.contains("http://xmlns.com/foaf/0.1/Person"),
                        "Objects should include Bob, Alice literal, and Person type");
            }
        });

        // 4️⃣  Query with literal bound and variable in predicate (subject variable)
        String qByLiteral = """
            SELECT * {
              GRAPH <http://example.org/foaf> {
                ?s ?p "Alice" .
              }
            }
            """;
        Txn.executeRead(dataset, () -> {
            Query query = QueryFactory.create(qByLiteral);
            try (QueryExecution qExec = QueryExecutionFactory.create(query, dataset)) {
                ResultSet rs = qExec.execSelect();
                assertTrue(rs.hasNext(), "There should be a triple with literal \"Alice\"");
                QuerySolution sol = rs.next();
                assertEquals("http://example.org/alice", sol.getResource("s").getURI(),
                        "Subject of the literal \"Alice\" should be Alice");
                assertEquals("http://xmlns.com/foaf/0.1/name", sol.getResource("p").getURI(),
                        "Predicate should be foaf:name");
                assertFalse(rs.hasNext(), "Only one triple should match the literal \"Alice\"");
            }
        });
    }

}
