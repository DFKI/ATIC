package de.dfki.sds.atic.agent;

import de.dfki.sds.atic.ac.Agent;
import de.dfki.sds.atic.ac.User;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 *
 */
public class Session {

    private final String sessionId;
    private final User principal;
    private final Agent agent;
    private volatile Instant expiresAt;
    private int creatorUserId;
    
    private MessageWorker worker;
    
    private final Logger logger;
    private final InMemoryLogHandler logHandler;
    
    private String title;
    
    private final List<Message> messages;

    public Session(
            User principal,
            String sessionId,
            Agent agent,
            Instant expiresAt) {
        this.principal = principal;
        this.sessionId = sessionId;
        this.agent = agent;
        this.expiresAt = expiresAt;
        
         this.logger = Logger.getLogger("session." + sessionId + "." + agent.getUsername());

        this.logHandler = new InMemoryLogHandler();

        this.messages = new ArrayList<>();
        
        logger.setUseParentHandlers(false);
        logger.addHandler(logHandler);
    }
    
    public void submit(Message message) {
        append(message);
        getWorker().submit(message);
    }
    
    public void append(Message message) {
        this.messages.add(message);
    }
    
    //=================================
    
    public Logger getLogger() {
        return logger;
    }

    public List<LogRecord> getLogRecords() {
        return logHandler.getRecords();
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public MessageWorker getWorker() {
        return worker;
    }

    public void setWorker(MessageWorker worker) {
        this.worker = worker;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Agent getAgent() {
        return agent;
    }

    public User getPrincipal() {
        return principal;
    }

    public List<Message> getMessages() {
        return List.copyOf(messages);
    }
    
    
    
}
