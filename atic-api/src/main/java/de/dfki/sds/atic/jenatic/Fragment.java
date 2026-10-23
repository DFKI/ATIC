package de.dfki.sds.atic.jenatic;

import de.dfki.sds.atic.helper.JSONUtils;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.impl.ModelCom;
import org.apache.jena.rdf.model.impl.ResourceImpl;
import org.json.JSONObject;

public final class Fragment extends ResourceImpl {

    private final Model subgraph;
    private final FragmentSettings settings;
    private final Map<Resource, Fragment> fragmentMap;

    private Fragment(
            Node subject,
            ModelCom backingModel,
            Model subgraph,
            FragmentSettings settings,
            Map<Resource, Fragment> fragmentMap) {

        super(subject, backingModel);
        this.subgraph = copyModel(subgraph);
        this.settings = Objects.requireNonNull(settings);
        this.fragmentMap = new HashMap<>(Objects.requireNonNull(fragmentMap, "fragmentMap must not be null"));
    }

    private static Model copyModel(Model source) {
        Model copy = ModelFactory.createDefaultModel();
        copy.add(source);
        return copy;
    }

    /**
     * Returns a defensive copy of the fragment's subgraph.
     *
     * @return subgraph of the fragment
     */
    public Model getSubgraph() {
        return copyModel(subgraph);
    }

    public FragmentSettings getSettings() {
        return settings;
    }

    //===================================================
    //JSON
    public JSONObject toJsonFragment() {
        return toJsonFragment(Locale.ENGLISH);
    }

    public JSONObject toJsonFragment(Locale locale) {
        Objects.requireNonNull(locale, "locale");
        return toJsonFragment(locale, false, new HashSet<>());
    }

    public JSONObject toJsonFragment(Locale locale, boolean recursiveContext) {
        Objects.requireNonNull(locale, "locale");
        return toJsonFragment(locale, recursiveContext, new HashSet<>());
    }

    private JSONObject toJsonFragment(Locale locale, boolean recursiveContext, Set<Node> recursionPath) {
        Node subject = asNode();

        JSONObject json = JSONUtils.createJSONObject()
                .put("@id", getURI());

        JSONObject context = JSONUtils.createJSONObject();

        if (!recursionPath.add(subject)) {
            return json;
        }

        try {
            for (FragmentSettings.FragmentProperty property : settings.getProperties()) {

                //TODO this needs an implementation
                if (property.hasTemplateRanking()) {
                    continue;
                }

                if (property.hasPropertyRanking()) {
                    for (Node prop : property.getPropertyRanking()) {

                        List<Triple> matching = subgraph.getGraph()
                                .find(subject, prop, Node.ANY)
                                .toList();

                        //try until one is not empty
                        if (matching.isEmpty()) {
                            continue;
                        }

                        Node object = selectObject(
                                matching,
                                locale,
                                property.isLanguageAware());

                        String key = property.getKey();

                        JSONObject contextEntry = JSONUtils.createJSONObject()
                                .put("@id", prop.getURI());

                        if (object.isLiteral()) {
                            String language = object.getLiteralLanguage();
                            String datatype = object.getLiteralDatatypeURI();

                            if (language != null && !language.isEmpty()) {
                                contextEntry.put("@language", language);
                            } else if (datatype != null) {
                                contextEntry.put("@type", datatype);
                            }

                            json.put(key, literalToJsonValue(object));
                        } else {
                            json.put(key, objectToJson(object, locale, recursiveContext, recursionPath));
                        }

                        //do not add "@type" to "@context": it will be a keyword redefinition error in JSON-LD parsing
                        if (key.equals("@type")) {
                            continue;
                        }

                        context.put(key, contextEntry);

                        //we only need one from the ranked list
                        break;
                    }
                }
            }

            if (!context.isEmpty()) {
                json.put("@context", context);
            }

            return json;
        } finally {
            recursionPath.remove(subject);
        }
    }

    private Object literalToJsonValue(Node literal) {
        String lexicalForm = literal.getLiteralLexicalForm();
        String datatype = literal.getLiteralDatatypeURI();

        if (datatype == null
                || XSDDatatype.XSDstring.getURI().equals(datatype)
                || !literal.getLiteralLanguage().isEmpty()) {
            return lexicalForm;
        }

        Object value = literal.getLiteralValue();

        if (value instanceof Boolean
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Double
                || value instanceof Float
                || value instanceof BigDecimal
                || value instanceof BigInteger) {
            return value;
        }

        // Preserve lexical representation for unsupported native types.
        return lexicalForm;
    }

