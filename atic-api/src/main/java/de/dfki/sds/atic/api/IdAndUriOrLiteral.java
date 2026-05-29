

package de.dfki.sds.atic.api;

import org.apache.jena.graph.Node;

/**
 *
 */
public class IdAndUriOrLiteral extends IdAndUri {
    
    private long id;
    
    private String uri;
    private Node literal;

    public IdAndUriOrLiteral(long id, String uri, Node literal) {
        super(id, uri);
        if(uri != null && literal != null) {
            throw new IllegalArgumentException("Both are given: uri and literal");
        }
        this.literal = literal;
    }

    public Node getLiteral() {
        return literal;
    }
    
    public boolean isLiteral() {
        return literal != null;
    }
    
    public boolean isURI() {
        return uri != null;
    }
    
    public static IdAndUriOrLiteral create(Node literal) {
        return new IdAndUriOrLiteral(-1, null, literal);
    }
    
    public static IdAndUriOrLiteral create(long id, String uri) {
        return new IdAndUriOrLiteral(id, uri, null);
    }
    
    public static IdAndUriOrLiteral create(long id, Node node) {
        return new IdAndUriOrLiteral(id, node.isBlank() ? node.getBlankNodeLabel() : node.getURI(), null);
    }

    @Override
    public String toString() {
        return "IdAndUriOrLiteral{" + "id=" + id + ", uri=" + uri + ", literal=" + literal + '}';
    }
    
    

}
