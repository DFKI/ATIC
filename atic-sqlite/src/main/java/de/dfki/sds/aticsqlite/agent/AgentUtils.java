package de.dfki.sds.aticsqlite.agent;

import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.event.AiServiceErrorEvent;
import dev.langchain4j.observability.api.event.AiServiceEvent;
import dev.langchain4j.observability.api.event.AiServiceRequestIssuedEvent;
import dev.langchain4j.observability.api.event.AiServiceResponseReceivedEvent;
import dev.langchain4j.observability.api.event.AiServiceStartedEvent;
import dev.langchain4j.observability.api.event.ToolExecutedEvent;

/**
 *
 */
public class AgentUtils {
    
    private static int LIMIT = 1500;

    public static String toString(AiServiceEvent event) {

        if (event instanceof AiServiceStartedEvent e) {
            return started(e);
        }

        if (event instanceof AiServiceRequestIssuedEvent e) {
            return request(e);
        }

        if (event instanceof AiServiceResponseReceivedEvent e) {
            return response(e);
        }

        if (event instanceof AiServiceErrorEvent e) {
            return error(e);
        }

        if (event instanceof AiServiceCompletedEvent e) {
            return completed(e);
        }

        if (event instanceof ToolExecutedEvent e) {
            return tool(e);
        }

        return "UnknownAiServiceEvent{"
                + "type=" + event.getClass().getSimpleName()
                + ", ctx=" + safeCtx(event)
                + "}";
    }

    // ---------------------------------------------
    // AiServiceStartedEvent
    // ---------------------------------------------
    private static String started(AiServiceStartedEvent e) {
        return "AiServiceStarted{"
                + ctx(e)
                + "}";
    }

    // ---------------------------------------------
    // AiServiceRequestIssuedEvent
    // ---------------------------------------------
    private static String request(AiServiceRequestIssuedEvent e) {
        return "AiServiceRequest{"
                + ctx(e)
                + ", request=" + safe(e.request())
                + "}";
    }

    // ---------------------------------------------
    // AiServiceResponseReceivedEvent
    // ---------------------------------------------
    private static String response(AiServiceResponseReceivedEvent e) {
        return "AiServiceResponse{"
                + ctx(e)
                + ", response=" + safe(e.response())
                + "}";
    }

    // ---------------------------------------------
    // AiServiceErrorEvent
    // ---------------------------------------------
    private static String error(AiServiceErrorEvent e) {
        return "AiServiceError{"
                + ctx(e)
                + ", error=" + safe(e.error())
                + "}";
    }

    // ---------------------------------------------
    // AiServiceCompletedEvent
    // ---------------------------------------------
    private static String completed(AiServiceCompletedEvent e) {
        return "AiServiceCompleted{"
                + ctx(e)
                + ", result=" + safe(e.result())
                + "}";
    }

    // ---------------------------------------------
    // ToolExecutedEvent
    // ---------------------------------------------
    private static String tool(ToolExecutedEvent e) {
        return "ToolExecuted{"
                + ctx(e)
                + ", tool=" + safe(e.request().name())
                + ", arguments=" + safe(e.request().arguments())
                + ", result=" + safe(e.resultText())
                + "}";
    }

    // ---------------------------------------------
    // helpers
    // ---------------------------------------------
    private static String ctx(AiServiceEvent e) {
        var c = e.invocationContext();
        return "invocationId=" + c.invocationId()
                + ", method=" + c.methodName()
                + ", interface=" + c.interfaceName();
    }

    private static String safeCtx(AiServiceEvent e) {
        try {
            return ctx(e);
        } catch (Exception ex) {
            return "<ctx-error>";
        }
    }

    private static String safe(Object obj) {
        if (obj == null) {
            return "null";
        }

        // IMPORTANT: avoid huge graphs
        String s = obj.toString();

        if (s.length() > LIMIT) {
            return s.substring(0, LIMIT) + "...<truncated>";
        }

        return s;
    }
}
