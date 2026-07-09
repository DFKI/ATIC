package de.dfki.sds.aticserver;

import java.io.InputStream;
import java.util.Properties;
import org.apache.jena.graph.Node;
import org.apache.jena.rdfpatch.RDFChanges;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.RDFPatchOps;

/**
 *
 */
public class Utils {

    public static String getVersion() {
        try (InputStream is = Utils.class.getClassLoader()
                .getResourceAsStream(
                        "META-INF/maven/de.dfki.sds/atic-server/pom.properties")) {

            Properties props = new Properties();
            props.load(is);
            return props.getProperty("version");

        } catch (Exception e) {
            return "";
        }
    }

    public static RDFPatch invertPatch(RDFPatch patch) {
        return RDFPatchOps.build(changes -> {
            patch.apply(new RDFChanges() {

                @Override
                public void header(String field, Node value) {
                    // Keep headers as-is (or ignore them)
                    changes.header(field, value);
                }

                @Override
                public void add(Node g, Node s, Node p, Node o) {
                    changes.delete(g, s, p, o);
                }

                @Override
                public void delete(Node g, Node s, Node p, Node o) {
                    changes.add(g, s, p, o);
                }

                @Override
                public void addPrefix(Node gn, String prefix, String uriStr) {
                    changes.deletePrefix(gn, prefix);
                }

                @Override
                public void deletePrefix(Node gn, String prefix) {
                    // Cannot invert without knowing the old URI.
                }

                @Override
                public void txnAbort() {
                    changes.txnAbort();
                }

                @Override
                public void segment() {
                    changes.segment();
                }

                @Override
                public void start() {
                    changes.start();
                }

                @Override
                public void finish() {
                    changes.finish();
                }

                @Override
                public void txnBegin() {
                    changes.txnBegin();
                }

                @Override
                public void txnCommit() {
                    changes.txnCommit();
                }
            });
        });
    }

}
