

package de.dfki.sds.aticsqlite.agent;

import de.dfki.sds.atic.ac.Agent;
import de.dfki.sds.atic.agent.AgentProgram;
import de.dfki.sds.atic.agent.Message;
import de.dfki.sds.atic.agent.Session;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import org.json.JSONObject;

/**
 * For testing.
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
        
        //for technical logging
        session.getLogger().info("start with: " + message.toString());
        
        if(message.content().toLowerCase().contains("error")) {
            throw new RuntimeException("Here we simulate an exception");
        }
        
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ex) {
            //ignore
        }
        
        session.getLogger().info("done with: " + message.toString());
        
        session.append(Message.plainText(agent, "I processed " + message.toString()));
    }
    
}
