

package de.dfki.sds.atic.api;

/**
 *
 */
public class IdAndUriTriple {

    private IdAndUri s;
    private IdAndUri p;
    private IdAndUriOrLiteral o;

    private double confidence = 1.0;
    
    public IdAndUriTriple(IdAndUri s, IdAndUri p, IdAndUriOrLiteral o) {
        this.s = s;
        this.p = p;
        this.o = o;
        this.confidence = 1.0;
    }
    
    public IdAndUriTriple(IdAndUri s, IdAndUri p, IdAndUriOrLiteral o, double confidence) {
        this(s, p, o);
        setConfidence(confidence);
    }
    
    public static IdAndUriTriple create(IdAndUri s, IdAndUri p, IdAndUriOrLiteral o) {
        return new IdAndUriTriple(s, p, o);
    }

    public static IdAndUriTriple create(IdAndUri s, IdAndUri p, IdAndUriOrLiteral o, double confidence) {
        return new IdAndUriTriple(s, p, o, confidence);
    }
    
    // -----------------------
    // Accessors
    // -----------------------
    public double getConfidence() {
        return confidence;
    }

    public final void setConfidence(double confidence) {
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("Confidence must be between 0 and 1");
        }
        this.confidence = confidence;
    }

    public IdAndUri getSubject() {
        return s;
    }

    public IdAndUri getPredicate() {
        return p;
    }

    public IdAndUriOrLiteral getObject() {
        return o;
    }

    @Override
    public String toString() {
        return "IdAndUriTriple{" + "s=" + s + ", p=" + p + ", o=" + o + ", confidence=" + confidence + '}';
    }
    
}
