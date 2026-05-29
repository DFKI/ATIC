

package de.dfki.sds.atic.api;

import org.apache.jena.graph.Node;

/**
 *
 */
public class IdAndUri {
    
    private long id;
    
    private String uri;

    public IdAndUri(long id, String uri) {
        this.id = id;
        this.uri = uri;
    }

    public long getId() {
        return id;
    }

    public String getUri() {
        return uri;
    }
    
    public static IdAndUri create(long id, String uri) {
        return new IdAndUri(id, uri);
    }
    
    public static IdAndUri create(long id, Node node) {
        return new IdAndUri(id, node.isBlank() ? node.getBlankNodeLabel() : node.getURI());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("IdAndUri{");
        sb.append("id=").append(id);
        sb.append(", uri=").append(uri);
        sb.append('}');
        return sb.toString();
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 73 * hash + (int) (this.id ^ (this.id >>> 32));
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final IdAndUri other = (IdAndUri) obj;
        return this.id == other.id;
    }
    

}
