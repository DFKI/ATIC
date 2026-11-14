package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.Fragment;
import de.dfki.sds.atic.jenatic.FragmentSettings;
import de.dfki.sds.atic.jenatic.FragmentSettings.FragmentProperty;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.SchemaDO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class FragmentStorageUnitTest {

    private static final String EX = "http://example.org/";

    private SqliteAticDatasetGraph dataset;
    private FragmentStorage fragmentStorage;

    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);
        fragmentStorage = dataset.getFragmentStorage();
    }

    @Test
    public void buildFragmentsTest() {
        User adminUser = dataset.calculateRead(()
                -> dataset.getUser(
                        UserGroupManagement.ADMIN_USERNAME,
                        InvocationContext.EMPTY
                )
        );

        InvocationContext ctx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();

        ctx.transferContext(dataset.getContext());

        String ttl = """
            @prefix ex: <http://example.org/> .
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix schema: <https://schema.org/> .

            ex:Person
                rdfs:label "Person" ;
                rdfs:comment "A human being" ;
                schema:image <http://example.org/images/person.png> .

            ex:alice
                rdf:type ex:Person ;
                rdfs:label "Alice" ;
                rdfs:comment "A software engineer" .

            ex:bob
                rdf:type ex:Person ;
                rdfs:label "Bob" ;
                rdfs:comment "A data scientist" .
            """;

        dataset.executeWrite(()
                -> RDFDataMgr.read(
                        dataset,
                        new StringReader(ttl),
                        null,
                        Lang.TURTLE
                )
        );

        List<Node> persons = List.of(
                NodeFactory.createURI("http://example.org/alice"),
                NodeFactory.createURI("http://example.org/bob")
        );

        List<Fragment> fragments = dataset.calculateRead(
                () -> fragmentStorage.buildFragments(persons, ctx)
        );

        assertEquals(2, fragments.size());

        Fragment alice = fragments.get(0);
        assertEquals("Alice", alice.getLabel());
        assertEquals("A software engineer", alice.getComment());
        assertEquals("Person", alice.getType().getLabel());
        assertEquals("A human being", alice.getType().getComment());
        assertEquals(
                "http://example.org/images/person.png",
                alice.getType().getIcon().getURI()
        );

        Fragment bob = fragments.get(1);
        assertEquals("Bob", bob.getLabel());
        assertEquals("A data scientist", bob.getComment());
        assertEquals("Person", bob.getType().getLabel());
        assertEquals("A human being", bob.getType().getComment());
        assertEquals(
                "http://example.org/images/person.png",
                bob.getType().getIcon().getURI()
        );
    }

    @Test
    public void buildFragmentsMultiLanguageTest() {
        User adminUser = dataset.calculateRead(()
                -> dataset.getUser(
                        UserGroupManagement.ADMIN_USERNAME,
                        InvocationContext.EMPTY
                )
        );

        InvocationContext ctx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();

        ctx.transferContext(dataset.getContext());

        String ttl = """
            @prefix ex: <http://example.org/> .
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix schema: <https://schema.org/> .

            ex:Person
                rdfs:label "Person"@en, "Person"@de, "人"@ja ;
                rdfs:comment "A human being"@en,
                             "Ein Mensch"@de,
                             "人間"@ja ;
                schema:image <http://example.org/images/person.png> .

            ex:alice
                rdf:type ex:Person ;
                rdfs:label "Alice"@en, "Alice"@de, "アリス"@ja ;
                rdfs:comment "A software engineer"@en,
                             "Eine Softwareentwicklerin"@de,
                             "ソフトウェアエンジニア"@ja .

            ex:bob
                rdf:type ex:Person ;
                rdfs:label "Bob"@en, "Bob"@de, "ボブ"@ja ;
                rdfs:comment "A data scientist"@en,
                             "Ein Datenwissenschaftler"@de,
                             "データサイエンティスト"@ja .
            """;

        dataset.executeWrite(()
                -> RDFDataMgr.read(
                        dataset,
                        new StringReader(ttl),
                        null,
                        Lang.TURTLE
                )
        );

        List<Node> persons = List.of(
                NodeFactory.createURI("http://example.org/alice"),
                NodeFactory.createURI("http://example.org/bob")
        );

        List<Fragment> fragments = dataset.calculateRead(
                () -> fragmentStorage.buildFragments(persons, ctx)
        );

        assertEquals(2, fragments.size());

        Locale en = Locale.ENGLISH;
        Locale de = Locale.GERMAN;
        Locale ja = Locale.JAPANESE;

        Fragment alice = fragments.get(0);

        assertEquals("Alice", alice.getLabel(en));
        assertEquals("Alice", alice.getLabel(de));
        assertEquals("アリス", alice.getLabel(ja));

        assertEquals("A software engineer", alice.getComment(en));
        assertEquals("Eine Softwareentwicklerin", alice.getComment(de));
        assertEquals("ソフトウェアエンジニア", alice.getComment(ja));

        assertEquals("Person", alice.getType().getLabel(en));
        assertEquals("Person", alice.getType().getLabel(de));
        assertEquals("人", alice.getType().getLabel(ja));

        Fragment bob = fragments.get(1);

        assertEquals("Bob", bob.getLabel(en));
        assertEquals("Bob", bob.getLabel(de));
        assertEquals("ボブ", bob.getLabel(ja));

        assertEquals("A data scientist", bob.getComment(en));
        assertEquals("Ein Datenwissenschaftler", bob.getComment(de));
        assertEquals("データサイエンティスト", bob.getComment(ja));

        assertEquals("Person", bob.getType().getLabel(en));
        assertEquals("Person", bob.getType().getLabel(de));
        assertEquals("人", bob.getType().getLabel(ja));
    }

    @Test
    public void buildFragmentsWithCycleTest() {
        User adminUser = dataset.calculateRead(()
                -> dataset.getUser(
                        UserGroupManagement.ADMIN_USERNAME,
                        InvocationContext.EMPTY
                )
        );

        InvocationContext ctx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();

        ctx.transferContext(dataset.getContext());

        String ttl = """
            @prefix ex: <http://example.org/> .
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .

            ex:A rdf:type ex:B .
            ex:B rdf:type ex:C .
            ex:C rdf:type ex:A .
            """;

        dataset.executeWrite(()
                -> RDFDataMgr.read(
                        dataset,
                        new StringReader(ttl),
                        null,
                        Lang.TURTLE
                )
        );

        List<Fragment> fragments = dataset.calculateRead(()
                -> fragmentStorage.buildFragments(
                        List.of(NodeFactory.createURI("http://example.org/A")),
                        ctx
                )
        );

        assertEquals(1, fragments.size());

        Fragment a = fragments.get(0);
        assertEquals("http://example.org/A", a.getURI());

        Fragment b = a.getType();
        assertEquals("http://example.org/B", b.getURI());

        Fragment c = b.getType();
        assertEquals("http://example.org/C", c.getURI());

        // C's type points back to A. Cycle detection should prevent infinite recursion.
        assertEquals("http://example.org/A", c.getType().getURI());
    }

    @Test
    public void buildFragmentsWithDifferentLabelPropertiesTest() {
        User adminUser = dataset.calculateRead(()
                -> dataset.getUser(
                        UserGroupManagement.ADMIN_USERNAME,
                        InvocationContext.EMPTY
                )
        );

        InvocationContext ctx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();

        ctx.transferContext(dataset.getContext());

        String ttl = """
            @prefix ex: <http://example.org/> .

            ex:acme
                ex:companyName "Acme Corporation" .

            ex:alice
                ex:personName "Alice Smith" .
            """;

        dataset.executeWrite(() -> {
            RDFDataMgr.read(
                    dataset,
                    new StringReader(ttl),
                    null,
                    Lang.TURTLE
            );

            FragmentSettings settings = FragmentSettings.builder()
                    .addProperty(new FragmentProperty(
                            "label",
                            List.of(
                                    NodeFactory.createURI(EX + "companyName"),
                                    NodeFactory.createURI(EX + "personName")
                            ),
                            true
                    ))
                    .build();

            fragmentStorage.save(settings, ctx);
        });

        List<Fragment> fragments = dataset.calculateRead(()
                -> fragmentStorage.buildFragments(
                        List.of(
                                NodeFactory.createURI(EX + "acme"),
                                NodeFactory.createURI(EX + "alice")
                        ),
                        ctx
                )
        );

        assertEquals(2, fragments.size());
        assertEquals("Acme Corporation", fragments.get(0).getLabel());
        assertEquals("Alice Smith", fragments.get(1).getLabel());
    }

    @Test
    public void buildFragmentsWithPropertyInheritanceTest() {
        User adminUser = dataset.calculateRead(()
                -> dataset.getUser(
                        UserGroupManagement.ADMIN_USERNAME,
                        InvocationContext.EMPTY
                )
        );

        InvocationContext ctx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();

        ctx.transferContext(dataset.getContext());

        // Class hierarchy: ex:Engineer rdfs:subClassOf ex:Person
        // ex:Person has schema:image (icon)
        // ex:bob is an ex:Engineer (which inherits from ex:Person) but has no direct icon
        String ttl = """
            @prefix ex: <http://example.org/> .
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix schema: <https://schema.org/> .

            ex:Person
                rdfs:label "Person"@en ;
                schema:image <http://example.org/images/person.png> .

            ex:Engineer
                rdfs:subClassOf ex:Person .

            ex:bob
                rdf:type ex:Engineer .
            """;

        dataset.executeWrite(()
                -> RDFDataMgr.read(
                        dataset,
                        new StringReader(ttl),
                        null,
                        Lang.TURTLE
                )
        );

        FragmentSettings settings = FragmentSettings.builder()
                .addProperty(new FragmentProperty(
                        "icon",
                        List.of(NodeFactory.createURI(SchemaDO.image.getURI())),
                        false,
                        true
                ))
                .build();

        dataset.executeWrite(()
                -> fragmentStorage.save(settings, ctx)
        );

        List<Fragment> fragments = dataset.calculateRead(()
                -> fragmentStorage.buildFragments(
                        List.of(NodeFactory.createURI(EX + "bob")),
                        ctx
                )
        );

        assertEquals(1, fragments.size());
        // bob should inherit the icon URI from his parent class ex:Person
        assertEquals(
                "http://example.org/images/person.png",
                fragments.get(0).getIcon().getURI()
        );
    }

    @Test
    public void buildFragmentsWithPropertyInheritanceDisabledTest() {
        User adminUser = dataset.calculateRead(()
                -> dataset.getUser(
                        UserGroupManagement.ADMIN_USERNAME,
                        InvocationContext.EMPTY
                )
        );

        InvocationContext ctx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();

        ctx.transferContext(dataset.getContext());

        String ttl = """
            @prefix ex: <http://example.org/> .
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix schema: <https://schema.org/> .

            ex:Person
                rdfs:label "Person"@en ;
                schema:image <http://example.org/images/person.png> .

            ex:Engineer
                rdfs:subClassOf ex:Person .

            ex:bob
                rdf:type ex:Engineer .
            """;

        dataset.executeWrite(()
                -> RDFDataMgr.read(
                        dataset,
                        new StringReader(ttl),
                        null,
                        Lang.TURTLE
                )
        );

        // Create settings with propertyInheritance: false (default)
        FragmentSettings settings = FragmentSettings.builder()
                .addProperty(new FragmentProperty(
                        "icon",
                        List.of(NodeFactory.createURI(SchemaDO.image.getURI())),
                        false,
                        false
                ))
                .build();

        dataset.executeWrite(()
                -> fragmentStorage.save(settings, ctx)
        );

        List<Fragment> fragments = dataset.calculateRead(()
                -> fragmentStorage.buildFragments(
                        List.of(NodeFactory.createURI(EX + "bob")),
                        ctx
                )
        );

        assertEquals(1, fragments.size());
        // bob has no direct icon, and inheritance is disabled
        // getIcon() throws when no icon found, so we access the subgraph directly
        org.apache.jena.rdf.model.Model subgraph = fragments.get(0).getSubgraph();
        assertEquals(0, subgraph.size());
    }

    @Test
    public void buildFragmentsWithPropertyInheritanceDirectValueTest() {
        User adminUser = dataset.calculateRead(()
                -> dataset.getUser(
                        UserGroupManagement.ADMIN_USERNAME,
                        InvocationContext.EMPTY
                )
        );

        InvocationContext ctx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();

        ctx.transferContext(dataset.getContext());

        // ex:alice has a direct schema:image, and her class ex:Person also has schema:image
        String ttl = """
            @prefix ex: <http://example.org/> .
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix schema: <https://schema.org/> .

            ex:Person
                rdfs:label "Person"@en ;
                schema:image <http://example.org/images/person.png> .

            ex:alice
                rdf:type ex:Person ;
                schema:image <http://example.org/images/alice.png> .
            """;

        dataset.executeWrite(()
                -> RDFDataMgr.read(
                        dataset,
                        new StringReader(ttl),
                        null,
                        Lang.TURTLE
                )
        );

        FragmentSettings settings = FragmentSettings.builder()
                .addProperty(new FragmentProperty(
                        "icon",
                        List.of(NodeFactory.createURI(SchemaDO.image.getURI())),
                        false,
                        true
                ))
                .build();

        dataset.executeWrite(()
                -> fragmentStorage.save(settings, ctx)
        );

        List<Fragment> fragments = dataset.calculateRead(()
                -> fragmentStorage.buildFragments(
                        List.of(NodeFactory.createURI(EX + "alice")),
                        ctx
                )
        );

        assertEquals(1, fragments.size());
        // Direct value takes precedence over inherited value
        assertEquals(
                "http://example.org/images/alice.png",
                fragments.get(0).getIcon().getURI()
        );
    }

    @Test
    public void buildFragmentsWithPropertyInheritanceDeepHierarchyTest() {
        User adminUser = dataset.calculateRead(()
                -> dataset.getUser(
                        UserGroupManagement.ADMIN_USERNAME,
                        InvocationContext.EMPTY
                )
        );

        InvocationContext ctx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();

        ctx.transferContext(dataset.getContext());

        // Deep hierarchy: ex:SoftwareEngineer rdfs:subClassOf ex:Engineer rdfs:subClassOf ex:Person
        // Only ex:Person has schema:image (icon)
        // ex:charlie is an ex:SoftwareEngineer (3 levels down)
        String ttl = """
            @prefix ex: <http://example.org/> .
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix schema: <https://schema.org/> .

            ex:Person
                rdfs:label "Person"@en ;
                schema:image <http://example.org/images/person.png> .

            ex:Engineer
                rdfs:subClassOf ex:Person .

            ex:SoftwareEngineer
                rdfs:subClassOf ex:Engineer .

            ex:charlie
                rdf:type ex:SoftwareEngineer .
            """;

        dataset.executeWrite(()
                -> RDFDataMgr.read(
                        dataset,
                        new StringReader(ttl),
                        null,
                        Lang.TURTLE
                )
        );

        FragmentSettings settings = FragmentSettings.builder()
                .addProperty(new FragmentProperty(
                        "icon",
                        List.of(NodeFactory.createURI(SchemaDO.image.getURI())),
                        false,
                        true
                ))
                .build();

        dataset.executeWrite(()
                -> fragmentStorage.save(settings, ctx)
        );

        List<Fragment> fragments = dataset.calculateRead(()
                -> fragmentStorage.buildFragments(
                        List.of(NodeFactory.createURI(EX + "charlie")),
                        ctx
                )
        );

        assertEquals(1, fragments.size());
        // charlie should inherit the icon through 2 levels of subClassOf
        assertEquals(
                "http://example.org/images/person.png",
                fragments.get(0).getIcon().getURI()
        );
    }

    @Test
    public void buildFragmentsForClassWithInheritanceTest() {
        User adminUser = dataset.calculateRead(()
                -> dataset.getUser(
                        UserGroupManagement.ADMIN_USERNAME,
                        InvocationContext.EMPTY
                )
        );

        InvocationContext ctx = new InvocationContext.Builder()
                .fromUser(adminUser)
                .build();

        ctx.transferContext(dataset.getContext());

        // classA is subClassOf classB, classB has schema:image
        // We build a fragment for classA itself (not an instance)
        String ttl = """
            @prefix ex: <http://example.org/> .
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix schema: <https://schema.org/> .

            ex:classB
                rdfs:label "Class B"@en ;
                schema:image <http://example.org/images/class-b.png> .

            ex:classA
                rdfs:subClassOf ex:classB .
            """;

        dataset.executeWrite(()
                -> RDFDataMgr.read(
                        dataset,
                        new StringReader(ttl),
                        null,
                        Lang.TURTLE
                )
        );

        FragmentSettings settings = FragmentSettings.builder()
                .addProperty(new FragmentProperty(
                        "icon",
                        List.of(NodeFactory.createURI(SchemaDO.image.getURI())),
                        false,
                        true
                ))
                .build();

        dataset.executeWrite(()
                -> fragmentStorage.save(settings, ctx)
        );

        List<Fragment> fragments = dataset.calculateRead(()
                -> fragmentStorage.buildFragments(
                        List.of(NodeFactory.createURI(EX + "classA")),
                        ctx
                )
        );

        assertEquals(1, fragments.size());
        // classA should inherit the icon from its parent class classB
        assertEquals(
                "http://example.org/images/class-b.png",
                fragments.get(0).getIcon().getURI()
        );
    }
}
