package de.dfki.sds.atic.ac;

import java.util.*;

public class User implements Principal {

    private final int id;
    private final String uri;
    private final String username;

    private final Group primaryGroup;
    private final List<Group> groups;

    private final String firstname;
    private final String lastname;
    private final String email;
    private final String hashedPassword;

    public User(
            int id,
            String uri,
            String username,
            Group primaryGroup,
            List<Group> groups,
            String firstname,
            String lastname,
            String email,
            String hashedPassword) {

        this.id = id;
        this.uri = uri;
        this.username = username;
        this.primaryGroup = primaryGroup;
        this.groups = groups == null ? List.of() : List.copyOf(groups);

        this.firstname = firstname;
        this.lastname = lastname;
        this.email = email;
        this.hashedPassword = hashedPassword;
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
        return primaryGroup.getUri();
    }

    public String getUsername() {
        return username;
    }
    
    @Override
    public String getName() {
        return getUsername();
    }

    public Group getPrimaryGroup() {
        return primaryGroup;
    }
    
    public String getPrimaryGroupUri() {
        return getPrimaryGroup().getUri();
    }
    
    public int getPrimaryGroupId() {
        return getPrimaryGroup().getId();
    }
    
    public String getPrimaryGroupName() {
        return getPrimaryGroup().getGroupname();
    }

    public List<Group> getGroups() {
        return groups;
    }
    
    public Set<Integer> getGroupIds() {
        Set<Integer> groupIds = new HashSet<>();
        for(Group g : getGroups()) {
            groupIds.add(g.getId());
        }
        groupIds.add(getPrimaryGroup().getId());
        return groupIds;
    }

    public String getFirstname() {
        return firstname;
    }

    public String getLastname() {
        return lastname;
    }

    public String getEmail() {
        return email;
    }

    public String getHashedPassword() {
        return hashedPassword;
    }

    @Override
    public Map<String, Object> toMap() {

        Map<String, Object> map = new HashMap<>();

        map.put("type", "user");
        
        map.put("id", id);
        map.put("uri", uri);
        map.put("username", username);

        map.put("primaryGroupId", primaryGroup.getId());
        map.put("primaryGroupName", primaryGroup.getGroupname());
        map.put("primaryGroupUri", primaryGroup.getUri());
        map.put("shareUri", getShareUri());

        List<Map<String, Object>> groupMaps = new ArrayList<>();
        for (Group g : groups) {
            groupMaps.add(Map.of(
                    "id", g.getId(),
                    "uri", g.getUri(),
                    "groupname", g.getGroupname()
            ));
        }

        map.put("groups", groupMaps);

        map.put("firstname", firstname);
        map.put("lastname", lastname);
        map.put("email", email);
        map.put("label", firstname + " " + lastname);

        return map;
    }

    
}
