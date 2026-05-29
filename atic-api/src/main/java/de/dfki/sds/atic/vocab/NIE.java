
package de.dfki.sds.atic.vocab;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;

/**
 * NEPOMUK Information Element Ontology.
 */
public class NIE {

    public static final String NS = "http://www.semanticdesktop.org/ontologies/2007/01/19/nie#";
    
    /**
     * Actually does not exist in ontology. 
     */
    public static final Resource Clipboard = ResourceFactory.createResource(NS + "Clipboard");
    
    public static final Property htmlContent = ResourceFactory.createProperty(NS + "htmlContent");
    public static final Property url = ResourceFactory.createProperty(NS + "url");

}
