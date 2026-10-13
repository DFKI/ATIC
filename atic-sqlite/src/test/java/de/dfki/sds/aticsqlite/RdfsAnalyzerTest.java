package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.AticGraph;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.RdfsAnalyzer.Cardinality;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.jena.query.TxnType;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.vocabulary.FOAF;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.XSD;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class RdfsAnalyzerTest {

    private SqliteAticDatasetGraph dataset;
    private InvocationContext ctx;

    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);

        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        ctx.transferContext(dataset.getContext());
    }

    @Test
    public void testAnalyzer() {

        AticGraph defaultGraph = dataset.calculateRead(() -> {
            return dataset.getDefaultGraph(ctx);
        });

        String ttl = """
            @prefix foaf: <http://xmlns.com/foaf/0.1/> .
            @prefix ex:   <http://example.org/> .

            ex:alice a foaf:Person ;
                foaf:firstName "Alice" ;
                foaf:lastName "Smith" ;
                foaf:knows ex:bob .

            ex:bob a foaf:Person ;
                foaf:firstName "Bob" ;
                foaf:lastName "Jones" .
        """;

        dataset.executeWrite(() -> {
            try (InputStream in = new ByteArrayInputStream(ttl.getBytes(StandardCharsets.UTF_8))) {
                RDFDataMgr.read(defaultGraph, in, Lang.TTL);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        RdfsAnalyzer analyzer = new RdfsAnalyzer();

        dataset.executeRead(() -> {
            Model model = ModelFactory.createModelForGraph(defaultGraph);

            analyzer.analyze(model);
        });

        Resource alice = ResourceFactory.createResource("http://example.org/alice");
        Resource bob = ResourceFactory.createResource("http://example.org/bob");

        dataset.begin(TxnType.READ);

        assertEquals(7, analyzer.getSize());
        assertEquals(Set.of(FOAF.Person), analyzer.getTypes());
        assertEquals(Set.of(), analyzer.getCustomTypes());
        assertEquals(11, analyzer.getAnalyzedStatementsCount());
        //assertEquals(Set.of(), analyzer.getNotAnalyzedProperties());

        assertEquals(Map.of(FOAF.Person,
                Set.of(FOAF.firstName, FOAF.lastName, FOAF.knows, RDF.type)),
                analyzer.getType2prop());

        assertEquals(Set.of(FOAF.firstName, FOAF.lastName),
                analyzer.getLiteralProperties());
        assertEquals(Set.of(FOAF.knows, RDF.type), analyzer.getResourceProperties());
        assertEquals(Set.of(), analyzer.getDanglingResources());
        assertEquals(Set.of(), analyzer.getMultiTypedInstances());

        assertEquals(Map.of(FOAF.firstName, RdfsAnalyzer.StorageClass.TEXT,
                FOAF.lastName, RdfsAnalyzer.StorageClass.TEXT), analyzer.getPropertyStorageClass());

        assertEquals(Map.of(RdfsAnalyzer.StorageClass.TEXT, Set.of(FOAF.firstName, FOAF.lastName)), analyzer.getStorageClassProperties());

        assertEquals(Map.of(alice, Set.of(FOAF.Person),
                bob, Set.of(FOAF.Person)),
                analyzer.getInstance2types());

        assertEquals(Map.of(FOAF.Person, Set.of(alice, bob)),
                analyzer.getType2instances());

        assertEquals(Map.of(FOAF.firstName, Cardinality.SINGLE, FOAF.lastName, Cardinality.SINGLE, FOAF.knows, Cardinality.SINGLE, RDF.type, Cardinality.MULTI), analyzer.getDomainCardinality());
        assertEquals(Map.of(FOAF.firstName, Cardinality.SINGLE, FOAF.lastName, Cardinality.SINGLE, FOAF.knows, Cardinality.SINGLE, RDF.type, Cardinality.SINGLE), analyzer.getRangeCardinality());
        assertEquals(List.of(), analyzer.getWarnings());

        assertEquals(Map.of(FOAF.firstName, Set.of(FOAF.Person),
                FOAF.lastName, Set.of(FOAF.Person),
                FOAF.knows, Set.of(FOAF.Person),
                RDF.type, Set.of(FOAF.Person)),
                analyzer.getPropertyDomains());

        assertEquals(Map.of(RDF.type, Set.of(), FOAF.knows, Set.of(FOAF.Person),
                FOAF.firstName, Set.of(XSD.xstring), FOAF.lastName, Set.of(XSD.xstring)),
                analyzer.getPropertyRanges());

        assertEquals(Set.of(), analyzer.getExplicitClasses());
        assertEquals(Set.of(),
                analyzer.getExplicitProperties());
        assertEquals(Set.of(), analyzer.getExplicitDatatypes());
        assertEquals(Set.of(), analyzer.getExplicitContainers());
        assertEquals(Set.of(), analyzer.getDanglingResourceProperties());
        assertEquals(Set.of(), analyzer.getUntypedResources());
        assertEquals(Set.of(), analyzer.getUntypedResourceProperties());
        assertEquals(Set.of(), analyzer.getDomainlessMultiCardProperties());
        assertEquals(Set.of(), analyzer.getRangelessMultiCardProperties());
        assertEquals(List.of(), analyzer.getMultiCardProperties());
        assertEquals(Set.of(), analyzer.getBlankNodeStatements());
        assertEquals(Set.of(), analyzer.getSkolemizedBlankNodes());
        assertEquals(Map.of(), analyzer.getSpecialParseProperties());
        assertEquals(Set.of(), analyzer.getLangStringProperties());

        dataset.end();

    }

    @Test
    public void testOntologyConstructorRdfs() {
        AticGraph defaultGraph = dataset.calculateRead(() -> {
            return dataset.getDefaultGraph(ctx);
        });

        String ttl = """
            @prefix foaf: <http://xmlns.com/foaf/0.1/> .
            @prefix ex:   <http://example.org/> .

            ex:alice a foaf:Person ;
                foaf:firstName "Alice" ;
                foaf:lastName "Smith" ;
                foaf:knows ex:bob .

            ex:bob a foaf:Person ;
                foaf:firstName "Bob" ;
                foaf:lastName "Jones" .
        """;

        dataset.executeWrite(() -> {
            try (InputStream in = new ByteArrayInputStream(ttl.getBytes(StandardCharsets.UTF_8))) {
                RDFDataMgr.read(defaultGraph, in, Lang.TTL);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        RdfsAnalyzer analyzer = new RdfsAnalyzer();

        dataset.executeRead(() -> {
            Model model = ModelFactory.createModelForGraph(defaultGraph);

            analyzer.analyze(model);
        });

        DefinitionConstructor oc = new DefinitionConstructor();

        Model ontology = oc.toRdfsOntology("ex", "http://example.org/onto", analyzer);
        
        //ontology.write(System.out, "TTL", null);

        String ontologyTTL = """
                             PREFIX ex:   <http://example.org/onto>
                             PREFIX foaf: <http://xmlns.com/foaf/0.1/>
                             PREFIX owl:  <http://www.w3.org/2002/07/owl#>
                             PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                             PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                             PREFIX xsd:  <http://www.w3.org/2001/XMLSchema#>
                             
                             foaf:Person  rdf:type  rdfs:Class;
                                     rdfs:label  "Person" .
                             
                             foaf:knows  rdf:type  rdf:Property;
                                     rdfs:domain  foaf:Person;
                                     rdfs:label   "Knows";
                                     rdfs:range   foaf:Person .
                             
                             ex:     rdf:type  owl:Ontology .
                             
                             foaf:lastName  rdf:type  rdf:Property;
                                     rdfs:domain  foaf:Person;
                                     rdfs:label   "Last Name";
                                     rdfs:range   xsd:string .
                             
                             foaf:firstName  rdf:type  rdf:Property;
                                     rdfs:domain  foaf:Person;
                                     rdfs:label   "First Name";
                                     rdfs:range   xsd:string .
                             """;

        Model expected = ModelFactory.createDefaultModel();
        expected.read(new StringReader(ontologyTTL), null, "TURTLE");

        assertEquals(expected.size(), ontology.size());
        assertTrue(ontology.isIsomorphicWith(expected));
    }
    
    @Test
    public void testOntologyConstructorOwl() {
        AticGraph defaultGraph = dataset.calculateRead(() -> {
            return dataset.getDefaultGraph(ctx);
        });

        String ttl = """
            @prefix foaf: <http://xmlns.com/foaf/0.1/> .
            @prefix ex:   <http://example.org/> .

            ex:alice a foaf:Person ;
                foaf:firstName "Alice" ;
                foaf:lastName "Smith" ;
                foaf:knows ex:bob .

            ex:bob a foaf:Person ;
                foaf:firstName "Bob" ;
                foaf:lastName "Jones" .
        """;

        dataset.executeWrite(() -> {
            try (InputStream in = new ByteArrayInputStream(ttl.getBytes(StandardCharsets.UTF_8))) {
                RDFDataMgr.read(defaultGraph, in, Lang.TTL);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        RdfsAnalyzer analyzer = new RdfsAnalyzer();

        dataset.executeRead(() -> {
            Model model = ModelFactory.createModelForGraph(defaultGraph);

            analyzer.analyze(model);
        });

        DefinitionConstructor oc = new DefinitionConstructor();

        Model ontology = oc.toOwlOntology("ex", "http://example.org/onto", analyzer);

        String ontologyTTL = """
                             PREFIX ex:   <http://example.org/onto>
                             PREFIX foaf: <http://xmlns.com/foaf/0.1/>
                             PREFIX owl:  <http://www.w3.org/2002/07/owl#>
                             PREFIX rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                             PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                             PREFIX xsd:  <http://www.w3.org/2001/XMLSchema#>
                             
                             foaf:Person  rdf:type  owl:Class;
                                     rdfs:label  "Person" .
                             
                             foaf:knows  rdf:type  owl:ObjectProperty;
                                     rdfs:domain  foaf:Person;
                                     rdfs:label   "Knows";
                                     rdfs:range   foaf:Person .
                             
                             ex:     rdf:type  owl:Ontology .
                             
                             foaf:lastName  rdf:type  owl:DatatypeProperty;
                                     rdfs:domain  foaf:Person;
                                     rdfs:label   "Last Name";
                                     rdfs:range   xsd:string .
                             
                             foaf:firstName  rdf:type  owl:DatatypeProperty;
                                     rdfs:domain  foaf:Person;
                                     rdfs:label   "First Name";
                                     rdfs:range   xsd:string .
                             """;

        Model expected = ModelFactory.createDefaultModel();
        expected.read(new StringReader(ontologyTTL), null, "TURTLE");

        //ontology.write(System.out, "TTL");
        
        assertEquals(expected.size(), ontology.size());
        assertTrue(ontology.isIsomorphicWith(expected));
    }

    /**
     * Verifies that {@code buildSuggestedTemplates} produces the expected
     * JSON for the foaf:Person type used in this test class.
     */
    @Test
    public void testSuggestedTemplateForPerson() {
        AticGraph defaultGraph = dataset.calculateRead(() -> {
            return dataset.getDefaultGraph(ctx);
        });

        String ttl = """
            @prefix foaf: <http://xmlns.com/foaf/0.1/> .
            @prefix ex:   <http://example.org/> .

            ex:alice a foaf:Person ;
                foaf:firstName "Alice" ;
                foaf:lastName "Smith" ;
                foaf:knows ex:bob .

            ex:bob a foaf:Person ;
                foaf:firstName "Bob" ;
                foaf:lastName "Jones" .
        """;

        dataset.executeWrite(() -> {
            try (InputStream in = new ByteArrayInputStream(ttl.getBytes(StandardCharsets.UTF_8))) {
                RDFDataMgr.read(defaultGraph, in, Lang.TTL);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        RdfsAnalyzer analyzer = new RdfsAnalyzer();

        dataset.executeRead(() -> {
            Model model = ModelFactory.createModelForGraph(defaultGraph);
            analyzer.analyze(model);
        });

        DefinitionConstructor dc = new DefinitionConstructor();
        Map<Resource, JSONObject> templates = dc.buildSuggestedTemplates(analyzer);

        // Find the template for foaf:Person by URI (Jena Resource identity differs across model instances)
        String personUri = "http://xmlns.com/foaf/0.1/Person";
        JSONObject actual = null;
        for (Map.Entry<Resource, JSONObject> e : templates.entrySet()) {
            if (personUri.equals(e.getKey().getURI())) {
                actual = e.getValue();
                break;
            }
        }

        assertTrue(actual != null, "Expected template for foaf:Person");

        // Expected output for foaf:Person
        // Properties: foaf:firstName (TEXT, SINGLE), foaf:lastName (TEXT, SINGLE),
        //             foaf:knows (RESOURCE, SINGLE)
        // Since knows has SINGLE cardinality, it gets a simple variable binding (not nested)
        assertEquals("array", actual.getString("$type"));
        assertEquals(10, actual.getInt("$limit"));

        JSONObject map = actual.getJSONObject("$map");
        assertEquals("?person", map.getString("@id"));
        assertEquals("?firstName", map.getString("firstName"));
        assertEquals("?lastName", map.getString("lastName"));
        assertEquals("?knows", map.getString("knows"));

        JSONArray where = actual.getJSONArray("$where");
        assertEquals(4, where.length());
        assertEquals("?person a foaf:Person", where.getString(0));
        // The remaining 3 patterns are from type2prop iteration order (Set), verify content not order
        Set<String> whereSet = new HashSet<>();
        for (int i = 1; i < where.length(); i++) {
            whereSet.add(where.getString(i));
        }
        assertTrue(whereSet.contains("?person foaf:firstName ?firstName"));
        assertTrue(whereSet.contains("?person foaf:lastName ?lastName"));
        assertTrue(whereSet.contains("?person foaf:knows ?knows"));

        JSONObject ctx = actual.getJSONObject("@context");
        assertEquals("http://xmlns.com/foaf/0.1/", ctx.getString("foaf"));
    }

    /**
     * Tests that a multi-cardinality resource property (knows) generates a nested sub-query
     * in the suggested template. With 3 persons knowing each other, foaf:knows becomes
     * MULTI in both domain and range cardinality.
     *
     * <p>Variables are lowercase to avoid clashes. Properties use CURIEs (e.g. {@code foaf:knows}).
     */
    @Test
    public void testSuggestedTemplateForPersonWithMultiCardinalityKnows() {
        AticGraph defaultGraph = dataset.calculateRead(() -> {
            return dataset.getDefaultGraph(ctx);
        });

        // 3 persons who all know each other => foaf:knows is MULTI cardinality
        String ttl = """
            @prefix foaf: <http://xmlns.com/foaf/0.1/> .
            @prefix ex:   <http://example.org/> .

            ex:alice a foaf:Person ;
                foaf:firstName "Alice" ;
                foaf:lastName "Smith" ;
                foaf:knows ex:bob , ex:charlie .

            ex:bob a foaf:Person ;
                foaf:firstName "Bob" ;
                foaf:lastName "Jones" ;
                foaf:knows ex:alice , ex:charlie .

            ex:charlie a foaf:Person ;
                foaf:firstName "Charlie" ;
                foaf:lastName "Brown" ;
                foaf:knows ex:alice , ex:bob .
        """;

        dataset.executeWrite(() -> {
            try (InputStream in = new ByteArrayInputStream(ttl.getBytes(StandardCharsets.UTF_8))) {
                RDFDataMgr.read(defaultGraph, in, Lang.TTL);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        RdfsAnalyzer analyzer = new RdfsAnalyzer();

        dataset.executeRead(() -> {
            Model model = ModelFactory.createModelForGraph(defaultGraph);
            analyzer.analyze(model);
        });

        // Verify cardinality was detected as MULTI
        assertEquals(Cardinality.MULTI, analyzer.getDomainCardinality().get(FOAF.knows));
        assertEquals(Cardinality.MULTI, analyzer.getRangeCardinality().get(FOAF.knows));

        DefinitionConstructor dc = new DefinitionConstructor();
        Map<Resource, JSONObject> templates = dc.buildSuggestedTemplates(analyzer);

        String personUri = "http://xmlns.com/foaf/0.1/Person";
        JSONObject actual = null;
        for (Map.Entry<Resource, JSONObject> e : templates.entrySet()) {
            if (personUri.equals(e.getKey().getURI())) {
                actual = e.getValue();
                break;
            }
        }

        //System.out.println(actual.toString(4));
        
        assertTrue(actual != null, "Expected template for foaf:Person with multi-cardinality knows");

        // Top-level structure
        assertEquals("array", actual.getString("$type"));
        assertEquals(10, actual.getInt("$limit"));

        JSONObject map = actual.getJSONObject("$map");
        assertEquals("?person", map.getString("@id"));
        assertEquals("?firstName", map.getString("firstName"));
        assertEquals("?lastName", map.getString("lastName"));

        // "knows" should be a nested sub-query object (not a simple variable string)
        JSONObject knowsNested = map.getJSONObject("knows");
        assertEquals("array", knowsNested.getString("$type"));
        assertEquals("?knows", knowsNested.getJSONObject("$map").getString("@id"));

        JSONArray knowsWhere = knowsNested.getJSONArray("$where");
        assertEquals(1, knowsWhere.length());
        // Nested query connects parent subject to child via the relationship property (CURIE, no trailing '.')
        assertEquals("?person foaf:knows ?knows", knowsWhere.getString(0));

        // Top-level $where should NOT contain a "knows" triple pattern
        // (instead, the knows property is only in the nested query)
        JSONArray where = actual.getJSONArray("$where");
        assertEquals(3, where.length());
        assertEquals("?person a foaf:Person", where.getString(0));
    }
}
