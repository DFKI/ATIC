

package de.dfki.sds.aticsqlite.agent;

import de.dfki.sds.atic.ac.Agent;
import de.dfki.sds.atic.agent.AgentProgram;
import de.dfki.sds.atic.agent.Message;
import de.dfki.sds.atic.agent.Session;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
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
        
        AiServiceStartedListener startedListener =
                event -> session.getLogger().info(event.toString());

        AiServiceRequestIssuedListener requestIssuedListener =
                event -> session.getLogger().info(event.toString());

        AiServiceResponseReceivedListener responseReceivedListener =
                event -> session.getLogger().info(event.toString());

        AiServiceErrorListener errorListener =
                event -> session.getLogger().warning(event.toString());

        AiServiceCompletedListener completedListener =
                event -> session.getLogger().info(event.toString());

        ToolExecutedEventListener toolExecutedListener =
                event -> session.getLogger().info(event.toString());
        
        
        AticDatasetGraphTools aticDatasetGraphTools = new AticDatasetGraphTools(dataset, ictx);
        
        aticAgentViaLangChain = AiServices
                .builder(AticAgentViaLangChain.class)
                .chatModel(model)
                .registerListener(startedListener)
                .registerListener(requestIssuedListener)
                .registerListener(responseReceivedListener)
                .registerListener(errorListener)
                .registerListener(completedListener)
                .registerListener(toolExecutedListener)
                .tools(aticDatasetGraphTools)
                .build();
    }
    
    //de.dfki.sds.aticsqlite.agent.AticAgentProgram.create
    public static AgentProgram create(Agent agent, String config, Session session, SqliteAticDatasetGraph dataset) {
        JSONObject configObj = new JSONObject(config);
        return new AticAgentProgram(agent, configObj, session, dataset);
    }

    @Override
    public void process(Message message) {
        
        
        String answer = aticAgentViaLangChain.chat(message.content());
        
        session.append(Message.plainText(agent, answer));
        
    }
    
}
