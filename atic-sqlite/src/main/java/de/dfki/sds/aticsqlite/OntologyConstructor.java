package de.dfki.sds.aticsqlite;

import java.util.HashSet;
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

/**
 *
 */
public class OntologyConstructor {

    public Model toRdfsOntology(String ontologyPrefix, String ontologyIRI, RdfsAnalyzer analyzer) {
        Model ontology = ModelFactory.createDefaultModel();

        ontology.setNsPrefix(ontologyPrefix, ontologyIRI);
        ontology.setNsPrefix("rdf", RDF.getURI());
        ontology.setNsPrefix("rdfs", RDFS.getURI());
        ontology.setNsPrefix("xsd", XSD.getURI());
        ontology.setNsPrefix("owl", OWL.getURI());

        Set<Resource> resources = new HashSet<>(analyzer.getTypes());
        resources.addAll(analyzer.getPropertyDomains().keySet());
        resources.addAll(analyzer.getPropertyRanges().keySet());

        addPotentialPrefixes(ontology, resources);

        Resource ontologyResource = ontology.createResource(ontologyIRI);
        ontologyResource.addProperty(RDF.type, OWL.Ontology);

        for (Resource type : analyzer.getTypes()) {
            ontology.add(type, RDF.type, RDFS.Class);
            ontology.add(type, RDFS.label, toLabel(type));
        }

        Set<Property> properties = new HashSet<>();
        properties.addAll(analyzer.getPropertyDomains().keySet());
        properties.addAll(analyzer.getPropertyRanges().keySet());

        for (Property property : properties) {
            if (property.equals(RDF.type)) {
                continue;
            }

            ontology.add(property, RDF.type, RDF.Property);
            ontology.add(property, RDFS.label, toLabel(property));

            for (Resource domain : analyzer.getPropertyDomains()
                    .getOrDefault(property, Set.of())) {
                ontology.add(property, RDFS.domain, domain);
            }

            for (Resource range : analyzer.getPropertyRanges()
                    .getOrDefault(property, Set.of())) {
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
        resources.addAll(analyzer.getPropertyDomains().keySet());
        resources.addAll(analyzer.getPropertyRanges().keySet());

        addPotentialPrefixes(ontology, resources);

        Resource ontologyResource = ontology.createResource(ontologyIRI);
        ontologyResource.addProperty(RDF.type, OWL.Ontology);

        for (Resource type : analyzer.getTypes()) {
            ontology.add(type, RDF.type, OWL.Class);
            ontology.add(type, RDFS.label, toLabel(type));
        }

        for (Property property : analyzer.getLiteralProperties()) {
            ontology.add(property, RDF.type, OWL.DatatypeProperty);
            ontology.add(property, RDFS.label, toLabel(property));

            for (Resource domain : analyzer.getPropertyDomains()
                    .getOrDefault(property, Set.of())) {
                ontology.add(property, RDFS.domain, domain);
            }

            for (Resource range : analyzer.getPropertyRanges()
                    .getOrDefault(property, Set.of())) {
                ontology.add(property, RDFS.range, range);
            }
        }

        for (Property property : analyzer.getResourceProperties()) {
            if (property.equals(RDF.type)) {
                continue;
            }

            ontology.add(property, RDF.type, OWL.ObjectProperty);
            ontology.add(property, RDFS.label, toLabel(property));

            for (Resource domain : analyzer.getPropertyDomains()
                    .getOrDefault(property, Set.of())) {
                ontology.add(property, RDFS.domain, domain);
            }

            for (Resource range : analyzer.getPropertyRanges()
                    .getOrDefault(property, Set.of())) {
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
            String localName = RdfsAnalyzer.getLocalName(uri);

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
        String localName = RdfsAnalyzer.getLocalName(resource.getURI());

        if (localName == null || localName.isBlank()) {
            return resource.getURI();
        }

        String label = localName.replaceAll("([a-z])([A-Z])", "$1 $2");

        return Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

}