    private Object objectToJson(Node object, Locale locale, boolean recursiveContext, Set<Node> recursionPath) {

        // A Jena model stores RDF nodes, not the original Java Resource
        // implementation. Resolve the node through fragmentMap before
        // serializing it as an ordinary URI/blank resource.
        if (object.isURI() || object.isBlank()) {
            Fragment fragment = findFragment(object);
            if (fragment != null) {
                JSONObject fragmentObj = fragment.toJsonFragment(locale, recursiveContext, recursionPath);
                if (!recursiveContext) {
                    fragmentObj.remove("@context");
                }
                return fragmentObj;
            }
        }

        if (object.isURI()) {
            return new JSONObject().put("@id", object.getURI());
        }

        //TODO correct blank handling
        if (object.isBlank()) {
            return "_:" + object.getBlankNodeLabel();
        }

        if (object.isLiteral()) {
            String lexicalForm = object.getLiteralLexicalForm();
            String language = object.getLiteralLanguage();

            // Preserve the language tag of the selected literal.
            if (language != null && !language.isEmpty()) {
                return new JSONObject()
                        .put("@value", lexicalForm)
                        .put("@language", language);
            }

            String datatype = object.getLiteralDatatypeURI();

            if (datatype == null
                    || XSDDatatype.XSDstring.getURI().equals(datatype)) {
                return lexicalForm;
            }

            return new JSONObject()
                    .put("@value", lexicalForm)
                    .put("@type", datatype);
        }

        return object.toString();
    }

    private Node selectObject(List<Triple> triples, Locale locale, boolean languageAware) {

        List<Node> objects = triples.stream()
                .map(Triple::getObject)
                .toList();

        if (!languageAware) {
            return objects.get(0);
        }

        List<Node> languageLiterals = objects.stream()
                .filter(Node::isLiteral)
                .filter(node
                        -> !node.getLiteralLanguage().isEmpty())
                .toList();

        if (languageLiterals.isEmpty()) {
            return objects.get(0);
        }

        String requestedTag = locale.toLanguageTag();
        String requestedLanguage = locale.getLanguage();

        // 1. Exact locale match, e.g. en-GB.
        for (Node node : languageLiterals) {
            if (node.getLiteralLanguage()
                    .equalsIgnoreCase(requestedTag)) {
                return node;
            }
        }

        // 2. Same language, e.g. en-US for en-GB.
        for (Node node : languageLiterals) {
            Locale candidate
                    = Locale.forLanguageTag(node.getLiteralLanguage());

            if (candidate.getLanguage()
                    .equalsIgnoreCase(requestedLanguage)) {
                return node;
            }
        }

        // 3. English fallback.
        for (Node node : languageLiterals) {
            Locale candidate
                    = Locale.forLanguageTag(node.getLiteralLanguage());

            if ("en".equalsIgnoreCase(candidate.getLanguage())) {
                return node;
            }
        }

        // 4. Untagged literal.
        for (Node node : objects) {
            if (node.isLiteral()
                    && node.getLiteralLanguage().isEmpty()) {
                return node;
            }
        }

        // 5. First available object.
        return objects.get(0);
    }

    private Fragment findFragment(Node node) {
        // Try normal Resource equality first, then compare RDF nodes directly
        // so lookup remains reliable across Jena model instances.
        Resource resource = subgraph.asRDFNode(node).asResource();
        Fragment fragment = fragmentMap.get(resource);
        if (fragment != null) {
            return fragment;
        }

        for (Map.Entry<Resource, Fragment> entry : fragmentMap.entrySet()) {
            if (entry.getKey().asNode().equals(node)) {
                return entry.getValue();
            }
        }
        return null;
    }

    //===================================================
    //builder
    public static Builder builder(String uri) {
        return new Builder(uri);
    }

    public static Builder builder(Node node) {
        return new Builder(node);
    }

    public static Builder builder(Resource resource) {
        return new Builder(resource);
    }

    public static final class Builder {

        private final ModelCom model;
        private final Resource subject;
        private final Model subgraph;
        private final Map<Resource, Fragment> fragmentMap = new HashMap<>();

        private FragmentSettings settings
                = FragmentSettings.defaultSettings();

        private boolean built;

        private Builder(String uri) {
            Objects.requireNonNull(uri, "uri must not be null");

            if (uri.isBlank()) {
                throw new IllegalArgumentException(
                        "uri must not be blank");
            }

            this.model = newModel();
            this.subgraph = model;
            this.subject = model.createResource(uri);
        }

