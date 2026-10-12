package de.dfki.sds.aticsqlite;

import de.dfki.sds.atic.helper.JSONUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 *
 */
public class DefinitionConstructor {

    public Model toRdfsOntology(String ontologyPrefix, String ontologyIRI, RdfsAnalyzer analyzer) {
        Model ontology = ModelFactory.createDefaultModel();

        ontology.setNsPrefix(ontologyPrefix, ontologyIRI);
        ontology.setNsPrefix("rdf", RDF.getURI());
        ontology.setNsPrefix("rdfs", RDFS.getURI());
        ontology.setNsPrefix("xsd", XSD.getURI());
        ontology.setNsPrefix("owl", OWL.getURI());

        Set<Resource> resources = new HashSet<>(analyzer.getTypes());
        resources.addAll(analyzer.getGeneratedTypes());
        resources.addAll(analyzer.getPropertyDomainsGenerated().values().stream().flatMap(Set::stream).toList());
        resources.addAll(analyzer.getPropertyRangesGenerated().values().stream().flatMap(Set::stream).toList());

        addPotentialPrefixes(ontology, resources);

        Resource ontologyResource = ontology.createResource(ontologyIRI);
        ontologyResource.addProperty(RDF.type, OWL.Ontology);

        Set<Resource> classes = new HashSet<>(analyzer.getTypes());
        classes.addAll(analyzer.getGeneratedTypes());

        for (Resource type : classes) {
            ontology.add(type, RDF.type, RDFS.Class);
            ontology.add(type, RDFS.label, toLabel(type));
        }

        Set<Property> properties = new HashSet<>();
        properties.addAll(analyzer.getPropertyDomainsGenerated().keySet());
        properties.addAll(analyzer.getPropertyRangesGenerated().keySet());

        analyzer.getSubClassOf().forEach((subClass, superClasses)
                -> superClasses.forEach(superClass
                        -> ontology.add(subClass, RDFS.subClassOf, superClass)
                )
        );

        for (Property property : properties) {
            if (property.equals(RDF.type)) {
                continue;
            }

            ontology.add(property, RDF.type, RDF.Property);
            ontology.add(property, RDFS.label, toLabel(property));

            for (Resource domain : analyzer.getPropertyDomainsGenerated().getOrDefault(property, Set.of())) {
                ontology.add(property, RDFS.domain, domain);
            }

            for (Resource range : analyzer.getPropertyRangesGenerated().getOrDefault(property, Set.of())) {
                ontology.add(property, RDFS.range, range);
            }
        }

        return ontology;
    }

    public Model toOwlOntology(String ontologyPrefix, String ontologyIRI, RdfsAnalyzer analyzer) {
        Model ontology = ModelFactory.createDefaultModel();

        ontology.setNsPrefix(ontologyPrefix, ontologyIRI);
        ontology.setNsPrefix("rdf", RDF.getURI());
        ontology.setNsPrefix("rdfs", RDFS.getURI());
        ontology.setNsPrefix("xsd", XSD.getURI());
        ontology.setNsPrefix("owl", OWL.getURI());

        Set<Resource> resources = new HashSet<>(analyzer.getTypes());
        resources.addAll(analyzer.getGeneratedTypes());
        resources.addAll(analyzer.getPropertyDomainsGenerated().values().stream().flatMap(Set::stream).toList());
        resources.addAll(analyzer.getPropertyRangesGenerated().values().stream().flatMap(Set::stream).toList());

        addPotentialPrefixes(ontology, resources);

        Resource ontologyResource = ontology.createResource(ontologyIRI);
        ontologyResource.addProperty(RDF.type, OWL.Ontology);

        Set<Resource> classes = new HashSet<>(analyzer.getTypes());
        classes.addAll(analyzer.getGeneratedTypes());

        for (Resource type : classes) {
            ontology.add(type, RDF.type, OWL.Class);
            ontology.add(type, RDFS.label, toLabel(type));
        }

        analyzer.getSubClassOf().forEach((subClass, superClasses)
                -> superClasses.forEach(superClass
                        -> ontology.add(subClass, RDFS.subClassOf, superClass)
                )
        );

        for (Property property : analyzer.getLiteralProperties()) {
            ontology.add(property, RDF.type, OWL.DatatypeProperty);
            ontology.add(property, RDFS.label, toLabel(property));

            for (Resource domain : analyzer.getPropertyDomainsGenerated().getOrDefault(property, Set.of())) {
                ontology.add(property, RDFS.domain, domain);
            }

            for (Resource range : analyzer.getPropertyRangesGenerated().getOrDefault(property, Set.of())) {
                ontology.add(property, RDFS.range, range);
            }
        }

        for (Property property : analyzer.getResourceProperties()) {
            if (property.equals(RDF.type)) {
                continue;
            }

            ontology.add(property, RDF.type, OWL.ObjectProperty);
            ontology.add(property, RDFS.label, toLabel(property));

            for (Resource domain : analyzer.getPropertyDomainsGenerated().getOrDefault(property, Set.of())) {
                ontology.add(property, RDFS.domain, domain);
            }

            for (Resource range : analyzer.getPropertyRangesGenerated().getOrDefault(property, Set.of())) {
                ontology.add(property, RDFS.range, range);
            }
        }

        return ontology;
    }

