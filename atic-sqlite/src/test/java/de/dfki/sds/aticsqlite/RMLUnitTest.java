package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.ac.UserGroupManagement;
import de.dfki.sds.atic.jenatic.InvocationContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.vocabulary.DCTerms;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 *
 */
public class RMLUnitTest {

    private SqliteAticDatasetGraph dataset;

    @BeforeEach
    void setup(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        dataset = TL.createDatasetGraph(tempDir);
    }

    private static String writeExampleCsv(Path srcTempDir) throws IOException {

        // deterministic file name
        Path csvFile = srcTempDir.resolve("example.csv");

        // deterministic CSV content
        String content
                = "id,name,value\n"
                + "1,Alice,100\n"
                + "2,Bob,200\n"
                + "3,Charlie,300\n";

        // write file (overwrites if exists)
        Files.write(csvFile, content.getBytes(StandardCharsets.UTF_8));

        // return absolute path
        return csvFile.toAbsolutePath().toString();
    }

    @Test
    void run() throws IOException {
        User adminUser = dataset.calculateRead(() -> {
            return dataset.getUser(UserGroupManagement.ADMIN_USERNAME, InvocationContext.EMPTY);
        });

        InvocationContext ctx = new InvocationContext.Builder().fromUser(adminUser).build();

        Path srcTempDir = Files.createTempDirectory("atic-src-folder-");
        String csvPath = writeExampleCsv(srcTempDir);

        String rmlCode = """
@prefix rml: <http://w3id.org/rml/> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
@prefix ex:  <http://example.org/staff/> .

<#triplesMap>
    a rml:TriplesMap ;

    rml:logicalSource [ a rml:LogicalSource;
        rml:referenceFormulation rml:CSV;
        rml:source [ a rml:FilePath ;
            rml:path "$csvPath"
          ]
    ];

    rml:subjectMap [
        rml:template "urn:staff:{id}" ;
        rml:termType rml:IRI
    ] ;

    rml:predicateObjectMap [
        rml:predicate ex:id ;
        rml:objectMap [
            rml:reference "id" ;
            rml:datatype xsd:integer
        ]
    ] ;

    rml:predicateObjectMap [
        rml:predicate ex:name ;
        rml:objectMap [
            rml:reference "name" ;
            rml:datatype xsd:string
        ]
    ] ;

    rml:predicateObjectMap [
        rml:predicate ex:value ;
        rml:objectMap [
            rml:reference "value" ;
            rml:datatype xsd:integer
        ]
    ] .
""".replace("$csvPath", csvPath);

        //a project is created before the run inside the quadstore
        dataset.executeWrite(() -> {
            dataset.add(
                    NodeFactory.createURI("urn:graph:rml"),
                    NodeFactory.createURI("urn:rml:project"),
                    DCTerms.description.asNode(),
                    NodeFactory.createLiteralString(rmlCode),
                    ctx
            );
        });

        //maybe specify default graph?
        //System.out.println("write:");
        //the project is run, all settings are on the project resources
        dataset.executeWrite(() -> {
            dataset.runRML(
                    NodeFactory.createURI("urn:graph:rml"),
                    NodeFactory.createURI("urn:rml:project"),
                    100_000,
                    ctx
            );
        });

        /*
        System.out.println("read:");
        dataset.executeRead(() -> {
            Iterator<Quad> iter = dataset.find(Node.ANY, Node.ANY, Node.ANY, Node.ANY, ctx);
            iter.forEachRemaining(quad -> System.out.println(quad));
        });
         */
        dataset.executeRead(() -> {
            Node staff = NodeFactory.createURI("urn:staff:1");

            Assertions.assertTrue(
                    dataset.contains(
                            Node.ANY, // any graph
                            staff,
                            NodeFactory.createURI("http://example.org/staff/id"),
                            Node.ANY,
                            ctx
                    ),
                    "Expected ex:id triple was not generated"
            );

            Assertions.assertTrue(
                    dataset.contains(
                            Node.ANY,
                            staff,
                            NodeFactory.createURI("http://example.org/staff/name"),
                            Node.ANY,
                            ctx
                    ),
                    "Expected ex:name triple was not generated"
            );

            Assertions.assertTrue(
                    dataset.contains(
                            Node.ANY,
                            staff,
                            NodeFactory.createURI("http://example.org/staff/value"),
                            Node.ANY,
                            ctx
                    ),
                    "Expected ex:value triple was not generated"
            );
        });
    }
}
