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
