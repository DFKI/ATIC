

package de.dfki.sds.aticsqlite;

import org.apache.jena.rdfpatch.RDFPatch;

/**
 *
 */
public interface RDFPatchListener {
    
    void handlePatch(RDFPatch patch);
    
}
