package de.dfki.sds.rdfpatchsqlite;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdfpatch.RDFPatch;
import org.apache.jena.rdfpatch.changes.RDFChangesBase;
import org.apache.jena.sparql.vocabulary.FOAF;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 *
 */
public class ATICPatchConverter {

    public JSONObject toATICPatch(RDFPatch rdfPatch, Predicate<String> uriExistance, Options options) {

        JSONObject response = JSONUtils.createJSONObject();
        JSONArray patch = new JSONArray();
        response.put("patch", patch);

        JSONObject context = JSONUtils.createJSONObject();
        response.put("@context", context);

        Map<Node, Map<String, Molecule>> moleculesByGraph = toMolecules(rdfPatch, uriExistance, options);

        extractNewMolecules(moleculesByGraph, patch, context, options);

        extractReplacements(moleculesByGraph, patch, context, options);

        extractRemovals(moleculesByGraph, patch, context, options);

        extractAdditions(moleculesByGraph, patch, context, options);

        assertMoleculesConsumed(moleculesByGraph);

        return response;
    }

    //extractions =========================================
    private void extractNewMolecules(
            Map<Node, Map<String, Molecule>> moleculesByGraph,
            JSONArray patch,
            JSONObject context,
            Options options) {

        Set<String> newUris = moleculesByGraph.values().stream()
                .flatMap(m -> m.values().stream())
                .filter(Molecule::isNew)
                .map(Molecule::getUri)
                .collect(Collectors.toSet());

        Set<Triple> deferred = new HashSet<>();

        for (Map<String, Molecule> molecules : moleculesByGraph.values()) {
            for (Molecule molecule : molecules.values()) {

                if (!molecule.isNew()) {
                    continue;
                }

                JSONObject value = JSONUtils.createJSONObject();

                List<Triple> outgoing = new ArrayList<>(molecule.getOutgoingAdds());

                outgoing.sort(outgoingPropertyComparator(options));

                for (Triple t : outgoing) {

                    Node object = t.getObject();

                    if (object.isURI()
                            && newUris.contains(object.getURI())
                            && !isSpecialUriProperty(t.getPredicate(), options)) {

                        deferred.add(t);
                        molecule.getOutgoingAdds().remove(t);
                        continue;
                    }

                    addOutgoingProperty(
                            value,
                            t,
                            context,
                            options);

                    molecule.getOutgoingAdds().remove(t);
                }

                JSONObject reverse = JSONUtils.createJSONObject();

                Iterator<Triple> inIt
                        = molecule.getIncomingAdds().iterator();

                while (inIt.hasNext()) {

                    Triple t = inIt.next();

                    Node subject = t.getSubject();

                    // new -> new reference, handle later
                    if (subject.isURI()
                            && newUris.contains(subject.getURI())) {

                        deferred.add(t);
                        inIt.remove();
                        continue;
                    }

                    String key = ensurePropertyKey(
                            t.getPredicate().getURI(),
                            context,
                            options);

                    addPropertyValue(
                            reverse,
                            key,
                            nodeToValue(subject));

                    inIt.remove();
                }

                if (!reverse.isEmpty()) {
                    value.put("@reverse", reverse);
                }

                JSONObject op = JSONUtils.createJSONObject();

                op.put("op", "add");

                op.put("ref",
                        new JSONArray()
                                .put(new JSONObject()
                                        .put("@id",
                                                molecule.getUri())));

                op.put("value", value);

                patch.put(op);
            }
        }

        // Add all deferred references after all molecules exist
        for (Triple t : deferred) {

            JSONObject op = JSONUtils.createJSONObject();

            op.put("op", "add");

            op.put("ref",
                    new JSONArray()
                            .put(new JSONObject()
                                    .put("@id",
                                            t.getSubject().getURI()))
                            .put(ensurePropertyKey(
                                    t.getPredicate().getURI(),
                                    context, options)));

            op.put("value",
                    nodeToValue(t.getObject()));

            patch.put(op);
        }
    }

