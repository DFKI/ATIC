package de.dfki.sds.atic.ac;

import de.dfki.sds.atic.jenatic.InvocationContext;
import org.json.JSONObject;

/**
 *
 */
public interface UserGroupManagement {

    public static final String ADMIN_USERNAME = "admin";
    public static final String EVERYONE_GROUP = "everyone";

    public String addUser(String firstname, String lastname, String email, String username, InvocationContext ctx);

    public void addGroup(String groupname, InvocationContext ctx);

    public User getUser(String username, InvocationContext ctx);

    public User getUser(int userId, InvocationContext ctx);

    public Group getGroup(String groupname, InvocationContext ctx);

    public void assignUserToGroup(String username, String groupname, InvocationContext ctx);

    public void unassignUserFromGroup(String username, String groupname, InvocationContext ctx);

    public void enableAgent(String username, String factory, JSONObject config, InvocationContext ctx);

    public void disableAgent (String username, InvocationContext ctx);

}
