package de.dfki.sds.aticserver;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.LangBuilder;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RDFWriterRegistry;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.WriterGraphRIOT;
import org.apache.jena.riot.WriterGraphRIOTFactory;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.system.StreamRDFWriter;
import org.apache.jena.riot.system.StreamRDFWriterFactory;
import org.apache.jena.sparql.util.Context;

/**
 * A custom Turtle writer that sorts all triples before serializing.
 *
 * <p>
 * Default ordering: URI subjects before blank-node subjects, then by subject, predicate, and object. The comparator can be replaced globally via
 * {@link #setComparator(Comparator)}.</p>
 *
 * <p>
 * Register once at application startup:</p>
 * <pre>OrderedTurtleWriter.register();</pre>
 *
 * <p>
 * Usage:</p>
 * <pre>RDFDataMgr.write(out, model, OrderedTurtleWriter.FORMAT);</pre>
 */
public final class OrderedTurtleWriter {

    public static final String LANG_NAME = "TTL-Ordered";

    public static final Lang LANG
            = LangBuilder.create(LANG_NAME, "application/x-turtle-ordered")
                    .addAltNames("TTL_ORDERED", "TURTLE_ORDERED")
                    .build();

    public static final RDFFormat FORMAT
            = new RDFFormat(LANG, RDFFormat.PLAIN);

    private static volatile Comparator<Triple> COMPARATOR = buildDefaultComparator();

    public static Comparator<Triple> getComparator() {
        return COMPARATOR;
    }

    public static void setComparator(Comparator<Triple> comparator) {
        Objects.requireNonNull(comparator, "comparator must not be null");
        COMPARATOR = comparator;
    }

    /**
     * Replace the global comparator with a custom ordering policy.
     */
    public static void configureComparator(Comparator<Triple> comparator) {
        setComparator(comparator);
    }

    public static void register() {
        RDFLanguages.register(LANG);
        RDFWriterRegistry.register(LANG, FORMAT);

        // Register graph writer factory (used by Model.write() and RDFDataMgr)
        RDFWriterRegistry.register(
                FORMAT,
                new OrderedGraphWriterFactory()
        );

        // Register streaming writer (used as fallback)
        StreamRDFWriter.register(
                FORMAT,
                new StreamRDFWriterFactory() {
            @Override
            public StreamRDF create(
                    OutputStream output,
                    RDFFormat format,
                    Context context) {
                return new OrderedStreamRDF(output, COMPARATOR);
            }
        }
        );
    }

    /**
     * Write a graph to the given output stream with triples sorted by the given comparator. Delegates to the flat Turtle writer for formatting.
     */
    public static void writeSortedFlat(OutputStream out,
            Graph graph,
            Comparator<Triple> comparator) {
        List<Triple> triples = new ArrayList<>();
        org.apache.jena.util.iterator.ExtendedIterator<Triple> it = graph.find();
        while (it.hasNext()) {
            triples.add(it.next());
        }
        it.close();

        triples.sort(comparator);

        StreamRDF delegate = StreamRDFWriter.getWriterStream(
                out, RDFFormat.TURTLE_FLAT);
        delegate.start();
        for (Triple triple : triples) {
            delegate.triple(triple);
        }
        delegate.finish();
    }

    /**
     * Write a graph to the given output stream with triples sorted by the given comparator. Delegates to the block Turtle writer for formatting.
     */
    public static void writeSorted(
            OutputStream out,
            Graph graph,
            Comparator<Triple> comparator) {

        List<Triple> triples = new ArrayList<>();
        org.apache.jena.util.iterator.ExtendedIterator<Triple> it = graph.find();
        while (it.hasNext()) {
            triples.add(it.next());
        }
        it.close();

        // Establish the exact order requested by the caller.
        triples.sort(comparator);

        // Preserve subject order by first occurrence in the sorted list,
        // while gathering each subject's remaining triples into its block.
        List<Triple> grouped = new ArrayList<>(triples.size());
        Set<Triple> emitted = new HashSet<>();

        for (Triple triple : triples) {
            if (!emitted.add(triple)) {
                continue;
            }

            grouped.add(triple);

            Node subject = triple.getSubject();

            for (Triple candidate : triples) {
                if (!emitted.contains(candidate)
                        && candidate.getSubject().equals(subject)) {
                    grouped.add(candidate);
                    emitted.add(candidate);
                }
            }
        }

        StreamRDF writer = StreamRDFWriter.getWriterStream(
                out, RDFFormat.TURTLE_BLOCKS);

        writer.start();

        try {
            graph.getPrefixMapping().getNsPrefixMap()
            .entrySet()
            .stream()
            .sorted(Map.Entry.comparingByValue())
            .forEach(entry ->
                    writer.prefix(entry.getKey(), entry.getValue()));

            for (Triple triple : grouped) {
                writer.triple(triple);
            }
        } finally {
            writer.finish();
        }
    }

    static Comparator<Triple> buildDefaultComparator() {
        return Comparator
                .comparing((Triple triple)
                        -> triple.getSubject().isBlank() ? 1 : 0)
                .thenComparing(triple -> nodeKey(triple.getSubject()))
                .thenComparing(triple -> nodeKey(triple.getPredicate()))
                .thenComparing(triple -> nodeKey(triple.getObject()));
    }

