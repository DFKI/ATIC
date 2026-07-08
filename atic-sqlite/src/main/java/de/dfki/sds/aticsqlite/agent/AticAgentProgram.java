package de.dfki.sds.aticsqlite.agent;

import de.dfki.sds.atic.ac.Agent;
import de.dfki.sds.atic.agent.AgentProgram;
import de.dfki.sds.atic.agent.Message;
import de.dfki.sds.atic.agent.Session;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.observability.api.event.AiServiceEvent;
import dev.langchain4j.observability.api.event.ToolExecutedEvent;
import dev.langchain4j.observability.api.listener.AiServiceCompletedListener;
import dev.langchain4j.observability.api.listener.AiServiceErrorListener;
import dev.langchain4j.observability.api.listener.AiServiceRequestIssuedListener;
import dev.langchain4j.observability.api.listener.AiServiceResponseReceivedListener;
import dev.langchain4j.observability.api.listener.AiServiceStartedListener;
import dev.langchain4j.observability.api.listener.ToolExecutedEventListener;
import dev.langchain4j.service.AiServices;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;

/**
 *
 */
public class AticAgentProgram implements AgentProgram {

    private Agent agent;
    private JSONObject config;
    private Session session;
    private SqliteAticDatasetGraph dataset;
    private InvocationContext ictx;

    private AticAgentViaLangChain aticAgentViaLangChain;
    
    private AticDatasetGraphTools aticDatasetGraphTools;
    
    private MyToolExecutedEventListener myToolExecutedEventListener;

    public AticAgentProgram(Agent agent, JSONObject config, Session session, SqliteAticDatasetGraph dataset) {
        this.agent = agent;
        this.config = config;
        this.session = session;
        this.dataset = dataset;
        this.ictx = new InvocationContext.Builder().fromUser(agent).build();

        try {
            initLangChain();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void initLangChain() throws IOException {

        //TODO later read from config env var and get key from env var
        Path tokenFile = Path.of(
                System.getProperty("user.home"),
                "tmpToken",
                "openrouter.txt"
        );
        String apiKey = Files.readString(tokenFile).trim();

        ChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://openrouter.ai/api/v1")
                .apiKey(apiKey)
                .modelName("openai/gpt-4.1")
                .build();

        //https://docs.langchain4j.dev/tutorials/observability/
        AiServiceStartedListener startedListener
                = event -> session.getLogger().info(toString(event));

        AiServiceRequestIssuedListener requestIssuedListener
                = event -> session.getLogger().info(toString(event));

        AiServiceResponseReceivedListener responseReceivedListener
                = event -> session.getLogger().info(toString(event));

        AiServiceErrorListener errorListener
                = event -> session.getLogger().warning(toString(event));

        AiServiceCompletedListener completedListener
                = event -> session.getLogger().info(toString(event));

        ToolExecutedEventListener toolExecutedListener
                = event -> session.getLogger().info(toString(event));

        aticDatasetGraphTools = new AticDatasetGraphTools(dataset, ictx);
        
        myToolExecutedEventListener = new MyToolExecutedEventListener();

        aticAgentViaLangChain = AiServices
                .builder(AticAgentViaLangChain.class)
                .chatModel(model)
                .registerListener(startedListener)
                .registerListener(requestIssuedListener)
                .registerListener(responseReceivedListener)
                .registerListener(errorListener)
                .registerListener(completedListener)
                .registerListener(toolExecutedListener)
                .registerListener(myToolExecutedEventListener)
                .tools(aticDatasetGraphTools)
                .build();
    }

    //de.dfki.sds.aticsqlite.agent.AticAgentProgram.create
    public static AgentProgram create(Agent agent, String config, Session session, SqliteAticDatasetGraph dataset) {
        JSONObject configObj = new JSONObject(config);
        return new AticAgentProgram(agent, configObj, session, dataset);
    }

    
    private class MyToolExecutedEventListener implements ToolExecutedEventListener {

        public void reset() {
            
        }
        
        @Override
        public void onEvent(ToolExecutedEvent event) {
            event.request().name();
            
            int a = 0;
        }
        
    }
    
    @Override
    public void process(Message message) {
        
        aticDatasetGraphTools.reset();
        myToolExecutedEventListener.reset();

        String answer = aticAgentViaLangChain.chat(message.content());

        session.append(Message.plainText(agent, answer));

    }

    private String toString(AiServiceEvent event) {
        try {
            //return ReflectionToStringBuilder.toString(event, new RecursiveToStringStyle(), false, false, true, AiServiceEvent.class);
            return AgentUtils.toString(event);
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
