

package de.dfki.sds.aticsqlite.agent;

import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.agent.Job;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import org.json.JSONObject;
import de.dfki.sds.atic.agent.AgentProgram;

/**
 *
 */
public class DummyAgent implements AgentProgram {
    
    private User user;
    private JSONObject config;
    private SqliteAticDatasetGraph parent;
    private InvocationContext ictx;

    public DummyAgent(User user, JSONObject config, SqliteAticDatasetGraph parent) {
        this.user = user;
        this.config = config;
        this.parent = parent;
        this.ictx = new InvocationContext.Builder().fromUser(user).build();
    }
    
    //de.dfki.sds.aticsqlite.agent.create
    public static AgentProgram create(User user, String config, SqliteAticDatasetGraph parent) {
        JSONObject configObj = new JSONObject(config);
        return new DummyAgent(user, configObj, parent);
    }

    @Override
    public void process(Job job) {
        System.out.println(job);
    }
    
}
