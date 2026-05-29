

package de.dfki.sds.atic.ac;

import java.util.HashMap;
import java.util.Map;

/**
 *
 */
public class Group implements Principal {

    private final int id;
    private final String uri;
    private final String groupname;

    public Group(int id, String uri, String groupname) {
        this.id = id;
        this.uri = uri;
        this.groupname = groupname;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public String getUri() {
        return uri;
    }
    
    @Override
    public String getShareUri() {
        return uri;
    }

    public String getGroupname() {
        return groupname;
    }

    @Override
    public String getName() {
        return getGroupname();
    }
    
    @Override
    public Map<String, Object> toMap() {

        Map<String, Object> map = new HashMap<>();

        map.put("type", "group");
        
        map.put("id", id);
        map.put("uri", uri);
        map.put("groupname", groupname);
        map.put("label", groupname);
        map.put("shareUri", getShareUri());

        return map;
    }

    
    
}