    private void addPotentialPrefixes(Model ontology, Set<Resource> resources) {
        Map<String, String> prefixes = ontology.getNsPrefixMap();

        for (Resource resource : resources) {
            if (!resource.isURIResource()) {
                continue;
            }

            String uri = resource.getURI();
            String localName = AticGraphUtils.getLocalName(uri);

            if (localName == null || localName.isBlank()) {
                continue;
            }

            String namespace = uri.substring(0, uri.length() - localName.length());

            if (prefixes.containsValue(namespace)) {
                continue;
            }

            String prefix = null;
            String[] parts = namespace.split("[/#:]");

            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = parts[i].replaceAll("[^A-Za-z]", "");

                if (!candidate.isBlank() && !candidate.equalsIgnoreCase("ns")) {
                    prefix = Character.toLowerCase(candidate.charAt(0))
                            + candidate.substring(1);
                    break;
                }
            }

            if (prefix == null || prefixes.containsKey(prefix)) {
                continue;
            }

            ontology.setNsPrefix(prefix, namespace);
            prefixes.put(prefix, namespace);
        }
    }

    private String toLabel(Resource resource) {
        String localName = AticGraphUtils.getLocalName(resource.getURI());

        if (localName == null || localName.isBlank()) {
            return resource.getURI();
        }

        String label = localName.replaceAll("([a-z])([A-Z])", "$1 $2");

        return Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    // ============================================================
    // Suggested templates based on RDFS analysis
    // ============================================================
    /**
     * Builds a suggested starting template for each type (class) found in the analyzed ABox. The returned map key is the type resource, and the value is a JSON
     * object with a valid bridge template structure that a user can adjust later.
     */
    public Map<Resource, JSONObject> buildSuggestedTemplates(RdfsAnalyzer analyzer) {
        Map<Resource, JSONObject> templates = new HashMap<>();

        Set<Resource> allTypes = new HashSet<>();
        allTypes.addAll(analyzer.getTypes());
        //allTypes.addAll(analyzer.getGeneratedTypes());

        for (Resource type : allTypes) {
            templates.put(type, buildSuggestedTemplate(type, analyzer));
        }

        return templates;
    }

    /**
     * Builds a single suggested template for the given type.
     */
    private JSONObject buildSuggestedTemplate(Resource type, RdfsAnalyzer analyzer) {
        String typeLocalName = AticGraphUtils.getLocalName(type.getURI());
        if (typeLocalName == null || typeLocalName.isBlank()) {
            typeLocalName = "resource";
        }
        // Ensure lowercase variable names
        typeLocalName = typeLocalName.toLowerCase();

        // --- Collect prefixes first ---
        Map<String, String> prefixMap = buildPrefixMap(analyzer);

        // --- Determine variable names (lowercase) avoiding clashes ---
        Map<String, String> varMap = new LinkedHashMap<>();
        varMap.put(typeLocalName, typeLocalName); // e.g. "person"

        Map<Resource, Set<Property>> type2prop = analyzer.getType2prop();
        Set<Property> props = type2prop.getOrDefault(type, Set.of());

        // Check for clashes: if a property has the same local name as the type
        Set<String> usedVars = new HashSet<>(varMap.values());
        for (Property prop : props) {
            String propLocalName = AticGraphUtils.getLocalName(prop.getURI());
            if (propLocalName == null || propLocalName.isBlank()) {
                continue;
            }
            if (prop.equals(RDF.type)) {
                continue;
            }
            String baseVar = propLocalName;
            if (usedVars.contains(baseVar)) {
                // Rename: append numeric suffix until unique
                int suffix = 1;
                String variant = baseVar + suffix;
                while (usedVars.contains(variant)) {
                    suffix++;
                    variant = baseVar + suffix;
                }
                varMap.put(propLocalName, variant);
            } else {
                varMap.put(propLocalName, baseVar);
            }
        }

        String subjectVar = "?" + varMap.get(typeLocalName);

        // --- $map ---
        JSONObject template = JSONUtils.createJSONObject();
        template.put("$type", "array");

        JSONObject map = JSONUtils.createJSONObject();
        map.put("@id", subjectVar);

        // --- $where ---
        List<String> wherePatterns = new ArrayList<>();
        String typeCuri = uriToCuri(type.getURI(), prefixMap);
        wherePatterns.add(subjectVar + " a " + typeCuri);

        for (Property prop : props) {
            String propLocalName = AticGraphUtils.getLocalName(prop.getURI());
            if (propLocalName == null || propLocalName.isBlank()) {
                continue;
            }
            if (prop.equals(RDF.type)) {
                continue;
            }

            String propVar = "?" + varMap.get(propLocalName);
            String propCuri = uriToCuri(prop.getURI(), prefixMap);

            // Determine if this is a resource property that should get a nested sub-query.
            boolean isNested = false;
            if (analyzer.getResourceProperties().contains(prop)
                    && analyzer.getDomainCardinality().get(prop) == RdfsAnalyzer.Cardinality.MULTI
                    && analyzer.getPropertyRanges().containsKey(prop)
                    && !analyzer.getPropertyRanges().get(prop).isEmpty()) {

                Resource rangeType = analyzer.getPropertyRanges().get(prop).iterator().next();
                String relVar = varMap.get(propLocalName);
                JSONObject nested = buildNestedMap(propLocalName, relVar, subjectVar, propCuri, rangeType, analyzer);
                map.put(propLocalName, nested);
                isNested = true;
            }

            // Simple property: map to a SPARQL variable (only when not nested)
            // Language-string properties → $map value includes "$string" modifier
            if (!isNested) {
                String valueStr = propVar;
                if (analyzer.getLangStringProperties().contains(prop)) {
                    valueStr = propVar + "$string";
                }
                map.put(propLocalName, valueStr);
                // Add triple pattern to $where (no trailing '.')
                wherePatterns.add(subjectVar + " " + propCuri + " " + propVar);
            }
        }

        template.put("$map", map);
        template.put("$where", new JSONArray(wherePatterns));

        // --- $limit ---
        template.put("$limit", 10);

        // --- @context ---
        template.put("@context", new JSONObject(prefixMap));

        return template;
    }

    /**
     * Builds a nested map object for a resource (1:N / relationship) property.
     */
    private JSONObject buildNestedMap(String propLocalName, String relVar, String parentSubjectVar,
            String propCuri, Resource rangeType, RdfsAnalyzer analyzer) {
        JSONObject nested = JSONUtils.createJSONObject();
        nested.put("$type", "array");

        // Keep it simple: just the @id for the nested query
        // The user can add more properties
        nested.put("$map", JSONUtils.createJSONObject().put("@id", "?" + relVar));

        // Connect parent subject to nested subject via the relationship property
        // (no trailing '.')
        String connectPattern = parentSubjectVar + " " + propCuri + " " + "?" + relVar;

        List<String> wherePatterns = new ArrayList<>();
        wherePatterns.add(connectPattern);
        nested.put("$where", new JSONArray(wherePatterns));

        return nested;
    }

    /**
     * Builds a prefix map from all URIs referenced in the analyzer.
     */
    private Map<String, String> buildPrefixMap(RdfsAnalyzer analyzer) {
        Set<String> uris = new HashSet<>();

        // Include all domain and range URIs
        for (Set<Resource> domains : analyzer.getPropertyDomains().values()) {
            for (Resource r : domains) {
                uris.add(r.getURI());
            }
        }
        for (Set<Resource> ranges : analyzer.getPropertyRanges().values()) {
            for (Resource r : ranges) {
                uris.add(r.getURI());
            }
        }

        return buildPrefixMap(uris);
    }

    /**
     * Builds a prefix map from a set of URIs.
     */
    private Map<String, String> buildPrefixMap(Set<String> uris) {
        Map<String, String> prefixMap = new LinkedHashMap<>();

        Map<String, String> standardNamespaces = new LinkedHashMap<>();
        standardNamespaces.put("rdf", RDF.getURI());
        standardNamespaces.put("rdfs", RDFS.getURI());
        standardNamespaces.put("xsd", XSD.getURI());
        standardNamespaces.put("owl", OWL.getURI());

        for (Map.Entry<String, String> entry : standardNamespaces.entrySet()) {
            String prefix = entry.getKey();
            String namespace = entry.getValue();

            if (uris.stream().anyMatch(uri -> uri.startsWith(namespace))) {
                prefixMap.put(prefix, namespace);
            }
        }

        for (String uri : uris) {
            if (uri == null) {
                continue;
            }

            if (standardNamespaces.values().stream().anyMatch(uri::startsWith)) {
                continue;
            }

            int sep = Math.max(uri.lastIndexOf('/'), uri.lastIndexOf('#'));
            if (sep == -1) {
                continue;
            }

            String namespace = uri.substring(0, sep + 1);

            if (prefixMap.containsValue(namespace)) {
                continue;
            }

            String prefix = inferPrefixForUri(uri, namespace);

            if (prefix != null && !prefixMap.containsKey(prefix)) {
                prefixMap.put(prefix, namespace);
            }
        }

        return prefixMap;
    }

    /**
     * Converts a URI to a CURIE using the given prefix map. If no prefix is found for the URI's namespace, returns the full URI.
     */
    private String uriToCuri(String uri, Map<String, String> prefixMap) {
        // Try to find the longest matching namespace
        String bestPrefix = null;
        String bestNamespace = null;
        String bestLength = null;

        for (Map.Entry<String, String> entry : prefixMap.entrySet()) {
            String namespace = entry.getValue();
            if (uri.startsWith(namespace)) {
                if (bestLength == null || namespace.length() > bestNamespace.length()) {
                    bestPrefix = entry.getKey();
                    bestNamespace = namespace;
                    bestLength = namespace;
                }
            }
        }

        if (bestPrefix != null && bestNamespace != null) {
            String localPart = uri.substring(bestNamespace.length());
            // Check if local part is a valid CURIE local name (no spaces, valid chars)
            if (!localPart.isBlank() && localPart.matches("[a-zA-Z_][a-zA-Z0-9_\\-]*")) {
                return bestPrefix + ":" + localPart;
            }
        }

        // Fallback: return full URI
        return "<" + uri + ">";
    }

    /**
     * Infers a prefix for a namespace from the full URI (using local part as hint).
     */
    private String inferPrefixForUri(String uri, String namespace) {
        // Known prefixes with high priority
        if (namespace.equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#")) {
            return "rdf";
        }
        if (namespace.equals("http://www.w3.org/2000/01/rdf-schema#")) {
            return "rdfs";
        }
        if (namespace.equals("http://www.w3.org/2001/XMLSchema#")) {
            return "xsd";
        }
        if (namespace.equals("http://www.w3.org/2002/07/owl#")) {
            return "owl";
        }
        if (namespace.equals("http://xmlns.com/foaf/0.1/")) {
            return "foaf";
        }
        if (namespace.equals("http://schema.org/")) {
            return "schema";
        }

        // Try to derive from URI parts
        String[] parts = namespace.split("[/#:]");
        for (int i = parts.length - 1; i >= 0; i--) {
            String candidate = parts[i].replaceAll("[^A-Za-z]", "");
            if (!candidate.isBlank() && !candidate.equalsIgnoreCase("ns")) {
                return Character.toLowerCase(candidate.charAt(0)) + candidate.substring(1);
            }
        }

        return null;
    }

}