    private void extractReplacements(
            Map<Node, Map<String, Molecule>> moleculesByGraph,
            JSONArray patch,
            JSONObject context,
            Options options) {

        boolean changed;

        do {
            changed = false;

            outer:
            for (Map<String, Molecule> molecules : moleculesByGraph.values()) {
                for (Molecule molecule : molecules.values()) {

                    Iterator<Triple> delIt = molecule.getOutgoingDeletes().iterator();

                    while (delIt.hasNext()) {
                        Triple del = delIt.next();

                        Triple addMatch = molecule.getOutgoingAdds().stream()
                                .filter(add
                                        -> add.getPredicate().equals(del.getPredicate())
                                && add.getSubject().equals(del.getSubject()))
                                .findFirst()
                                .orElse(null);

                        if (addMatch == null) {
                            continue;
                        }

                        delIt.remove();
                        molecule.getOutgoingAdds().remove(addMatch);

                        patch.put(createReplaceOperation(
                                molecule,
                                del,
                                addMatch,
                                context,
                                options));

                        changed = true;
                        break outer;
                    }
                }
            }

        } while (changed);
    }

    private void extractRemovals(
            Map<Node, Map<String, Molecule>> moleculesByGraph,
            JSONArray patch,
            JSONObject context,
            Options options) {

        Set<Triple> handled = new HashSet<>();

        // Prefer outgoing deletes
        for (Map<String, Molecule> molecules : moleculesByGraph.values()) {
            for (Molecule molecule : molecules.values()) {

                Map<String, List<Triple>> grouped = new LinkedHashMap<>();

                Iterator<Triple> it
                        = molecule.getOutgoingDeletes().iterator();

                while (it.hasNext()) {

                    Triple t = it.next();

                    grouped.computeIfAbsent(
                            t.getPredicate().getURI(),
                            k -> new ArrayList<>())
                            .add(t);

                    handled.add(t);
                    it.remove();
                }

                for (List<Triple> triples : grouped.values()) {
                    patch.put(createRemoveOperation(
                            triples,
                            context,
                            options));
                }
            }
        }

        // Process incoming deletes that were not already handled
        for (Map<String, Molecule> molecules : moleculesByGraph.values()) {
            for (Molecule molecule : molecules.values()) {

                Map<String, List<Triple>> grouped = new LinkedHashMap<>();

                Iterator<Triple> it
                        = molecule.getIncomingDeletes().iterator();

                while (it.hasNext()) {

                    Triple t = it.next();

                    if (handled.contains(t)) {
                        it.remove();
                        continue;
                    }

                    grouped.computeIfAbsent(
                            t.getPredicate().getURI(),
                            k -> new ArrayList<>())
                            .add(t);

                    handled.add(t);
                    it.remove();
                }

                for (List<Triple> triples : grouped.values()) {
                    patch.put(createRemoveOperation(
                            triples,
                            context,
                            options));
                }
            }
        }
    }

    private void extractAdditions(
            Map<Node, Map<String, Molecule>> moleculesByGraph,
            JSONArray patch,
            JSONObject context,
            Options options) {

        Set<Triple> handled = new HashSet<>();

        for (Map<String, Molecule> molecules : moleculesByGraph.values()) {
            for (Molecule molecule : molecules.values()) {

                Map<String, List<Triple>> grouped = new LinkedHashMap<>();

                // Prefer outgoing additions
                for (Triple t : molecule.getOutgoingAdds()) {

                    grouped.computeIfAbsent(
                            t.getPredicate().getURI(),
                            k -> new ArrayList<>())
                            .add(t);

                    handled.add(t);
                }

                molecule.getOutgoingAdds().clear();

                // Add grouped outgoing additions
                for (List<Triple> triples : grouped.values()) {
                    patch.put(createAddOperation(
                            triples,
                            context,
                            options));
                }
            }
        }

        // Handle incoming additions which were not already handled
        for (Map<String, Molecule> molecules : moleculesByGraph.values()) {
            for (Molecule molecule : molecules.values()) {

                Map<String, List<Triple>> grouped = new LinkedHashMap<>();

                Iterator<Triple> it
                        = molecule.getIncomingAdds().iterator();

                while (it.hasNext()) {

                    Triple t = it.next();

                    if (handled.contains(t)) {
                        it.remove();
                        continue;
                    }

                    grouped.computeIfAbsent(
                            t.getPredicate().getURI(),
                            k -> new ArrayList<>())
                            .add(t);

                    handled.add(t);
                    it.remove();
                }

                for (List<Triple> triples : grouped.values()) {
                    patch.put(createAddOperation(
                            triples,
                            context,
                            options));
                }
            }
        }
    }

