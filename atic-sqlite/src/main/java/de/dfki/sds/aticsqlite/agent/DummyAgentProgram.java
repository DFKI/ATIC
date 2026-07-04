

package de.dfki.sds.aticsqlite.agent;

import de.dfki.sds.atic.ac.Agent;
import de.dfki.sds.atic.agent.AgentProgram;
import de.dfki.sds.atic.agent.Message;
import de.dfki.sds.atic.agent.Session;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import org.json.JSONObject;

/**
 *
 */
public class DummyAgentProgram implements AgentProgram {
    
    private Agent agent;
    private JSONObject config;
    private SqliteAticDatasetGraph parent;
    private InvocationContext ictx;
    private Session session;

    public DummyAgentProgram(Agent agent, JSONObject config, Session session, SqliteAticDatasetGraph parent) {
        this.agent = agent;
        this.config = config;
        this.parent = parent;
        this.ictx = new InvocationContext.Builder().fromUser(agent).build();
        this.session = session;
    }
    
    //de.dfki.sds.aticsqlite.agent.DummyAgentProgram.create
    public static AgentProgram create(Agent agent, String config, Session session, SqliteAticDatasetGraph parent) {
        JSONObject configObj = new JSONObject(config);
        return new DummyAgentProgram(agent, configObj, session, parent);
    }

    @Override
    public void process(Message message) {
        //System.out.println(message);
        
        //for technical logging
        session.getLogger().info(message.toString());
        
        session.append(Message.plainText(agent, "I received " + message.toString()));
    }
    
}
