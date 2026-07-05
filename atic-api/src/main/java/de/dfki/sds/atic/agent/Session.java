package de.dfki.sds.atic.agent;

import de.dfki.sds.atic.ac.Agent;
import de.dfki.sds.atic.ac.User;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
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

    private MessageWorker worker;

    private final Logger logger;
    private final InMemoryLogHandler logHandler;

    private String title;

    private final List<Message> messages;

    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();

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
        this.logHandler.addListener((LogRecord record) -> {
            notifyLog(record);
        });

        this.messages = new ArrayList<>();

        logger.setUseParentHandlers(false);
        logger.addHandler(logHandler);

        this.title = sessionId; //.substring(0, Math.min(6, sessionId.length()));
    }

    public void submit(Message message) {
        append(message);
        
        getWorker().submit(message);
        notifyMessageSubmitted(message);
    }

    public void append(Message message) {
        this.messages.add(message);
        notifyMessage(message);
    }

    //==================================
    public void addListener(SessionListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SessionListener listener) {
        listeners.remove(listener);
    }

    public void notifySessionCreated() {

        notifyListeners(listener
                -> listener.onSessionCreated(this));
    }

    public void notifyMessageSubmitted(Message message) {

        notifyListeners(listener
                -> listener.onMessageSubmitted(
                        this,
                        message
                ));
    }

    public void notifyMessage(Message message) {

        notifyListeners(listener
                -> listener.onMessage(
                        this,
                        message
                ));
    }

    public void notifyLog(LogRecord record) {

        notifyListeners(listener
                -> listener.onLog(
                        this,
                        record
                ));
    }

    public void notifyClosed() {

        notifyListeners(listener
                -> listener.onClosed(this));
    }

    public void notifyMessageProcessingStarted(Message message) {
        notifyListeners(listener
                -> listener.onMessageProcessingStarted(
                        this,
                        message
                ));
    }

    public void notifyMessageProcessingFinished(Message message) {
        notifyListeners(listener
                -> listener.onMessageProcessingFinished(
                        this,
                        message
                ));
    }

    public void notifyError(Throwable error) {
        notifyListeners(listener
                -> listener.onError(
                        this,
                        error
                ));
    }

    private void notifyListeners(Consumer<SessionListener> action) {

        for (SessionListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
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
