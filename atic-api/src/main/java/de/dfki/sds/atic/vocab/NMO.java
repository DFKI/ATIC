
package de.dfki.sds.atic.vocab;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;

/**
 * Nepomuk Message Ontology.
 */
public class NMO {

    public static final String NS = "http://www.semanticdesktop.org/ontologies/2007/03/22/nmo#";
    
    public static final Resource Email = ResourceFactory.createResource(NS + "Email");
    
    /**
     * Not part of ontology.
     */
    public static final Resource EmailFolder = ResourceFactory.createResource(NS + "EmailFolder");
    
    public static final Property messageSubject = ResourceFactory.createProperty(NS + "messageSubject");
    
    public static final Property messageId = ResourceFactory.createProperty(NS + "messageId");

}
