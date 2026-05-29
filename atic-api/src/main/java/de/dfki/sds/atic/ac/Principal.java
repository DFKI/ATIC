

package de.dfki.sds.atic.ac;

import java.util.Map;

/**
 *
 */
public interface Principal {

    public int getId();
    
    public String getUri();
    
    public String getShareUri();
    
    public String getName();
    
    //TODO later maybe use RDF here too
    public Map<String, Object> toMap();
    
}
