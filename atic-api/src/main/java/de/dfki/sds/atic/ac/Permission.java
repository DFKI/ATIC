

package de.dfki.sds.atic.ac;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;

/**
 *
 */
public enum Permission {

    //NONE(0), //use unshare so delete the entry
    READ (1, "urn:atic:permission-READ"),
    REFER (2, "urn:atic:permission-REFER"),
    EDIT (3, "urn:atic:permission-EDIT"),
    ADMIN(4, "urn:atic:permission-ADMIN");

    private final int code;
    private final String uri;
    private final Node node;

    Permission(int code, String uri) {
        this.code = code;
        this.uri = uri;
        this.node = NodeFactory.createURI(uri);
    }

    /** The numeric storage code (1=read, 2=edit, 3=admin) */
    public int getCode() {
        return code;
    }

    public String getUri() {
        return uri;
    }

    public Node asNode() {
        return node;
    }
    
    /**
     * Lookup by numeric code.
     *
     * @param code the integer code stored in the database
     * @return corresponding GraphPermission or null
     */
    public static Permission fromCode(int code) {
        for (Permission perm : values()) {
            if (perm.code == code) {
                return perm;
            }
        }
        return null;
    }
    
    //used in RDFPatchEmitterTransactional to mark that any permission is deleted
    public final static Node ANY = NodeFactory.createURI("urn:atic:permission");
    
    
}
