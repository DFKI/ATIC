package de.dfki.sds.aticsqlite.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 *
 */
public class Trial {

    public static void main(String[] args) throws Exception {
        //agentJobWorkerTest();
    }

    private static void chatModelTest() throws IOException {
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

        String resp = model.chat("hey");
        System.out.println(resp);
    }

    private static void firstTest() throws IOException {
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

        TestInterface assistant = AiServices
                .builder(TestInterface.class)
                .chatModel(model)
                .build();

        String resp = assistant.chat("write a sentence about fans (wind).");

        System.out.println(resp);
    }
    
    /*
    private static void agentJobWorkerTest() throws Exception {
        AgentProgram agent = job -> {
            if (job instanceof MessageWithAttachmentsJob messageJob) {
                System.out.println("Received: " + messageJob.getMessage());
            }
        };

        try (JobWorker worker = new JobWorker(agent)) {

            worker.submit(MessageWithAttachmentsJob.builder(null, "Hello").build());
            worker.submit(MessageWithAttachmentsJob.builder(null, "World").build());

            Thread.sleep(1000);
        }
    }
    */
}
