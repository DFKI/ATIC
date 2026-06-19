

package de.dfki.sds.atic.api;

/**
 *
 */
public class IdAndUriTriple {

    private IdAndUri s;
    private IdAndUri p;
    private IdAndUriOrLiteral o;

    private double confidence = 1.0;
    private double applicability = 1.0;
    
    public IdAndUriTriple(IdAndUri s, IdAndUri p, IdAndUriOrLiteral o) {
        this.s = s;
        this.p = p;
        this.o = o;
        this.confidence = 1.0;
        this.applicability = 1.0;
    }

    public IdAndUriTriple(IdAndUri s, IdAndUri p, IdAndUriOrLiteral o, double confidence) {
        this(s, p, o);
        setConfidence(confidence);
        this.applicability = 1.0;
    }

    public IdAndUriTriple(IdAndUri s, IdAndUri p, IdAndUriOrLiteral o, double confidence, double applicability) {
        this.s = s;
        this.p = p;
        this.o = o;
        setConfidence(confidence);
        setApplicability(applicability);
    }
    
    public static IdAndUriTriple create(IdAndUri s, IdAndUri p, IdAndUriOrLiteral o) {
        return new IdAndUriTriple(s, p, o);
    }

    public static IdAndUriTriple create(IdAndUri s, IdAndUri p, IdAndUriOrLiteral o, double confidence) {
        return new IdAndUriTriple(s, p, o, confidence);
    }

    public static IdAndUriTriple create(IdAndUri s, IdAndUri p, IdAndUriOrLiteral o, double confidence, double applicability) {
        return new IdAndUriTriple(s, p, o, confidence, applicability);
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

    public double getApplicability() {
        return applicability;
    }

    public final void setApplicability(double applicability) {
        if (applicability < -1.0 || applicability > 1.0) {
            throw new IllegalArgumentException("Applicability must be between -1 and 1");
        }
        this.applicability = applicability;
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
        return "IdAndUriTriple{" + "s=" + s + ", p=" + p + ", o=" + o + ", confidence=" + confidence + ", applicability=" + applicability + '}';
    }
    
}
