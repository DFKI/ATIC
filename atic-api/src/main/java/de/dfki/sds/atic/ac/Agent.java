package de.dfki.sds.atic.ac;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class Agent extends User {

    private final String factory;
    private final String config;

    public Agent(int id, String uri, String username, Group primaryGroup, List<Group> groups, String firstname, String lastname, String email, String hashedPassword, String factory, String config) {
        super(id, uri, username, primaryGroup, groups, firstname, lastname, email, hashedPassword);
        this.factory = factory;
        this.config = config;
    }

    public String getFactory() {
        return factory;
    }

    public String getConfig() {
        return config;
    }

    @Override
    public boolean isAgent() {
        return true;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>(super.toMap());
        map.put("type", "agent");
        map.put("factory", factory);
        map.put("config", config);
        return map;
    }
}
