

package de.dfki.sds.atic.api;

/**
 *
 */
public class IdAndUriQuad {

    private IdAndUri g;
    private IdAndUriTriple t;

    public IdAndUriQuad(IdAndUri g, IdAndUriTriple t) {
        this.g = g;
        this.t = t;
    }
    
    public static IdAndUriQuad create(IdAndUri g, IdAndUriTriple t) {
        return new IdAndUriQuad(g, t);
    }

    public IdAndUri getGraph() {
        return g;
    }

    public IdAndUriTriple getTriple() {
        return t;
    }
    
    public IdAndUri getSubject() {
        return t.getSubject();
    }

    public IdAndUri getPredicate() {
        return t.getPredicate();
    }

    public IdAndUriOrLiteral getObject() {
        return t.getObject();
    }

    @Override
    public String toString() {
        return "IdAndUriQuad{" + "g=" + g + ", t=" + t + '}';
    }
    
    
}
