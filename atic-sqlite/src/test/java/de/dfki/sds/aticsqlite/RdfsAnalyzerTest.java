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

        OntologyConstructor oc = new OntologyConstructor();

        Model ontology = oc.toRdfsOntology("ex", "http://example.org/onto", analyzer);

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

        OntologyConstructor oc = new OntologyConstructor();

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
}