        private Builder(Node node) {
            Objects.requireNonNull(node, "node must not be null");

            this.model = newModel();
            this.subgraph = model;

            RDFNode rdfNode = model.asRDFNode(node);

            if (!rdfNode.isResource()) {
                throw new IllegalArgumentException(
                        "Subject node must be a URI or blank node");
            }

            this.subject = rdfNode.asResource();
        }

        private Builder(Resource resource) {
            Objects.requireNonNull(
                    resource, "resource must not be null");

            this.model = newModel();
            this.subgraph = model;
            this.subject = model.asRDFNode(
                    resource.asNode()).asResource();
        }

        private static ModelCom newModel() {
            return (ModelCom) ModelFactory.createDefaultModel();
        }

        private void checkNotBuilt() {
            if (built) {
                throw new IllegalStateException(
                        "Fragment has already been built");
            }
        }

        public Builder settings(FragmentSettings settings) {
            checkNotBuilt();
            this.settings = Objects.requireNonNull(settings);
            return this;
        }

        // --------------------------------------------------
        // Predicate: String URI
        // --------------------------------------------------
        public Builder add(String predicateUri, String objectUri) {
            return add(property(predicateUri), resource(objectUri));
        }

        public Builder add(String predicateUri, Node object) {
            return add(property(predicateUri), object);
        }

        public Builder add(String predicateUri, Resource object) {
            return add(property(predicateUri), object);
        }

        public Builder add(String predicateUri, Literal object) {
            return add(property(predicateUri), object);
        }

        // --------------------------------------------------
        // Predicate: Node
        // --------------------------------------------------
        public Builder add(Node predicate, String objectUri) {
            return add(property(predicate), resource(objectUri));
        }

        public Builder add(Node predicate, Node object) {
            return add(property(predicate), object);
        }

        public Builder add(Node predicate, Resource object) {
            return add(property(predicate), object);
        }

        public Builder add(Node predicate, Literal object) {
            return add(property(predicate), object);
        }

        // --------------------------------------------------
        // Predicate: Property
        // --------------------------------------------------
        public Builder add(Property predicate, String objectUri) {
            return add(predicate, resource(objectUri));
        }

        public Builder add(Property predicate, Node object) {
            checkNotBuilt();
            Objects.requireNonNull(object, "object must not be null");

            return add(predicate, model.asRDFNode(object));
        }

        public Builder add(Property predicate, Resource object) {
            checkNotBuilt();
            Objects.requireNonNull(object, "object must not be null");

            if (object instanceof Fragment fragment) {
                Resource key = model.asRDFNode(fragment.asNode()).asResource();
                fragmentMap.put(key, fragment);
            }

            return add(predicate, model.asRDFNode(object.asNode()));
        }

        public Builder add(Property predicate, Literal object) {
            checkNotBuilt();
            Objects.requireNonNull(object, "object must not be null");

            return add(predicate, model.asRDFNode(object.asNode()));
        }

        // --------------------------------------------------
        // Internal helpers
        // --------------------------------------------------
        private Builder add(Property predicate, RDFNode object) {
            checkNotBuilt();

            Objects.requireNonNull(
                    predicate, "predicate must not be null");
            Objects.requireNonNull(
                    object, "object must not be null");

            subgraph.add(subject, predicate, object);
            return this;
        }

        private Property property(String uri) {
            checkNotBuilt();
            Objects.requireNonNull(uri, "predicate URI must not be null");

            if (uri.isBlank()) {
                throw new IllegalArgumentException(
                        "Predicate URI must not be blank");
            }

            return model.createProperty(uri);
        }

        private Property property(Node node) {
            checkNotBuilt();
            Objects.requireNonNull(
                    node, "predicate node must not be null");

            if (!node.isURI()) {
                throw new IllegalArgumentException(
                        "Predicate node must be a URI node");
            }

            return model.createProperty(node.getURI());
        }

        private Resource resource(String uri) {
            checkNotBuilt();
            Objects.requireNonNull(uri, "object URI must not be null");
            return model.createResource(uri);
        }

        // --------------------------------------------------
        // Build
        // --------------------------------------------------
        public Fragment build() {
            checkNotBuilt();

            // Prevent further builder operations.
            built = true;

            // Copy the triples into a separate snapshot.
            Model snapshot = copyModel(subgraph);

            // Use a separate backing model for inherited
            // ResourceImpl operations.
            ModelCom backingModel = newModel();

            return new Fragment(
                    subject.asNode(),
                    backingModel,
                    snapshot,
                    settings,
                    fragmentMap);
        }
    }
}
