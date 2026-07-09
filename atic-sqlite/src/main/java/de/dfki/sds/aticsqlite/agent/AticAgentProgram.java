package de.dfki.sds.aticsqlite.agent;

import de.dfki.sds.atic.ac.Agent;
import de.dfki.sds.atic.agent.AgentProgram;
import de.dfki.sds.atic.agent.Attachment;
import de.dfki.sds.atic.agent.Message;
import de.dfki.sds.atic.agent.Session;
import de.dfki.sds.atic.agent.ToolCallAttachment;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
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
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private List<ToolCallAttachment> toolCallAttachments = new ArrayList<>();

    private static final String systemMessage = """
You are a helpful Resource Description Framework (RDF) assistant.

""" + AticDatasetGraphTools.SKILL_TEXT;

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
                .modelName("openai/gpt-4o-mini")
                //.modelName("openai/gpt-4.1")
                //.modelName("openai/gpt-5.5-pro")
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

        ToolExecutionErrorHandler toolExecutionErrorHandler = (Throwable thrwbl, ToolErrorContext tec) -> {
            return handleError(thrwbl, tec, "execution");
        };

        ToolArgumentsErrorHandler toolArgumentsErrorHandler = (Throwable thrwbl, ToolErrorContext tec) -> {
            return handleError(thrwbl, tec, "arguments");
        };

        aticDatasetGraphTools = new AticDatasetGraphTools(dataset, ictx);

        myToolExecutedEventListener = new MyToolExecutedEventListener();

        aticAgentViaLangChain = AiServices
                .builder(AticAgentViaLangChain.class)
                .chatModel(model)
                .systemMessage(systemMessage)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .registerListener(startedListener)
                .registerListener(requestIssuedListener)
                .registerListener(responseReceivedListener)
                .registerListener(errorListener)
                .registerListener(completedListener)
                .registerListener(toolExecutedListener)
                .registerListener(myToolExecutedEventListener)
                .toolExecutionErrorHandler(toolExecutionErrorHandler)
                .toolArgumentsErrorHandler(toolArgumentsErrorHandler)
                .tools(aticDatasetGraphTools)
                .build();
    }

    //de.dfki.sds.aticsqlite.agent.AticAgentProgram.create
    public static AgentProgram create(Agent agent, String config, Session session, SqliteAticDatasetGraph dataset) {
        JSONObject configObj = new JSONObject(config);
        return new AticAgentProgram(agent, configObj, session, dataset);
    }

    //we collect all tool errors as attachments
    private ToolErrorHandlerResult handleError(Throwable thrwbl, ToolErrorContext tec, String mode) {
        //handleError is called first on exception
        ToolCallAttachment toolCallAttachment = new ToolCallAttachment(
                tec.toolExecutionRequest().name(),
                tec.toolExecutionRequest().arguments(), //this is a json object as string
                null,
                thrwbl
        );
        toolCallAttachments.add(toolCallAttachment);
        return ToolErrorHandlerResult.text("An error occured:\n" + thrwbl.getClass().toString() + ": " + thrwbl.getMessage());
    }

    //we collect all tool executions as attachments
    private class MyToolExecutedEventListener implements ToolExecutedEventListener {

        @Override
        public void onEvent(ToolExecutedEvent event) {
            //onEvent is called second on exception
            ToolCallAttachment toolCallAttachment = new ToolCallAttachment(
                    event.request().name(),
                    event.request().arguments(), //this is a json object as string
                    event.resultText(),
                    null
            );
            toolCallAttachments.add(toolCallAttachment);
        }
    }

    private void merge(List<ToolCallAttachment> attachments) {

        Map<MergeKey, ToolCallAttachment> merged = new LinkedHashMap<>();

        for (ToolCallAttachment attachment : attachments) {

            MergeKey key = new MergeKey(
                    attachment.name(),
                    attachment.arguments()
            );

            ToolCallAttachment existing = merged.get(key);

            if (existing == null) {

                merged.put(key, attachment);

            } else {

                merged.put(key, new ToolCallAttachment(
                        attachment.name(),
                        attachment.arguments(),
                        existing.result() != null
                        ? existing.result()
                        : attachment.result(),
                        existing.throwable() != null
                        ? existing.throwable()
                        : attachment.throwable()
                ));
            }
        }

        attachments.clear();
        attachments.addAll(merged.values());
    }

    private record MergeKey(
            String name,
            String arguments
            ) {

    }

    @Override
    public void process(Message message) {

        aticDatasetGraphTools.reset();
        toolCallAttachments.clear();

        StringBuilder messageSB = new StringBuilder();
        messageSB.append(message.content());
        messageSB.append("\n");
        messageSB.append("\n");
        if (!message.attachments().isEmpty()) {
            messageSB.append("Attachments:\n");
            for (Attachment attachment : message.attachments()) {
                messageSB.append(" * ").append(attachment.toString()).append("\n");
            }
        }

        String answer = aticAgentViaLangChain.chat(messageSB.toString());

        Message.Builder responseMessageBuilder = Message
                .builder(agent, answer, Message.TEXT_PLAIN);

        //merge tool execution and potential error in one attachment
        merge(toolCallAttachments);

        //attach tool calls
        for (Attachment attachment : toolCallAttachments) {
            responseMessageBuilder.attachment(attachment);
        }
        
        //attach what quads the agent saw
        if(aticDatasetGraphTools.hasFoundQuads()) {
            responseMessageBuilder.attachment(aticDatasetGraphTools.getRdfDatasetAttachment());
        }
        
        if(aticDatasetGraphTools.hasRDFPatch()) {
            responseMessageBuilder.attachment(aticDatasetGraphTools.getRdfPatchAttachment());
        }

        session.append(responseMessageBuilder.build());
    }

    private String toString(AiServiceEvent event) {
        try {
            return AgentUtils.toString(event);
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
