
package de.dfki.sds.atic.jenatic;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;

/**
 * 
 */
public class AticTriple extends Triple {

    private double confidence = 1.0;
    private double applicability = 1.0;

    // -----------------------
    // Constructors
    // -----------------------
    public AticTriple(Triple t) {
        super(t.getSubject(), t.getPredicate(), t.getObject());
        this.confidence = 1.0;
        this.applicability = 1.0;
    }

    public AticTriple(Node s, Node p, Node o) {
        super(s, p, o);
        this.confidence = 1.0;
        this.applicability = 1.0;
    }

    public AticTriple(Node s, Node p, Node o, double confidence) {
        super(s, p, o);
        setConfidence(confidence);
        this.applicability = 1.0;
    }

    public AticTriple(Node s, Node p, Node o, double confidence, double applicability) {
        super(s, p, o);
        setConfidence(confidence);
        setApplicability(applicability);
    }

    // -----------------------
    // Static factory methods
    // -----------------------
    public static AticTriple create(Triple t) {
        return new AticTriple(t);
    }

    public static AticTriple create(Node s, Node p, Node o) {
        return new AticTriple(s, p, o);
    }

    public static AticTriple create(Node s, Node p, Node o, double confidence) {
        return new AticTriple(s, p, o, confidence);
    }

    public static AticTriple create(Node s, Node p, Node o, double confidence, double applicability) {
        return new AticTriple(s, p, o, confidence, applicability);
    }

    public static AticTriple createWithConfidence(Triple t, double confidence) {
        return new AticTriple(t.getSubject(), t.getPredicate(), t.getObject(), confidence);
    }

    public static AticTriple createWithConfidenceAndApplicability(Triple t, double confidence, double applicability) {
        return new AticTriple(t.getSubject(), t.getPredicate(), t.getObject(), confidence, applicability);
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
}
