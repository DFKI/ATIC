
package de.dfki.sds.atic.vocab;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;

/**
 * Nepomuk Annotation Ontology.
 */
public class NAO {

    public static final String NS = "http://www.semanticdesktop.org/ontologies/2007/08/15/nao#";
    
    public static final Resource Tag = ResourceFactory.createResource(NS + "Tag");
    
    public static final Property hasTopic = ResourceFactory.createProperty(NS + "hasTopic");

}
