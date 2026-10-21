package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.jenatic.Fragment;
import de.dfki.sds.atic.jenatic.FragmentSettings;
import io.json.compare.CompareMode;
import io.json.compare.JSONCompare;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.json.JSONObject;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class FragmentUnitTest {

    private static final String EX = "http://example.org/";

    @Test
    void createsExpectedSubgraph() {
        Fragment fragment = Fragment.builder(EX + "fragment")
                .add(EX + "name",
                        ResourceFactory.createPlainLiteral("Example"))
                .add(EX + "relatedTo",
                        ResourceFactory.createResource(EX + "other"))
                .add(ResourceFactory.createProperty(EX + "count"),
                        ResourceFactory.createTypedLiteral(42))
                .build();

        Model expected = ModelFactory.createDefaultModel();
        Resource subject = expected.createResource(EX + "fragment");

        expected.add(subject,
                expected.createProperty(EX + "name"),
                expected.createLiteral("Example"));

        expected.add(subject,
                expected.createProperty(EX + "relatedTo"),
                expected.createResource(EX + "other"));

        expected.add(subject,
                expected.createProperty(EX + "count"),
                expected.createTypedLiteral(42));

        assertTrue(
                expected.isIsomorphicWith(fragment.getSubgraph()));

        assertEquals(3, fragment.getSubgraph().size());
    }

    @Test
    void everyTripleUsesFragmentAsSubject() {
        Fragment fragment = Fragment.builder(EX + "fragment")
                .add(EX + "p1", EX + "object1")
                .add(EX + "p2", EX + "object2")
                .build();

        Model graph = fragment.getSubgraph();
        Resource subject = graph.createResource(EX + "fragment");

        assertTrue(graph.contains(
                subject, graph.createProperty(EX + "p1"),
                graph.createResource(EX + "object1")));

        assertTrue(graph.contains(
                subject, graph.createProperty(EX + "p2"),
                graph.createResource(EX + "object2")));

        assertEquals(1, graph.listSubjects().toSet().size());
        // Both statements have the same subject.
    }

    @Test
    void acceptsNodeAsSubject() {
        Node subject = NodeFactory.createURI(EX + "nodeSubject");

        Fragment fragment = Fragment.builder(subject)
                .add(EX + "name",
                        ResourceFactory.createPlainLiteral("Node subject"))
                .build();

        Model graph = fragment.getSubgraph();

        assertTrue(graph.contains(
                graph.createResource(EX + "nodeSubject"),
                graph.createProperty(EX + "name"),
                "Node subject"));
    }

    @Test
    void acceptsResourceAsSubject() {
        Resource subject = ResourceFactory.createResource(EX + "resourceSubject");

        Fragment fragment = Fragment.builder(subject)
                .add(EX + "name",
                        ResourceFactory.createPlainLiteral("Resource subject"))
                .build();

        Model graph = fragment.getSubgraph();

        assertTrue(graph.contains(
                graph.createResource(EX + "resourceSubject"),
                graph.createProperty(EX + "name"),
                "Resource subject"));
    }

    @Test
    void preservesLiteralDatatypeAndLanguage() {
        Fragment fragment = Fragment.builder(EX + "fragment")
                .add(EX + "integer",
                        ResourceFactory.createTypedLiteral(123))
                .add(EX + "label",
                        ResourceFactory.createLangLiteral("Hallo", "de"))
                .build();

        Model graph = fragment.getSubgraph();
        Resource subject = graph.createResource(EX + "fragment");

        Literal integer = graph.listObjectsOfProperty(
                subject, graph.createProperty(EX + "integer"))
                .next().asLiteral();

        Literal label = graph.listObjectsOfProperty(
                subject, graph.createProperty(EX + "label"))
                .next().asLiteral();

        assertEquals(123, integer.getInt());
        assertEquals("de", label.getLanguage());
        assertEquals("Hallo", label.getString());
    }

    @Test
    void rejectsLiteralNodeAsSubject() {
        Node literal = NodeFactory.createLiteralString("not a resource");

        assertThrows(
                IllegalArgumentException.class,
                () -> Fragment.builder(literal));
    }

    @Test
    void rejectsNonUriPredicateNode() {
        Node blankNode = NodeFactory.createBlankNode();

        Fragment.Builder builder = Fragment.builder(EX + "fragment");

        assertThrows(
                IllegalArgumentException.class,
                () -> builder.add(
                        blankNode,
                        ResourceFactory.createPlainLiteral("value")));
    }

    @Test
    void cannotAddTriplesAfterBuild() {
        Fragment.Builder builder = Fragment.builder(EX + "fragment")
                .add(EX + "name",
                        ResourceFactory.createPlainLiteral("Original"));

        Fragment fragment = builder.build();
        Model before = fragment.getSubgraph();

        assertThrows(
                IllegalStateException.class,
                () -> builder.add(EX + "extra", EX + "value"));

        assertTrue(before.isIsomorphicWith(fragment.getSubgraph()));
        assertEquals(1, fragment.getSubgraph().size());
    }

    @Test
    void cannotChangeSettingsAfterBuild() {
        Fragment.Builder builder = Fragment.builder(EX + "fragment");
        builder.build();

        assertThrows(
                IllegalStateException.class,
                () -> builder.settings(FragmentSettings.defaultSettings()));
    }

    @Test
    void cannotBuildTwice() {
        Fragment.Builder builder = Fragment.builder(EX + "fragment");

        builder.build();

        assertThrows(
                IllegalStateException.class,
                builder::build);
    }

    @Test
    void modifyingReturnedSubgraphDoesNotChangeFragment() {
        Fragment fragment = Fragment.builder(EX + "fragment")
                .add(EX + "name",
                        ResourceFactory.createPlainLiteral("Original"))
                .build();

        Model exposed = fragment.getSubgraph();

        exposed.add(
                exposed.createResource(EX + "fragment"),
                exposed.createProperty(EX + "injected"),
                exposed.createLiteral("Injected"));

        Model actual = fragment.getSubgraph();

        assertEquals(1, actual.size());
        assertFalse(actual.contains(
                actual.createResource(EX + "fragment"),
                actual.createProperty(EX + "injected")));
    }

    @Test
    void fragmentSubgraphIsIndependentOfOriginalResourceModel() {
        Model source = ModelFactory.createDefaultModel();
        Resource subject = source.createResource(EX + "source");

        Fragment fragment = Fragment.builder(subject)
                .add(EX + "name",
                        ResourceFactory.createPlainLiteral("Snapshot"))
                .build();

        source.add(subject,
                source.createProperty(EX + "later"),
                source.createLiteral("Mutation"));

        Model graph = fragment.getSubgraph();

        assertEquals(1, graph.size());
        assertFalse(graph.contains(
                graph.createResource(EX + "source"),
                graph.createProperty(EX + "later")));
    }

    @Test
    void rejectsNullSubject() {
        assertThrows(
                NullPointerException.class,
                () -> Fragment.builder((String) null));

        assertThrows(
                NullPointerException.class,
                () -> Fragment.builder((Node) null));

        assertThrows(
                NullPointerException.class,
                () -> Fragment.builder((Resource) null));
    }

    @Test
    void convertsFragmentToExpectedJson() {
        FragmentSettings settings = FragmentSettings.builder()
                .addProperty("label", NodeFactory.createURI(EX + "label"), true)
                .addProperty("comment", NodeFactory.createURI(EX + "comment"), true)
                .addProperty("icon", NodeFactory.createURI(EX + "icon"), false)
                .build();

        Fragment fragment = Fragment.builder(EX + "fragment")
                .settings(settings)
                .add(EX + "label",
                        ResourceFactory.createLangLiteral("Example", "en"))
                .add(EX + "comment",
                        ResourceFactory.createLangLiteral("A test fragment", "en"))
                .add(EX + "icon",
                        ResourceFactory.createPlainLiteral("example-icon"))
                .build();

        //fragment.getSubgraph().write(System.out, "TTL");
        JSONObject expected = new JSONObject("""
                                             {
                                                 "@id": "http://example.org/fragment",
                                                 "label": "Example",
                                                 "comment": "A test fragment",
                                                 "icon": "example-icon",
                                                 "@context": {
                                                     "label": {
                                                         "@language": "en",
                                                         "@id": "http://example.org/label"
                                                     },
                                                     "comment": {
                                                         "@language": "en",
                                                         "@id": "http://example.org/comment"
                                                     },
                                                     "icon": {
                                                         "@type": "http://www.w3.org/2001/XMLSchema#string",
                                                         "@id": "http://example.org/icon"
                                                     }
                                                 }
                                             }
                                             """);

        JSONObject actual = fragment.toJsonFragment();

        //System.out.println(actual.toString(4));
        JSONCompare.assertMatches(expected.toString(), actual.toString(),
                Set.of(
                        CompareMode.JSON_ARRAY_NON_EXTENSIBLE,
                        CompareMode.JSON_OBJECT_NON_EXTENSIBLE
                )
        );
    }

    @Test
    void convertsFragmentWithPlainStringsToExpectedJson() {
        FragmentSettings settings = FragmentSettings.builder()
                .addProperty("label", NodeFactory.createURI(EX + "label"), true)
                .addProperty("comment", NodeFactory.createURI(EX + "comment"), true)
                .addProperty("icon", NodeFactory.createURI(EX + "icon"), false)
                .build();

        Fragment fragment = Fragment.builder(EX + "fragment")
                .settings(settings)
                .add(EX + "label",
                        ResourceFactory.createPlainLiteral("Example"))
                .add(EX + "comment",
                        ResourceFactory.createPlainLiteral("A test fragment"))
                .add(EX + "icon",
                        ResourceFactory.createPlainLiteral("example-icon"))
                .build();

        JSONObject expected = new JSONObject("""
            {
                "@id": "http://example.org/fragment",
                "label": "Example",
                "comment": "A test fragment",
                "icon": "example-icon",
                "@context": {
                    "label": {
                        "@type": "http://www.w3.org/2001/XMLSchema#string",
                        "@id": "http://example.org/label"
                    },
                    "comment": {
                        "@type": "http://www.w3.org/2001/XMLSchema#string",
                        "@id": "http://example.org/comment"
                    },
                    "icon": {
                        "@type": "http://www.w3.org/2001/XMLSchema#string",
                        "@id": "http://example.org/icon"
                    }
                }
            }
            """);

        JSONObject actual = fragment.toJsonFragment();

        JSONCompare.assertMatches(expected.toString(), actual.toString(),
                Set.of(
                        CompareMode.JSON_ARRAY_NON_EXTENSIBLE,
                        CompareMode.JSON_OBJECT_NON_EXTENSIBLE
                )
        );
    }

    @Test
    void convertsFragmentWithNestedTypeToExpectedJson() {
        FragmentSettings settings = FragmentSettings.builder()
                .addProperty("label", NodeFactory.createURI(EX + "label"), true)
                .addProperty("comment", NodeFactory.createURI(EX + "comment"), true)
                .addProperty("type", NodeFactory.createURI(EX + "type"), false)
                .build();

        Fragment type = Fragment.builder(EX + "type1")
                .settings(settings)
                .add(EX + "label",
                        ResourceFactory.createLangLiteral("Type label", "en"))
                .add(EX + "comment",
                        ResourceFactory.createLangLiteral("Type comment", "en"))
                .build();

        Fragment fragment = Fragment.builder(EX + "fragment")
                .settings(settings)
                .add(EX + "label",
                        ResourceFactory.createLangLiteral("Example", "en"))
                .add(EX + "comment",
                        ResourceFactory.createLangLiteral("A test fragment", "en"))
                .add(EX + "type",
                        type.asResource())
                .build();

        JSONObject expected = new JSONObject("""
            {
                "@id": "http://example.org/fragment",
                "label": "Example",
                "comment": "A test fragment",
                "type": {
                    "@id": "http://example.org/type1",
                    "label": "Type label",
                    "comment": "Type comment"
                },
                "@context": {
                    "label": {
                        "@id": "http://example.org/label",
                        "@language": "en"
                    },
                    "comment": {
                        "@id": "http://example.org/comment",
                        "@language": "en"
                    },
                    "type": {"@id": "http://example.org/type"}
                }
            }
            """);

        JSONObject actual = fragment.toJsonFragment();

        //System.out.println(actual.toString(4));
        JSONCompare.assertMatches(expected.toString(), actual.toString(),
                Set.of(
                        CompareMode.JSON_ARRAY_NON_EXTENSIBLE,
                        CompareMode.JSON_OBJECT_NON_EXTENSIBLE
                )
        );
    }

    @Test
    void convertsFragmentWithNestedTypeRecursiveContextToExpectedJson() {
        FragmentSettings settings = FragmentSettings.builder()
                .addProperty("label", NodeFactory.createURI(EX + "label"), true)
                .addProperty("comment", NodeFactory.createURI(EX + "comment"), true)
                .addProperty("type", NodeFactory.createURI(EX + "type"), false)
                .build();

        Fragment type = Fragment.builder(EX + "type1")
                .settings(settings)
                .add(EX + "label",
                        ResourceFactory.createLangLiteral("Type label", "en"))
                .add(EX + "comment",
                        ResourceFactory.createLangLiteral("Type comment", "en"))
                .build();

        Fragment fragment = Fragment.builder(EX + "fragment")
                .settings(settings)
                .add(EX + "label",
                        ResourceFactory.createLangLiteral("Example", "en"))
                .add(EX + "comment",
                        ResourceFactory.createLangLiteral("A test fragment", "en"))
                .add(EX + "type",
                        type.asResource())
                .build();

        JSONObject expected = new JSONObject("""
            {
                "@id": "http://example.org/fragment",
                "label": "Example",
                "comment": "A test fragment",
                "type": {
                    "@id": "http://example.org/type1",
                    "label": "Type label",
                    "comment": "Type comment",
                    "@context": {
                        "label": {
                            "@id": "http://example.org/label",
                            "@language": "en"
                        },
                        "comment": {
                            "@id": "http://example.org/comment",
                            "@language": "en"
                        }
                    }
                },
                "@context": {
                    "label": {
                        "@id": "http://example.org/label",
                        "@language": "en"
                    },
                    "comment": {
                        "@id": "http://example.org/comment",
                        "@language": "en"
                    },
                    "type": {"@id": "http://example.org/type"}
                }
            }
            """);

        JSONObject actual = fragment.toJsonFragment(Locale.ENGLISH, true);

        System.out.println(actual.toString(4));

        JSONCompare.assertMatches(expected.toString(), actual.toString(),
                Set.of(
                        CompareMode.JSON_ARRAY_NON_EXTENSIBLE,
                        CompareMode.JSON_OBJECT_NON_EXTENSIBLE
                )
        );
    }

    @Test
    void convertsFragmentToJsonUsingLocale() {
        FragmentSettings settings = FragmentSettings.builder()
                .addProperty("label", NodeFactory.createURI(EX + "label"), true)
                .addProperty("comment", NodeFactory.createURI(EX + "comment"), true)
                .addProperty("icon", NodeFactory.createURI(EX + "icon"), false)
                .build();

        Fragment fragment = Fragment.builder(EX + "fragment")
                .settings(settings)
                .add(EX + "label",
                        ResourceFactory.createLangLiteral("Example", "en"))
                .add(EX + "label",
                        ResourceFactory.createLangLiteral("Beispiel", "de"))
                .add(EX + "comment",
                        ResourceFactory.createLangLiteral("A test fragment", "en"))
                .add(EX + "comment",
                        ResourceFactory.createLangLiteral("Ein Testfragment", "de"))
                .add(EX + "icon",
                        ResourceFactory.createPlainLiteral("example-icon"))
                .build();

        JSONObject english = fragment.toJsonFragment(Locale.ENGLISH);

        assertEquals("Example", english.getString("label"));
        assertEquals("A test fragment", english.getString("comment"));
        assertEquals("en", english.getJSONObject("@context")
                .getJSONObject("label").getString("@language"));
        assertEquals("en", english.getJSONObject("@context")
                .getJSONObject("comment").getString("@language"));

        JSONObject german = fragment.toJsonFragment(Locale.GERMAN);

        assertEquals("Beispiel", german.getString("label"));
        assertEquals("Ein Testfragment", german.getString("comment"));
        assertEquals("de", german.getJSONObject("@context")
                .getJSONObject("label").getString("@language"));
        assertEquals("de", german.getJSONObject("@context")
                .getJSONObject("comment").getString("@language"));

        // Spanish is unavailable; verify the fallback behavior.
        JSONObject spanish = fragment.toJsonFragment(Locale.forLanguageTag("es"));

        //System.out.println("Spanish fallback: " + spanish.toString(4));
        // Assumes the fallback is English.
        assertEquals("Example", spanish.getString("label"));
        assertEquals("A test fragment", spanish.getString("comment"));
        assertEquals("en", spanish.getJSONObject("@context")
                .getJSONObject("label").getString("@language"));
        assertEquals("en", spanish.getJSONObject("@context")
                .getJSONObject("comment").getString("@language"));
    }

    @Test
    void convertsFragmentToJsonLdWithExpectedTriples() {
        FragmentSettings settings = FragmentSettings.builder()
                .addProperty("label", NodeFactory.createURI(EX + "label"), true)
                .addProperty("comment", NodeFactory.createURI(EX + "comment"), true)
                .addProperty("icon", NodeFactory.createURI(EX + "icon"), false)
                .build();

        Fragment fragment = Fragment.builder(EX + "fragment")
                .settings(settings)
                .add(EX + "label",
                        ResourceFactory.createLangLiteral("Example", "en"))
                .add(EX + "comment",
                        ResourceFactory.createLangLiteral("A test fragment", "en"))
                .add(EX + "icon",
                        ResourceFactory.createPlainLiteral("example-icon"))
                .build();

        JSONObject actual = fragment.toJsonFragment();

        Model parsed = ModelFactory.createDefaultModel();

        RDFParser.create()
                .source(new StringReader(actual.toString()))
                .lang(Lang.JSONLD)
                .base(EX)
                .parse(parsed);

        Model expected = ModelFactory.createDefaultModel();
        Resource subject = expected.createResource(EX + "fragment");

        expected.add(subject,
                expected.createProperty(EX + "label"),
                expected.createLiteral("Example", "en"));

        expected.add(subject,
                expected.createProperty(EX + "comment"),
                expected.createLiteral("A test fragment", "en"));

        expected.add(subject,
                expected.createProperty(EX + "icon"),
                expected.createLiteral("example-icon"));

        assertTrue(expected.isIsomorphicWith(parsed));
        assertEquals(3, parsed.size());
    }

    @Test
    void convertsFragmentWithNestedTypeToJsonLdWithExpectedTriples() {
        FragmentSettings settings = FragmentSettings.builder()
                .addProperty("label", NodeFactory.createURI(EX + "label"), true)
                .addProperty("comment", NodeFactory.createURI(EX + "comment"), true)
                .addProperty("type", NodeFactory.createURI(EX + "type"), false)
                .build();

        Fragment type = Fragment.builder(EX + "type1")
                .settings(settings)
                .add(EX + "label",
                        ResourceFactory.createLangLiteral("Type label", "en"))
                .add(EX + "comment",
                        ResourceFactory.createLangLiteral("Type comment", "en"))
                .build();

        Fragment fragment = Fragment.builder(EX + "fragment")
                .settings(settings)
                .add(EX + "label",
                        ResourceFactory.createLangLiteral("Example", "en"))
                .add(EX + "comment",
                        ResourceFactory.createLangLiteral("A test fragment", "en"))
                .add(EX + "type", type.asResource())
                .build();

        JSONObject actual = fragment.toJsonFragment();

        Model parsed = ModelFactory.createDefaultModel();

        RDFParser.create()
                .source(new StringReader(actual.toString()))
                .lang(Lang.JSONLD)
                .base(EX)
                .parse(parsed);

        Model expected = ModelFactory.createDefaultModel();

        Resource subject = expected.createResource(EX + "fragment");
        Resource typeResource = expected.createResource(EX + "type1");

        expected.add(subject,
                expected.createProperty(EX + "label"),
                expected.createLiteral("Example", "en"));

        expected.add(subject,
                expected.createProperty(EX + "comment"),
                expected.createLiteral("A test fragment", "en"));

        expected.add(subject,
                expected.createProperty(EX + "type"),
                typeResource);

        expected.add(typeResource,
                expected.createProperty(EX + "label"),
                expected.createLiteral("Type label", "en"));

        expected.add(typeResource,
                expected.createProperty(EX + "comment"),
                expected.createLiteral("Type comment", "en"));

        assertTrue(expected.isIsomorphicWith(parsed));
        assertEquals(5, parsed.size());
    }

    @Test
    void convertsFragmentWithDefaultSettingsAndNestedTypeToJsonLd() {
        FragmentSettings settings = FragmentSettings.defaultSettings();

        Fragment type = Fragment.builder(EX + "type1")
                .settings(settings)
                .add(RDFS.label.asNode(),
                        ResourceFactory.createLangLiteral("Type label", "en"))
                .add(RDFS.comment.asNode(),
                        ResourceFactory.createLangLiteral("Type comment", "en"))
                .build();

        Fragment fragment = Fragment.builder(EX + "fragment")
                .settings(settings)
                .add(RDFS.label.asNode(),
                        ResourceFactory.createLangLiteral("Example", "en"))
                .add(RDFS.comment.asNode(),
                        ResourceFactory.createLangLiteral("A test fragment", "en"))
                .add(RDF.type.asNode(), type.asResource())
                .build();

        JSONObject actual = fragment.toJsonFragment();

        //System.out.println(actual.toString(4));
        Model parsed = ModelFactory.createDefaultModel();

        RDFParser.create()
                .source(new StringReader(actual.toString()))
                .lang(Lang.JSONLD)
                .base(EX)
                .parse(parsed);

        Model expected = ModelFactory.createDefaultModel();

        Resource subject = expected.createResource(EX + "fragment");
        Resource typeResource = expected.createResource(EX + "type1");

        expected.add(subject, RDF.type, typeResource);
        expected.add(subject, RDFS.label, expected.createLiteral("Example", "en"));
        expected.add(subject, RDFS.comment,
                expected.createLiteral("A test fragment", "en"));

        expected.add(typeResource, RDFS.label,
                expected.createLiteral("Type label", "en"));
        expected.add(typeResource, RDFS.comment,
                expected.createLiteral("Type comment", "en"));

        assertTrue(expected.isIsomorphicWith(parsed));
        assertEquals(5, parsed.size());
    }

    @Test
    void convertsFragmentWithTypedLiteralsToExpectedJsonTypes() {
        FragmentSettings settings = FragmentSettings.builder()
                .addProperty("active", NodeFactory.createURI(EX + "active"), false)
                .addProperty("price", NodeFactory.createURI(EX + "price"), false)
                .addProperty("count", NodeFactory.createURI(EX + "count"), false)
                .addProperty("date", NodeFactory.createURI(EX + "date"), false)
                .build();

        Fragment fragment = Fragment.builder(EX + "fragment")
                .settings(settings)
                .add(EX + "active",
                        ResourceFactory.createTypedLiteral(true))
                .add(EX + "price",
                        ResourceFactory.createTypedLiteral(
                                new BigDecimal("12.50")))
                .add(EX + "count",
                        ResourceFactory.createTypedLiteral(42))
                .add(EX + "date",
                        ResourceFactory.createTypedLiteral(
                                "2026-10-20", XSDDatatype.XSDdate))
                .build();

        JSONObject actual = fragment.toJsonFragment();
        
        System.out.println(actual.toString(4));

        assertEquals("http://example.org/fragment", actual.getString("@id"));

        // Verify native JSON value types.
        assertEquals(Boolean.TRUE, actual.get("active"));
        assertInstanceOf(Number.class, actual.get("price"));
        assertEquals(new BigDecimal("12.5"), new BigDecimal(actual.get("price").toString()));
        assertEquals(42, actual.getInt("count"));
        assertEquals("2026-10-20", actual.getString("date"));

        JSONObject context = actual.getJSONObject("@context");

        assertEquals(
                XSDDatatype.XSDboolean.getURI(),
                context.getJSONObject("active").getString("@type"));

        assertEquals(
                XSDDatatype.XSDdecimal.getURI(),
                context.getJSONObject("price").getString("@type"));

        assertEquals(
                XSDDatatype.XSDint.getURI(),
                context.getJSONObject("count").getString("@type"));

        assertEquals(
                XSDDatatype.XSDdate.getURI(),
                context.getJSONObject("date").getString("@type"));
    }
}