    /**
     * Create a comparator that prioritises ontology class nodes before property nodes, then falls back to URI-sorted order.
     */
    public static Comparator<Triple> createClassFirstComparator(
            String... classKeywords) {
        String[] defaultClassKeywords = {
            "Class", "Ontology", "Entity", "Type"
        };
        String[] effectiveClassKeywords
                = (classKeywords != null && classKeywords.length > 0)
                        ? classKeywords
                        : defaultClassKeywords;

        String[] propertyKeywords = {
            "Property", "Relation", "Role", "Has"
        };

        String[] datatypeKeywords = {
            "Datatype"
        };

        Comparator<Triple> base = Comparator
                .comparing((Triple triple)
                        -> triple.getSubject().isBlank() ? 1 : 0);

        base = base.thenComparing(triple -> category(
                triple.getSubject(),
                effectiveClassKeywords,
                propertyKeywords,
                datatypeKeywords
        ));

        base = base.thenComparing(triple -> nodeKey(triple.getSubject()));
        base = base.thenComparing(triple -> nodeKey(triple.getPredicate()));
        base = base.thenComparing(triple -> nodeKey(triple.getObject()));

        return base;
    }

    /**
     * Convenience factory that uses default class/property keywords.
     */
    public static Comparator<Triple> createClassFirstComparator() {
        return createClassFirstComparator((String[]) null);
    }

    /* ------------------------------------------------------------------ */
 /* Comparator helper                                                   */
 /* ------------------------------------------------------------------ */
    private static int category(Node node,
            String[] classKeywords,
            String[] propertyKeywords,
            String[] datatypeKeywords) {
        if (!node.isURI()) {
            return 3;
        }
        String localName = getLocalName(node.getURI());
        for (String kw : classKeywords) {
            if (localName.contains(kw)) {
                return 0;
            }
        }
        for (String kw : propertyKeywords) {
            if (localName.contains(kw)) {
                return 1;
            }
        }
        for (String kw : datatypeKeywords) {
            if (localName.contains(kw)) {
                return 2;
            }
        }
        return 3;
    }

    private static String getLocalName(String uri) {
        int hashIdx = uri.lastIndexOf('#');
        if (hashIdx >= 0) {
            return uri.substring(hashIdx + 1);
        }
        int slashIdx = uri.lastIndexOf('/');
        if (slashIdx >= 0) {
            return uri.substring(slashIdx + 1);
        }
        return uri;
    }

    private static String nodeKey(Node node) {
        if (node.isURI()) {
            return "U:" + node.getURI();
        }
        if (node.isBlank()) {
            return "B:" + node.getBlankNodeLabel();
        }
        if (node.isLiteral()) {
            return "L:" + node.getLiteralLexicalForm()
                    + "|lang=" + Objects.toString(node.getLiteralLanguage(), "")
                    + "|datatype=" + Objects.toString(node.getLiteralDatatypeURI(), "");
        }
        return node.toString();
    }

    /* ------------------------------------------------------------------ */
 /* OrderedGraphWriterFactory                                           */
 /* ------------------------------------------------------------------ */
    private static class OrderedGraphWriterFactory
            implements WriterGraphRIOTFactory {

        @Override
        public WriterGraphRIOT create(RDFFormat syntaxForm) {
            return new OrderedGraphWriter(COMPARATOR);
        }
    }

    private static class OrderedGraphWriter implements WriterGraphRIOT {

        private final Comparator<Triple> comparator;
        private List<Triple> triples;

        OrderedGraphWriter(Comparator<Triple> comparator) {
            this.comparator = comparator;
        }

        @Override
        public void write(OutputStream out,
                Graph graph,
                org.apache.jena.riot.system.PrefixMap prefixMap,
                String baseURI,
                Context context) {
            triples = new ArrayList<>();
            org.apache.jena.util.iterator.ExtendedIterator<Triple> it = graph.find();
            while (it.hasNext()) {
                triples.add(it.next());
            }
            it.close();

            triples.sort(comparator);

            StreamRDF delegate = StreamRDFWriter.getWriterStream(
                    out, RDFFormat.TURTLE_FLAT);
            delegate.start();
            for (Triple triple : triples) {
                delegate.triple(triple);
            }
            delegate.finish();
        }

        @Override
        public void write(java.io.Writer out,
                Graph graph,
                org.apache.jena.riot.system.PrefixMap prefixMap,
                String baseURI,
                Context context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Lang getLang() {
            return LANG;
        }
    }

    /* ------------------------------------------------------------------ */
 /* OrderedStreamRDF                                                    */
 /* ------------------------------------------------------------------ */
    private static final class OrderedStreamRDF implements StreamRDF {

        private final OutputStream output;
        private final Comparator<Triple> comparator;
        private final List<Triple> triples = new ArrayList<>();
        private final Map<String, String> prefixes = new LinkedHashMap<>();

        private OrderedStreamRDF(OutputStream output,
                Comparator<Triple> comparator) {
            this.output = output;
            this.comparator = comparator;
        }

        @Override
        public void base(String baseURI) {
        }

        @Override
        public void start() {
        }

        @Override
        public void prefix(String prefix, String iri) {
            prefixes.put(prefix, iri);
        }

        @Override
        public void triple(Triple triple) {
            triples.add(triple);
        }

        @Override
        public void quad(org.apache.jena.sparql.core.Quad quad) {
            throw new RiotException(
                    "TTL-Ordered supports RDF graphs, not datasets");
        }

        @Override
        public void finish() {
            triples.sort(comparator);

            StreamRDF delegate = StreamRDFWriter.getWriterStream(
                    output, RDFFormat.TURTLE_FLAT);
            delegate.start();
            prefixes.forEach(delegate::prefix);
            for (Triple triple : triples) {
                delegate.triple(triple);
            }
            delegate.finish();
        }
    }

    private OrderedTurtleWriter() {
    }
}