    //operation creation =================================
    private JSONObject createAddOperation(
            List<Triple> triples,
            JSONObject context,
            Options options) {

        Triple first = triples.get(0);

        JSONObject op = new JSONObject();

        op.put("op", "add");

        op.put("ref",
                new JSONArray()
                        .put(new JSONObject()
                                .put("@id",
                                        first.getSubject().getURI()))
                        .put(ensurePropertyKey(
                                first.getPredicate().getURI(),
                                context,
                                options)));

        if (triples.size() == 1) {

            op.put("value",
                    nodeToValue(first.getObject()));

        } else {

            JSONArray values = new JSONArray();

            for (Triple t : triples) {
                values.put(nodeToValue(t.getObject()));
            }

            op.put("value", values);
        }

        return op;
    }

    private JSONObject createRemoveOperation(
            List<Triple> triples,
            JSONObject context,
            Options options) {

        Triple first = triples.get(0);

        JSONObject op = new JSONObject();

        JSONArray ref = new JSONArray();

        ref.put(new JSONObject()
                .put("@id",
                        first.getSubject().getURI()));

        ref.put(ensurePropertyKey(
                first.getPredicate().getURI(),
                context,
                options));

        if (triples.size() == 1) {

            ref.put(nodeToReference(
                    first.getObject()));

        } else {

            JSONArray values = new JSONArray();

            for (Triple t : triples) {
                values.put(nodeToReference(
                        t.getObject()));
            }

            ref.put(values);
        }

        op.put("op", "remove");
        op.put("ref", ref);

        return op;
    }

    private JSONObject createReplaceOperation(
            Molecule molecule,
            Triple oldTriple,
            Triple newTriple,
            JSONObject context,
            Options options) {

        Node predicate = oldTriple.getPredicate();

        String propertyKey;
        if (predicate.getURI().equals(options.typeUri)) {
            propertyKey = "@type";
        } else {
            propertyKey = ensurePropertyKey(
                    predicate.getURI(),
                    context, options);
        }

        JSONArray ref = new JSONArray();

        ref.put(new JSONObject()
                .put("@id", molecule.getUri()));

        ref.put(propertyKey);

        ref.put(nodeToReference(oldTriple.getObject()));

        JSONObject result = new JSONObject();

        result.put("op", "replace");
        result.put("ref", ref);
        result.put("value", nodeToValue(newTriple.getObject()));

        return result;
    }

    private Object nodeToReference(Node node) {

        if (node.isURI()) {
            return new JSONObject()
                    .put("@id", node.getURI());
        }

        if (node.isLiteral()) {
            JSONObject result = new JSONObject();

            result.put("@value", node.getLiteralLexicalForm());

            String lang = node.getLiteralLanguage();
            String datatype = node.getLiteralDatatypeURI();

            if (lang != null && !lang.isBlank()) {
                result.put("@language", lang);
            } else if (datatype != null && !datatype.isBlank()
                    && !RDF.dtLangString.getURI().equals(datatype)
                    && !XSDDatatype.XSDstring.getURI().equals(datatype)) {
                result.put("@type", datatype);
            }

            return result;
        }

        throw new IllegalArgumentException(
                "Unsupported node type: " + node);
    }

    private Object nodeToValue(Node node) {

        if (node.isURI()) {
            return new JSONObject()
                    .put("@id", node.getURI());
        }

        if (node.isLiteral()) {
            String lang = node.getLiteralLanguage();
            String datatype = node.getLiteralDatatypeURI();

            boolean hasLanguage
                    = lang != null && !lang.isBlank();

            boolean hasSpecialDatatype
                    = datatype != null
                    && !datatype.isBlank()
                    && !RDF.dtLangString.getURI().equals(datatype)
                    && !XSDDatatype.XSDstring.getURI().equals(datatype);

            if (!hasLanguage && !hasSpecialDatatype) {
                return node.getLiteralLexicalForm();
            }

            JSONObject result = new JSONObject();

            result.put("@value", node.getLiteralLexicalForm());

            if (hasLanguage) {
                result.put("@language", lang);
            } else {
                result.put("@type", datatype);
            }

            return result;
        }

        throw new IllegalArgumentException(
                "Unsupported node type: " + node);
    }

