package de.dfki.sds.rdfpatchsqlite;

import io.json.compare.CompareMode;
import io.json.compare.JSONCompare;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.RDFPatchOps;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.json.JSONObject;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class ATICPatchConverterUnitTest {

    private static final boolean PRINT = false;

    public ATICPatchConverterUnitTest() {
    }

    @Test
    public void testAliceNameChange() {
        String trig = """
        @prefix ex: <http://example.org/> .
        @prefix foaf: <http://xmlns.com/foaf/0.1/> .

        ex:alice
            foaf:name "Alice" ;
            foaf:knows ex:bob .

        ex:bob
            foaf:name "Bob" .
        """;

        String patchText = """
        D <http://example.org/alice>
          <http://xmlns.com/foaf/0.1/name>
          "Alice" .

        A <http://example.org/alice>
          <http://xmlns.com/foaf/0.1/name>
          "Alice Smith" .
        """;

        String expectedBody = """
                              {
                                "patch": [{
                                  "op": "replace",
                                  "ref": [
                                    {"@id": "http://example.org/alice"},
                                    "name",
                                    {"@value": "Alice"}
                                  ],
                                  "value": "Alice Smith"
                                }],
                                "@context": {"name": "http://xmlns.com/foaf/0.1/name"}
                              }
                              """;

        test(trig, patchText, expectedBody);
    }

    @Test
    public void testAliceNameChangeJapanese() {
        String trig = """
        @prefix ex: <http://example.org/> .
        @prefix foaf: <http://xmlns.com/foaf/0.1/> .

        ex:alice
            foaf:name "アリス"@ja ;
            foaf:knows ex:bob .

        ex:bob
            foaf:name "Bob" .
        """;

        String patchText = """
        D <http://example.org/alice>
          <http://xmlns.com/foaf/0.1/name>
          "アリス"@ja .

        A <http://example.org/alice>
          <http://xmlns.com/foaf/0.1/name>
          "alice"@de .
        """;

        String expectedBody = """
        {
          "patch": [{
            "op": "replace",
            "ref": [
              {"@id": "http://example.org/alice"},
              "name",
              {
                "@value": "アリス",
                "@language": "ja"
              }
            ],
            "value": { "@value": "alice", "@language": "de" }
          }],
          "@context": {
            "name": "http://xmlns.com/foaf/0.1/name"
          }
        }
        """;

        test(trig, patchText, expectedBody);
    }

    @Test
    public void testNewAliceAndBob() {
        String trig = """
        @prefix ex: <http://example.org/> .
        @prefix foaf: <http://xmlns.com/foaf/0.1/> .
        """;

        String patchText = """
        A <http://example.org/alice>
          <http://xmlns.com/foaf/0.1/name>
          "Alice" .

        A <http://example.org/alice>
          <http://xmlns.com/foaf/0.1/knows>
          <http://example.org/bob> .

        A <http://example.org/bob>
          <http://xmlns.com/foaf/0.1/name>
          "Bob" .
        """;

        String expectedBody = """
                          {
                            "patch": [
                              {
                                "op": "add",
                                "ref": [
                                  {"@id": "http://example.org/alice"}
                                ],
                                "value": {
                                  "name": "Alice"
                                }
                              },
                              {
                                "op": "add",
                                "ref": [
                                  {"@id": "http://example.org/bob"}
                                ],
                                "value": {
                                  "name": "Bob"
                                }
                              },
                              {
                                "op": "add",
                                "ref": [
                                  {"@id": "http://example.org/alice"},
                                  "knows"
                                ],
                                "value": {
                                  "@id": "http://example.org/bob"
                                }
                              }
                            ],
                            "@context": {
                              "name": "http://xmlns.com/foaf/0.1/name",
                              "knows": "http://xmlns.com/foaf/0.1/knows"
                            }
                          }
                          """;

        test(trig, patchText, expectedBody);
    }

    @Test
    public void testNewAliceBobCharlieCircularDependency() {
        String trig = """
        @prefix ex: <http://example.org/> .
        @prefix foaf: <http://xmlns.com/foaf/0.1/> .
        """;

        String patchText = """
        A <http://example.org/alice>
          <http://xmlns.com/foaf/0.1/name>
          "Alice" .

        A <http://example.org/alice>
          <http://xmlns.com/foaf/0.1/age>
          "30"^^<http://www.w3.org/2001/XMLSchema#integer> .

        A <http://example.org/alice>
          <http://xmlns.com/foaf/0.1/mbox>
          <mailto:alice@example.org> .

        A <http://example.org/alice>
          <http://xmlns.com/foaf/0.1/knows>
          <http://example.org/bob> .


        A <http://example.org/bob>
          <http://xmlns.com/foaf/0.1/name>
          "Bob" .

        A <http://example.org/bob>
          <http://xmlns.com/foaf/0.1/title>
          "Engineer" .

        A <http://example.org/bob>
          <http://xmlns.com/foaf/0.1/knows>
          <http://example.org/charlie> .


        A <http://example.org/charlie>
          <http://xmlns.com/foaf/0.1/name>
          "Charlie"@en .

        A <http://example.org/charlie>
          <http://xmlns.com/foaf/0.1/organization>
          "Example Corp" .

        A <http://example.org/charlie>
          <http://xmlns.com/foaf/0.1/knows>
          <http://example.org/alice> .
        """;

        String expectedBody = """
                          {
                            "patch": [
                              {
                                "op": "add",
                                "ref": [{"@id": "http://example.org/alice"}],
                                "value": {
                                  "name": "Alice",
                                  "age": {
                                    "@value": "30",
                                    "@type": "http://www.w3.org/2001/XMLSchema#integer"
                                  }
                                }
                              },
                              {
                                "op": "add",
                                "ref": [{"@id": "mailto:alice@example.org"}],
                                "value": {}
                              },
                              {
                                "op": "add",
                                "ref": [{"@id": "http://example.org/bob"}],
                                "value": {
                                  "name": "Bob",
                                  "title": "Engineer"
                                }
                              },
                              {
                                "op": "add",
                                "ref": [{"@id": "http://example.org/charlie"}],
                                "value": {
                                  "organization": "Example Corp",
                                  "name": {
                                    "@value": "Charlie",
                                    "@language": "en"
                                  }
                                }
                              },
                              {
                                "op": "add",
                                "ref": [
                                  {"@id": "http://example.org/bob"},
                                  "knows"
                                ],
                                "value": {"@id": "http://example.org/charlie"}
                              },
                              {
                                "op": "add",
                                "ref": [
                                  {"@id": "http://example.org/alice"},
                                  "mbox"
                                ],
                                "value": {"@id": "mailto:alice@example.org"}
                              },
                              {
                                "op": "add",
                                "ref": [
                                  {"@id": "http://example.org/charlie"},
                                  "knows"
                                ],
                                "value": {"@id": "http://example.org/alice"}
                              },
                              {
                                "op": "add",
                                "ref": [
                                  {"@id": "http://example.org/alice"},
                                  "knows"
                                ],
                                "value": {"@id": "http://example.org/bob"}
                              }
                            ],
                            "@context": {
                              "name": "http://xmlns.com/foaf/0.1/name",
                              "age": "http://xmlns.com/foaf/0.1/age",
                              "title": "http://xmlns.com/foaf/0.1/title",
                              "organization": "http://xmlns.com/foaf/0.1/organization",
                              "knows": "http://xmlns.com/foaf/0.1/knows",
                              "mbox": "http://xmlns.com/foaf/0.1/mbox"
                            }
                          }
                          """;

        test(trig, patchText, expectedBody);
    }

    @Test
    public void testNewAliceBobCharlieWithMetadataCircularDependency() {
        String trig = """
        @prefix ex: <http://example.org/> .
        @prefix foaf: <http://xmlns.com/foaf/0.1/> .
        @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        """;

        String patchText = """
        A <http://example.org/alice>
          <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>
          <http://xmlns.com/foaf/0.1/Person> .

        A <http://example.org/alice>
          <http://www.w3.org/2004/02/skos/core#prefLabel>
          "Alice"@en .

        A <http://example.org/alice>
          <http://www.w3.org/2000/01/rdf-schema#comment>
          "Alice is a software engineer."@en .

        A <http://example.org/alice>
          <http://xmlns.com/foaf/0.1/img>
          <http://example.org/images/alice.png> .

        A <http://example.org/alice>
          <http://xmlns.com/foaf/0.1/knows>
          <http://example.org/bob> .


        A <http://example.org/bob>
          <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>
          <http://xmlns.com/foaf/0.1/Person> .

        A <http://example.org/bob>
          <http://www.w3.org/2004/02/skos/core#prefLabel>
          "Bob"@en .

        A <http://example.org/bob>
          <http://www.w3.org/2000/01/rdf-schema#comment>
          "Bob works on distributed systems."@en .

        A <http://example.org/bob>
          <http://xmlns.com/foaf/0.1/img>
          <http://example.org/images/bob.png> .

        A <http://example.org/bob>
          <http://xmlns.com/foaf/0.1/knows>
          <http://example.org/charlie> .


        A <http://example.org/charlie>
          <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>
          <http://xmlns.com/foaf/0.1/Person> .

        A <http://example.org/charlie>
          <http://www.w3.org/2004/02/skos/core#prefLabel>
          "Charlie"@en .

        A <http://example.org/charlie>
          <http://www.w3.org/2000/01/rdf-schema#comment>
          "Charlie likes knowledge graphs."@en .

        A <http://example.org/charlie>
          <http://xmlns.com/foaf/0.1/img>
          <http://example.org/images/charlie.png> .

        A <http://example.org/charlie>
          <http://xmlns.com/foaf/0.1/knows>
          <http://example.org/alice> .
        """;

        String expectedBody = """
                              {
                                "patch": [
                                  {
                                    "op": "add",
                                    "ref": [{"@id": "http://example.org/alice"}],
                                    "value": {
                                      "@type": "http://xmlns.com/foaf/0.1/Person",
                                      "label": {
                                        "@value": "Alice",
                                        "@language": "en"
                                      },
                                      "comment": {
                                        "@value": "Alice is a software engineer.",
                                        "@language": "en"
                                      },
                                      "icon": {"@id": "http://example.org/images/alice.png"}
                                    }
                                  },
                                  {
                                    "op": "add",
                                    "ref": [{"@id": "http://example.org/bob"}],
                                    "value": {
                                      "@type": "http://xmlns.com/foaf/0.1/Person",
                                      "label": {
                                        "@value": "Bob",
                                        "@language": "en"
                                      },
                                      "comment": {
                                        "@value": "Bob works on distributed systems.",
                                        "@language": "en"
                                      },
                                      "icon": {"@id": "http://example.org/images/bob.png"}
                                    }
                                  },
                                  {
                                    "op": "add",
                                    "ref": [{"@id": "http://example.org/charlie"}],
                                    "value": {
                                      "@type": "http://xmlns.com/foaf/0.1/Person",
                                      "label": {
                                        "@value": "Charlie",
                                        "@language": "en"
                                      },
                                      "comment": {
                                        "@value": "Charlie likes knowledge graphs.",
                                        "@language": "en"
                                      },
                                      "icon": {"@id": "http://example.org/images/charlie.png"}
                                    }
                                  },
                                  {
                                    "op": "add",
                                    "ref": [
                                      {"@id": "http://example.org/bob"},
                                      "knows"
                                    ],
                                    "value": {"@id": "http://example.org/charlie"}
                                  },
                                  {
                                    "op": "add",
                                    "ref": [
                                      {"@id": "http://example.org/charlie"},
                                      "knows"
                                    ],
                                    "value": {"@id": "http://example.org/alice"}
                                  },
                                  {
                                    "op": "add",
                                    "ref": [
                                      {"@id": "http://example.org/alice"},
                                      "knows"
                                    ],
                                    "value": {"@id": "http://example.org/bob"}
                                  }
                                ],
                                "@context": {"knows": "http://xmlns.com/foaf/0.1/knows"}
                              }
        """;

        test(trig, patchText, expectedBody);
    }

    @Test
    public void testAliceKnowsBobDelete() {
        String trig = """
        @prefix ex: <http://example.org/> .
        @prefix foaf: <http://xmlns.com/foaf/0.1/> .

        ex:alice
            foaf:name "Alice" ;
            foaf:knows ex:bob .

        ex:bob
            foaf:name "Bob" .
        """;

        String patchText = """
        D <http://example.org/alice>
          <http://xmlns.com/foaf/0.1/knows>
          <http://example.org/bob> .
        """;

        String expectedBody = """
                          {
                            "patch": [{
                              "op": "remove",
                              "ref": [
                                {"@id": "http://example.org/alice"},
                                "knows",
                                {"@id": "http://example.org/bob"}
                              ]
                            }],
                            "@context": {"knows": "http://xmlns.com/foaf/0.1/knows"}
                          }
                          """;

        test(trig, patchText, expectedBody);
    }

    @Test
    public void testAliceTypeChange() {
        String trig = """
        @prefix ex: <http://example.org/> .
        @prefix foaf: <http://xmlns.com/foaf/0.1/> .

        ex:alice
            a foaf:Person ;
            foaf:name "Alice" .
        """;

        String patchText = """
        D <http://example.org/alice>
          <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>
          <http://xmlns.com/foaf/0.1/Person> .

        A <http://example.org/alice>
          <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>
          <https://schema.org/Person> .
        """;

        String expectedBody = """
                              {
                                "patch": [{
                                  "op": "replace",
                                  "ref": [
                                    {"@id": "http://example.org/alice"},
                                    "@type",
                                    {"@id": "http://xmlns.com/foaf/0.1/Person"}
                                  ],
                                  "value": {"@id": "https://schema.org/Person"}
                                }],
                                "@context": {}
                              }
                          """;

        test(trig, patchText, expectedBody);
    }

    @Test
    public void testAliceLabelAndCommentChange() {
        String trig = """
        @prefix ex: <http://example.org/> .
        @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

        ex:alice
            skos:prefLabel "Alice"@en ;
            rdfs:comment "A person named Alice."@en .
        """;

        String patchText = """
        D <http://example.org/alice>
          <http://www.w3.org/2004/02/skos/core#prefLabel>
          "Alice"@en .

        A <http://example.org/alice>
          <http://www.w3.org/2004/02/skos/core#prefLabel>
          "Alice Smith"@en .

        D <http://example.org/alice>
          <http://www.w3.org/2000/01/rdf-schema#comment>
          "A person named Alice."@en .

        A <http://example.org/alice>
          <http://www.w3.org/2000/01/rdf-schema#comment>
          "Alice is a software engineer."@en .
        """;

        String expectedBody = """
                          {
                            "patch": [
                              {
                                "op": "replace",
                                "ref": [
                                  {"@id": "http://example.org/alice"},
                                  "label",
                                  {
                                    "@value": "Alice",
                                    "@language": "en"
                                  }
                                ],
                                "value": {
                                  "@value": "Alice Smith",
                                  "@language": "en"
                                }
                              },
                              {
                                "op": "replace",
                                "ref": [
                                  {"@id": "http://example.org/alice"},
                                  "comment",
                                  {
                                    "@value": "A person named Alice.",
                                    "@language": "en"
                                  }
                                ],
                                "value": {
                                  "@value": "Alice is a software engineer.",
                                  "@language": "en"
                                }
                              }
                            ],
                            "@context": {
                              "label": "http://www.w3.org/2004/02/skos/core#prefLabel",
                              "comment": "http://www.w3.org/2000/01/rdf-schema#comment"
                            }
                          }
                          """;

        test(trig, patchText, expectedBody);
    }

    @Test
    public void testAliceMultipleLabelsDeleteOne() {
        String trig = """
        @prefix ex: <http://example.org/> .
        @prefix skos: <http://www.w3.org/2004/02/skos/core#> .

        ex:alice
            skos:prefLabel "Alice"@en ;
            skos:prefLabel "アリス"@ja .
        """;

        String patchText = """
        D <http://example.org/alice>
          <http://www.w3.org/2004/02/skos/core#prefLabel>
          "Alice"@en .
        """;

        String expectedBody = """
                              {
                                "patch": [{
                                  "op": "remove",
                                  "ref": [
                                    {"@id": "http://example.org/alice"},
                                    "label",
                                    {
                                      "@value": "Alice",
                                      "@language": "en"
                                    }
                                  ]
                                }],
                                "@context": {"label": "http://www.w3.org/2004/02/skos/core#prefLabel"}
                              }
                          """;

        test(trig, patchText, expectedBody);
    }

    @Test
    public void testAliceAddExistingHobbies() {
        String trig = """
        @prefix ex: <http://example.org/> .
        @prefix foaf: <http://xmlns.com/foaf/0.1/> .
        @prefix schema: <https://schema.org/> .

        ex:alice
            foaf:name "Alice" .

        ex:hiking
            a schema:Thing ;
            schema:name "Hiking" .

        ex:reading
            a schema:Thing ;
            schema:name "Reading" .

        ex:cooking
            a schema:Thing ;
            schema:name "Cooking" .
        """;

        String patchText = """
        A <http://example.org/alice>
          <https://schema.org/hobby>
          <http://example.org/hiking> .

        A <http://example.org/alice>
          <https://schema.org/hobby>
          <http://example.org/reading> .

        A <http://example.org/alice>
          <https://schema.org/hobby>
          <http://example.org/cooking> .
        """;

        String expectedBody = """
                              {
                                "patch": [{
                                  "op": "add",
                                  "ref": [
                                    {"@id": "http://example.org/alice"},
                                    "hobby"
                                  ],
                                  "value": [
                                    {"@id": "http://example.org/hiking"},
                                    {"@id": "http://example.org/reading"},
                                    {"@id": "http://example.org/cooking"}
                                  ]
                                }],
                                "@context": {"hobby": "https://schema.org/hobby"}
                              }
                          """;

        test(trig, patchText, expectedBody);
    }

    @Test
    public void testAliceRemoveExistingHobbies() {
        String trig = """
        @prefix ex: <http://example.org/> .
        @prefix foaf: <http://xmlns.com/foaf/0.1/> .
        @prefix schema: <https://schema.org/> .

        ex:alice
            foaf:name "Alice" ;
            schema:hobby ex:hiking ;
            schema:hobby ex:reading ;
            schema:hobby ex:cooking .

        ex:hiking
            a schema:Thing ;
            schema:name "Hiking" .

        ex:reading
            a schema:Thing ;
            schema:name "Reading" .

        ex:cooking
            a schema:Thing ;
            schema:name "Cooking" .
        """;

        String patchText = """
        D <http://example.org/alice>
          <https://schema.org/hobby>
          <http://example.org/reading> .

        D <http://example.org/alice>
          <https://schema.org/hobby>
          <http://example.org/cooking> .
        """;

        String expectedBody = """
                              {
                                "patch": [{
                                  "op": "remove",
                                  "ref": [
                                    {"@id": "http://example.org/alice"},
                                    "hobby",
                                    [
                                      {"@id": "http://example.org/reading"},
                                      {"@id": "http://example.org/cooking"}
                                    ]
                                  ]
                                }],
                                "@context": {"hobby": "https://schema.org/hobby"}
                              }
                          """;

        test(trig, patchText, expectedBody);
    }

    @Test
    public void testAliceNumberDatatypeChangeKeepValue() {
        String trig = """
        @prefix ex: <http://example.org/> .
        @prefix schema: <https://schema.org/> .

        ex:alice
            schema:height "2"^^<http://www.w3.org/2001/XMLSchema#decimal> .
        """;

        String patchText = """
        D <http://example.org/alice>
          <https://schema.org/height>
          "2"^^<http://www.w3.org/2001/XMLSchema#decimal> .

        A <http://example.org/alice>
          <https://schema.org/height>
          "2"^^<http://www.w3.org/2001/XMLSchema#integer> .
        """;

        String expectedBody = """
                              {
                                "patch": [{
                                  "op": "replace",
                                  "ref": [
                                    {"@id": "http://example.org/alice"},
                                    "height",
                                    {
                                      "@value": "2",
                                      "@type": "http://www.w3.org/2001/XMLSchema#decimal"
                                    }
                                  ],
                                  "value": {
                                    "@value": "2",
                                    "@type": "http://www.w3.org/2001/XMLSchema#integer"
                                  }
                                }],
                                "@context": {"height": "https://schema.org/height"}
                              }
                          """;

        test(trig, patchText, expectedBody);
    }

    @Test
    public void testAliceMultipleAliasesChangeAndDelete() {
        String trig = """
        @prefix ex: <http://example.org/> .
        @prefix schema: <https://schema.org/> .

        ex:alice
            schema:alternateName "Alice" ;
            schema:alternateName "Alicia" ;
            schema:alternateName "Ally" ;
            schema:alternateName "Alice Smith" ;
            schema:alternateName "A. Smith" .
        """;

        String patchText = """
        D <http://example.org/alice>
          <https://schema.org/alternateName>
          "Alice" .

        A <http://example.org/alice>
          <https://schema.org/alternateName>
          "Alice Cooper" .

        D <http://example.org/alice>
          <https://schema.org/alternateName>
          "Alicia" .

        A <http://example.org/alice>
          <https://schema.org/alternateName>
          "Alicia Smith" .

        D <http://example.org/alice>
          <https://schema.org/alternateName>
          "Ally" .

        A <http://example.org/alice>
          <https://schema.org/alternateName>
          "Al" .

        D <http://example.org/alice>
          <https://schema.org/alternateName>
          "Alice Smith" .

        D <http://example.org/alice>
          <https://schema.org/alternateName>
          "A. Smith" .
        """;

        String expectedBody = """
                          {
                            "patch": [
                              {
                                "op": "replace",
                                "ref": [
                                  {"@id": "http://example.org/alice"},
                                  "alternateName",
                                  {"@value": "Alice"}
                                ],
                                "value": "Alice Cooper"
                              },
                              {
                                "op": "replace",
                                "ref": [
                                  {"@id": "http://example.org/alice"},
                                  "alternateName",
                                  {"@value": "Alicia"}
                                ],
                                "value": "Alicia Smith"
                              },
                              {
                                "op": "replace",
                                "ref": [
                                  {"@id": "http://example.org/alice"},
                                  "alternateName",
                                  {"@value": "Ally"}
                                ],
                                "value": "Al"
                              },
                              {
                                "op": "remove",
                                "ref": [
                                  {"@id": "http://example.org/alice"},
                                  "alternateName",
                                  [
                                    {"@value": "Alice Smith"},
                                    {"@value": "A. Smith"}
                                  ]
                                ]
                              }
                            ],
                            "@context": {"alternateName": "https://schema.org/alternateName"}
                          }
                          """;

        test(trig, patchText, expectedBody);
    }

    //======================================
    //helper
    private void test(String trigText, String rdfPatchText, String expectedResponse) {
        ATICPatchConverter jpc = new ATICPatchConverter();

        DatasetGraph dg = DatasetGraphFactory.createGeneral();
        RDFDataMgr.read(dg, new ByteArrayInputStream(trigText.getBytes(StandardCharsets.UTF_8)), Lang.TRIG);

        RDFPatch rdfPatch = RDFPatchOps.read(
                new ByteArrayInputStream(rdfPatchText.getBytes(StandardCharsets.UTF_8)));

        JSONObject aticPatch = jpc.toATICPatch(
                rdfPatch,
                uri -> containsURI(uri, dg),
                new ATICPatchConverter.Options());

        assertNotNull(aticPatch);

        if (PRINT) {
            System.out.println(aticPatch.toString(2));
        }

        JSONCompare.assertMatches(expectedResponse, aticPatch.toString(2), Set.of(CompareMode.JSON_ARRAY_NON_EXTENSIBLE));
    }

    private boolean containsURI(String uri, DatasetGraph dg) {
        Node n = NodeFactory.createURI(uri);

        return dg.contains(Node.ANY, n, Node.ANY, Node.ANY)
                || dg.contains(Node.ANY, Node.ANY, Node.ANY, n);
    }
}
