package de.dfki.sds.aticserver;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Comparator;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OrderedTurtleWriter}.
 */
public class OrderedTurtleWriterUnitTest {

    @BeforeAll
    static void registerWriter() {
        OrderedTurtleWriter.register();
    }

    @Test
    void testTripleOrdering_classesBeforeProperties() {
        Model model = ModelFactory.createDefaultModel();
        Graph graph = model.getGraph();

        String ns = "http://example.org/";
        String owlClass = "http://www.w3.org/2002/07/owl#Class";
        String rdfType = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

        // Add a property declaration first (should end up before classes)
        model.createResource(ns + "hasName")
            .addProperty(model.createProperty(rdfType),
                         model.createResource(ns + "ObjectProperty"));

        // Add a class declaration (should end up before properties)
        model.createResource(ns + "Person")
            .addProperty(model.createProperty(rdfType),
                         model.createResource(owlClass));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OrderedTurtleWriter.writeSorted(out, graph,
            OrderedTurtleWriter.getComparator());

        String ttl = out.toString();

        // Person class declaration should appear before hasName property declaration
        int personIdx = ttl.indexOf("Person");
        int hasNameIdx = ttl.indexOf("hasName");
        assertNotEquals(-1, personIdx);
        assertNotEquals(-1, hasNameIdx);
        assertTrue(personIdx < hasNameIdx,
            "Class should appear before property in sorted output:\n" + ttl);
    }

    @Test
    void testAlphabeticalBySubjectPredicateObject() {
        Model model = ModelFactory.createDefaultModel();
        Graph graph = model.getGraph();

        String ns = "http://example.org/";
        String s = ns + "s";
        String p1 = ns + "predicate_a";
        String p2 = ns + "predicate_b";
        String o1 = ns + "object_a";
        String o2 = ns + "object_b";

        // Add in reverse order
        model.createResource(s)
            .addProperty(model.createProperty(p2),
                         model.createResource(o2));
        model.createResource(s)
            .addProperty(model.createProperty(p1),
                         model.createResource(o1));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OrderedTurtleWriter.writeSorted(out, graph,
            OrderedTurtleWriter.getComparator());

        String ttl = out.toString();

        int p1Idx = ttl.indexOf("predicate_a");
        int p2Idx = ttl.indexOf("predicate_b");
        assertTrue(p1Idx < p2Idx,
            "Predicate A should appear before Predicate B:\n" + ttl);
    }

    @Test
    void testCustomComparator_reversedOrder() {
        Model model = ModelFactory.createDefaultModel();
        Graph graph = model.getGraph();

        String ns = "http://example.org/";
        String reverseP = ns + "zulu";
        String normalP = ns + "alpha";

        // Add normal first
        model.createResource(ns + "s")
            .addProperty(model.createProperty(normalP),
                         model.createResource(ns + "o"));
        model.createResource(ns + "s")
            .addProperty(model.createProperty(reverseP),
                         model.createResource(ns + "o"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OrderedTurtleWriter.writeSorted(out, graph,
            OrderedTurtleWriter.getComparator());

        String ttl = out.toString();
        int normalIdx = ttl.indexOf("alpha");
        int reverseIdx = ttl.indexOf("zulu");
        assertTrue(normalIdx < reverseIdx,
            "Default comparator should sort alpha before zulu:\n" + ttl);

        // Now set a custom comparator that reverses the order
        OrderedTurtleWriter.setComparator(
            Comparator
                .comparing((Triple t) -> t.getPredicate().getURI())
                .reversed()
        );

        out.reset();
        OrderedTurtleWriter.writeSorted(out, graph,
            OrderedTurtleWriter.getComparator());
        ttl = out.toString();

        normalIdx = ttl.indexOf("alpha");
        reverseIdx = ttl.indexOf("zulu");
        assertTrue(reverseIdx < normalIdx,
            "Reversed comparator should sort zulu before alpha:\n" + ttl);

        // Reset to default
        OrderedTurtleWriter.setComparator(
            OrderedTurtleWriter.buildDefaultComparator());
    }
}