    private String ensurePropertyKey(
            String propertyUri,
            JSONObject context,
            Options options) {

        String specialKey = getSpecialPropertyKey(propertyUri, options);

        if (specialKey != null) {
            context.put(specialKey, propertyUri);
            return specialKey;
        }

        for (String key : context.keySet()) {
            if (propertyUri.equals(context.optString(key))) {
                return key;
            }
        }

        String key = createPropertyKey(propertyUri);

        String base = key;
        int counter = 2;

        while (context.has(key)) {
            key = base + counter++;
        }

        context.put(key, propertyUri);

        return key;
    }

    private String getSpecialPropertyKey(
            String propertyUri,
            Options options) {

        if (options.typeUri.equals(propertyUri)) {
            return options.typeKey;
        }

        if (options.labelUri.equals(propertyUri)) {
            return options.labelKey;
        }

        if (options.commentUri.equals(propertyUri)) {
            return options.commentKey;
        }

        if (options.iconUri.equals(propertyUri)) {
            return options.iconKey;
        }

        return null;
    }

    private String createPropertyKey(String uri) {

        int idx = Math.max(
                uri.lastIndexOf('#'),
                uri.lastIndexOf('/'));

        String local
                = idx >= 0
                        ? uri.substring(idx + 1)
                        : uri;

        local = local.replaceAll("[^A-Za-z0-9_]", "_");

        if (local.isEmpty()) {
            local = "property";
        }

        if (!Character.isJavaIdentifierStart(local.charAt(0))) {
            local = "_" + local;
        }

        return local;
    }

    //helper ========================================================
    private void assertMoleculesConsumed(
            Map<Node, Map<String, Molecule>> moleculesByGraph) {

        List<String> remaining = new ArrayList<>();

        for (Map.Entry<Node, Map<String, Molecule>> graphEntry
                : moleculesByGraph.entrySet()) {

            for (Molecule molecule : graphEntry.getValue().values()) {

                if (!molecule.getOutgoingAdds().isEmpty()
                        || !molecule.getOutgoingDeletes().isEmpty()
                        || !molecule.getIncomingAdds().isEmpty()
                        || !molecule.getIncomingDeletes().isEmpty()) {

                    remaining.add(molecule.getUri()
                            + " "
                            + "outAdd="
                            + molecule.getOutgoingAdds().size()
                            + ", outDel="
                            + molecule.getOutgoingDeletes().size()
                            + ", inAdd="
                            + molecule.getIncomingAdds().size()
                            + ", inDel="
                            + molecule.getIncomingDeletes().size());
                }
            }
        }

        if (!remaining.isEmpty()) {
            throw new IllegalStateException(
                    "Unconsumed molecule changes remain:\n"
                    + String.join("\n", remaining));
        }
    }

    private void addOutgoingProperty(
            JSONObject value,
            Triple t,
            JSONObject context,
            Options options) {

        String uri = t.getPredicate().getURI();
        Node object = t.getObject();

        if (uri.equals(options.typeUri)) {

            if (object.isURI()) {
                value.put("@type", object.getURI());
                //ensurePropertyKey(object.getURI(), context)
            }

            return;
        }

        if (uri.equals(options.labelUri)) {
            value.put("label",
                    nodeToValue(object));
            return;
        }

        if (uri.equals(options.commentUri)) {
            value.put("comment",
                    nodeToValue(object));
            return;
        }

        if (uri.equals(options.iconUri)) {
            value.put("icon",
                    nodeToValue(object));
            return;
        }

        String key = ensurePropertyKey(uri, context, options);

        addPropertyValue(
                value,
                key,
                nodeToValue(object));
    }

    private void addPropertyValue(
            JSONObject object,
            String key,
            Object value) {

        if (!object.has(key)) {
            object.put(key, value);
            return;
        }

        Object old = object.get(key);

        if (old instanceof JSONArray array) {
            array.put(value);
        } else {
            JSONArray array = new JSONArray();
            array.put(old);
            array.put(value);

            object.put(key, array);
        }
    }

    private boolean isSpecialUriProperty(
            Node predicate,
            Options options) {

        String uri = predicate.getURI();

        return uri.equals(options.typeUri)
                || uri.equals(options.iconUri);
    }

