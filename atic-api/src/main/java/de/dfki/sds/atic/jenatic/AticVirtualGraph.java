

package de.dfki.sds.atic.jenatic;

import java.util.List;
import java.util.Map;

/**
 *
 */
public interface AticVirtualGraph extends AticGraph {

    public AticVirtualGraphResponse handleRequest(String method, String path, Map<String, List<String>> queryParamMap, InvocationContext ctx);
    
}
