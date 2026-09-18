

package de.dfki.sds.aticsqlite.s16n;

/**
 *
 */
public enum Operation {
    /**
     * Fully sets the column's content.
     */
    Set,
    
    /**
     * Only adds content to column's content.
     */
    Add,
    
    /**
     * Only removes content from colum's content.
     */
    Remove
}