    private Comparator<Triple> outgoingPropertyComparator(Options options) {

        return Comparator.comparingInt((Triple t)
                -> propertyPriority(t.getPredicate().getURI(), options))
                .thenComparing(
                        t -> t.getPredicate().getURI(),
                        String.CASE_INSENSITIVE_ORDER);
    }

    private int propertyPriority(String uri, Options options) {

        // @id is not normally an outgoing property, but keep the slot
        // if it is introduced later
        if ("@id".equals(uri)) {
            return 0;
        }

        if (options.typeUri.equals(uri)) {
            return 1;
        }

        if (options.labelUri.equals(uri)) {
            return 2;
        }

        if (options.commentUri.equals(uri)) {
            return 3;
        }

        if (options.iconUri.equals(uri)) {
            return 4;
        }

        return 5;
    }

    //preparation ===================================
    private Map<Node, Map<String, Molecule>> toMolecules(
            RDFPatch rdfPatch,
            Predicate<String> uriExistence,
            Options options) {

        Map<Node, Map<String, Molecule>> moleculesByGraph
                = new LinkedHashMap<>();

        rdfPatch.apply(new RDFChangesBase() {

            @Override
            public void add(Node g, Node s, Node p, Node o) {
                collect(true, g, s, p, o);
            }

            @Override
            public void delete(Node g, Node s, Node p, Node o) {
                collect(false, g, s, p, o);
            }

            private void collect(
                    boolean isAdd,
                    Node g,
                    Node s,
                    Node p,
                    Node o) {

                Triple triple = Triple.create(s, p, o);

                if (s.isURI()) {
                    Molecule m = getMolecule(
                            moleculesByGraph,
                            g,
                            s.getURI());

                    m.setNew(!uriExistence.test(s.getURI()));

                    if (isAdd) {
                        m.getOutgoingAdds().add(triple);
                    } else {
                        m.getOutgoingDeletes().add(triple);
                    }
                }

                // Do not create molecules for rdf:type or icon targets
                if (o.isURI()
                        && !isSpecialUriProperty(p, options)) {

                    Molecule m = getMolecule(
                            moleculesByGraph,
                            g,
                            o.getURI());

                    m.setNew(!uriExistence.test(o.getURI()));

                    if (isAdd) {
                        m.getIncomingAdds().add(triple);
                    } else {
                        m.getIncomingDeletes().add(triple);
                    }
                }
            }
        });

        return moleculesByGraph;
    }

    private static Molecule getMolecule(
            Map<Node, Map<String, Molecule>> moleculesByGraph,
            Node graph,
            String uri) {

        return moleculesByGraph
                .computeIfAbsent(graph, g -> new LinkedHashMap<>())
                .computeIfAbsent(uri, u -> new Molecule(graph, u));
    }

    //classes ===========================
    private static class Molecule {

        private final Node graph;
        private final String uri;

        private boolean isNew;

        private final Set<Triple> outgoingAdds = new LinkedHashSet<>();
        private final Set<Triple> outgoingDeletes = new LinkedHashSet<>();

        private final Set<Triple> incomingAdds = new LinkedHashSet<>();
        private final Set<Triple> incomingDeletes = new LinkedHashSet<>();

        public Molecule(Node graph, String uri) {
            this.graph = graph;
            this.uri = uri;
        }

        public Node getGraph() {
            return graph;
        }

        public String getUri() {
            return uri;
        }

        public boolean isNew() {
            return isNew;
        }

        public void setNew(boolean isNew) {
            this.isNew = isNew;
        }

        public Set<Triple> getOutgoingAdds() {
            return outgoingAdds;
        }

        public Set<Triple> getOutgoingDeletes() {
            return outgoingDeletes;
        }

        public Set<Triple> getIncomingAdds() {
            return incomingAdds;
        }

        public Set<Triple> getIncomingDeletes() {
            return incomingDeletes;
        }
    }

    public static class Options {

        private String typeUri;
        private String labelUri;
        private String commentUri;
        private String iconUri;

        private String typeKey;
        private String labelKey;
        private String commentKey;
        private String iconKey;

        public Options() {
            typeUri = RDF.type.getURI();
            labelUri = SKOS.prefLabel.getURI();
            commentUri = RDFS.comment.getURI();
            iconUri = FOAF.img.getURI();

            typeKey = "@type";
            labelKey = "label";
            commentKey = "comment";
            iconKey = "icon";
        }

    }
}
