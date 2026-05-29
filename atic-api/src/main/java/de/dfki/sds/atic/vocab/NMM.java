
package de.dfki.sds.atic.vocab;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;

/**
 * NMM - Nepomuk Multimedia Ontology .
 */
public class NMM {

    public static final String NS = "http://www.semanticdesktop.org/ontologies/2009/02/19/nmm#";
    
    public static final Resource MusicAlbum = ResourceFactory.createResource(NS + "MusicAlbum");
    public static final Resource MusicPiece = ResourceFactory.createResource(NS + "MusicPiece");
    
    public static final Property musicAlbum = ResourceFactory.createProperty(NS + "musicAlbum");
    public static final Property performer = ResourceFactory.createProperty(NS + "performer");
    
}
