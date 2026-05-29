package de.dfki.sds.rdfpatchsqlite;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.ReaderInputStream;
import org.apache.commons.io.output.WriterOutputStream;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.RDFPatchOps;
import org.apache.jena.rdfpatch.changes.RDFChangesBase;
import org.apache.jena.rdfpatch.changes.RDFChangesCollector;

/**
 *
 */
/*package*/ class TrialPatch {

    public static void main(String[] args) throws IOException {
        //write();
        //read();

        check();
    }

    private static void check() throws IOException {
        String patchText = """
H id <uuid:0686c69d-8f89-4496-acb5-744f0157a8db> .
H prev <uuid:3ee0eca0-6d5f-4b4d-85db-f69ab1167eb1> .
H growing true .
H version "0.1.0" .
H number 9.0 .
H anotherLiteral "DFKI"@de .
H name-with-minus "German ..."@en .
TX .
PA "rdf" "http://www.w3.org/1999/02/22-rdf-syntax-ns#" .
PA "owl" "http://www.w3.org/2002/07/owl#" .
PA "rdfs" "http://www.w3.org/2000/01/rdf-schema#" .
A <http://example/SubClass> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/2002/07/owl#Class> .
A <http://example/SubClass> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example/SUPER_CLASS> .
A <http://example/SubClass> <http://www.w3.org/2000/01/rdf-schema#label> "SubClass" .
A <http://example/SubClass> <http://www.w3.org/2000/01/rdf-schema#label> "Unterklasse"@de .
A <http://example/SubClass> <urn:property:a_boolean_prop> true .
A <http://example/SubClass> <urn:property:a_number_prop> 1.0 .
D <http://example/SubClass> <urn:property:a_number_prop> 1.0 .
D <http://example/SubClass> <urn:property:with_datatype> "{ \\"blub\\": 5 }"^^<http://www.w3.org/1999/02/22-rdf-syntax-ns#JSON> .
TC .
                           """;
        
        //not supported: A << <urn:res:A> <urn:res:B> <urn:res:C> >> <urn:property:a_boolean_prop> true .
        
        RDFPatch patch = RDFPatchOps.read(
                new ReaderInputStream.Builder()
                        .setReader(new StringReader(patchText))
                        .setCharset(StandardCharsets.UTF_8) // enforce UTF‑8 encoding
                        .get());

        System.out.println(patch);

        Converter c = new Converter();
        
        FileUtils.deleteQuietly(new File("/tmp/patch.sqlite"));

        c.toSqlite(patch, "jdbc:sqlite:/tmp/patch.sqlite", 200);

        RDFPatch loadedPatch = c.toPatch("jdbc:sqlite:/tmp/patch.sqlite");

        StringWriter sw = new StringWriter();
        WriterOutputStream wos = WriterOutputStream.builder()
                .setWriter(sw)
                .setCharset(StandardCharsets.UTF_8)
                .get();
        RDFPatchOps.write(wos, patch);
        System.out.println(sw.toString());
    }

    private static void read() {
        String patchFile = "generated.rdfp";

        // Read the patch from a file (text format)
        RDFPatch patch = RDFPatchOps.read(patchFile);

        //TODO use
        //JsonValue v = RDFTerm2Json.fromNode(Node.ANY);
        // Handler receives change callbacks
        RDFChangesBase handler = new RDFChangesBase() {
            @Override
            public void start() {
                System.out.println("PATCH START");
            }

            @Override
            public void finish() {
                System.out.println("PATCH FINISH");
            }

            @Override
            public void header(String field, Node value) {
                System.out.println("HEADER: " + field + " = " + value);
            }

            @Override
            public void add(Node g, Node s, Node p, Node o) {
                System.out.println("ADD: " + s + " " + p + " " + o + " (graph: " + g + ")");
            }

            @Override
            public void delete(Node g, Node s, Node p, Node o) {
                System.out.println("DELETE: " + s + " " + p + " " + o + " (graph: " + g + ")");
            }

            @Override
            public void addPrefix(Node graph, String prefix, String uriStr) {
                System.out.println("PREFIX+ " + prefix + " -> " + uriStr + " (graph: " + graph + ")");
            }

            @Override
            public void deletePrefix(Node graph, String prefix) {
                System.out.println("PREFIX- " + prefix + " (graph: " + graph + ")");
            }

            @Override
            public void txnBegin() {
                System.out.println("TXN BEGIN");
            }

            @Override
            public void txnCommit() {
                System.out.println("TXN COMMIT");
            }

            @Override
            public void txnAbort() {
                System.out.println("TXN ABORT");
            }
        };

        // Apply the patch stream to the handler
        patch.apply(handler);
    }

    private static void write() {

        // Collector for change events
        RDFChangesCollector collector = new RDFChangesCollector();

        // Begin a transaction in the patch
        collector.txnBegin();

        // Add a prefix declaration
        collector.addPrefix(null, "ex", "http://example.org/");

        // Add some triples (as quads with null graph = default)
        collector.add(null,
                NodeFactory.createURI("http://example.org/s1"),
                NodeFactory.createURI("http://example.org/p1"),
                NodeFactory.createLiteralByValue("Hello"));

        collector.add(null,
                NodeFactory.createURI("http://example.org/s2"),
                NodeFactory.createURI("http://example.org/p2"),
                NodeFactory.createLiteralByValue("World"));

        // Delete a triple example
        collector.delete(null,
                NodeFactory.createURI("http://example.org/s3"),
                NodeFactory.createURI("http://example.org/p3"),
                NodeFactory.createLiteralByValue("Goodbye"));

        collector.header("test", NodeFactory.createLiteralByValue("{ \"metadata\": 5 }"));

        // Commit the transaction
        collector.txnCommit();

        // Get the constructed RDFPatch
        RDFPatch patch = collector.getRDFPatch();

        // Write it out to a .rdfp file (text format)
        try (OutputStream out = new FileOutputStream("generated.rdfp")) {
            RDFPatchOps.write(out, patch);
        } catch (FileNotFoundException ex) {
            System.getLogger(TrialPatch.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        } catch (IOException ex) {
            System.getLogger(TrialPatch.class.getName()).log(System.Logger.Level.ERROR, (String) null, ex);
        }

        System.out.println("Patch written to generated.rdfp");
    }
}
