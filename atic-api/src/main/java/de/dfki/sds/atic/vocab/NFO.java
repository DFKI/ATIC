
package de.dfki.sds.atic.vocab;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;

/**
 * NEPOMUK File Ontology.
 */
public class NFO {

    public static final String NS = "http://www.semanticdesktop.org/ontologies/2007/03/22/nfo#";
    
    public static final Resource FileDataObject = ResourceFactory.createResource(NS + "FileDataObject");
    public static final Resource Folder = ResourceFactory.createResource(NS + "Folder");
    public static final Resource Website = ResourceFactory.createResource(NS + "Website");
    public static final Resource Audio = ResourceFactory.createResource(NS + "Audio");
    public static final Resource Image = ResourceFactory.createResource(NS + "Image");
    public static final Resource Spreadsheet = ResourceFactory.createResource(NS + "Spreadsheet");
    public static final Resource TextDocument = ResourceFactory.createResource(NS + "TextDocument");
    public static final Resource Attachment = ResourceFactory.createResource(NS + "Attachment");
    
    public static final Property fileName = ResourceFactory.createProperty(NS + "fileName");
    public static final Property hidden = ResourceFactory.createProperty(NS + "hidden");
    public static final Property belongsToContainer = ResourceFactory.createProperty(NS + "belongsToContainer");
    public static final Property contains = ResourceFactory.createProperty(NS + "contains");

}
