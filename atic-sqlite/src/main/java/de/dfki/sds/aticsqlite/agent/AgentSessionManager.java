package de.dfki.sds.aticsqlite.agent;

import de.dfki.sds.atic.ac.Agent;
import de.dfki.sds.atic.ac.User;
import de.dfki.sds.atic.agent.AgentProgram;
import de.dfki.sds.atic.agent.Message;
import de.dfki.sds.atic.agent.MessageWorker;
import de.dfki.sds.atic.agent.Session;
import de.dfki.sds.atic.agent.SessionListener;
import de.dfki.sds.atic.jenatic.InvocationContext;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.LogRecord;
import org.json.JSONObject;

public class AgentSessionManager implements AutoCloseable, SessionListener {

    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();

    private record SessionKey(
            String sessionId,
            String agentUsername,
            int userId
            ) {

    }

    public static final Duration DEFAULT_EXPIRATION = Duration.ofDays(3);

    private final Map<SessionKey, Session> sessions = new ConcurrentHashMap<>();
    private final Duration expirationDuration;

    public AgentSessionManager() {
        this(DEFAULT_EXPIRATION);
    }

    public AgentSessionManager(Duration expirationDuration) {
        this.expirationDuration = expirationDuration;
    }

    //===========================================
    
    public void addListener(SessionListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SessionListener listener) {
        listeners.remove(listener);
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

    private void notifySessionCreated(Session session) {
        notifyListeners(listener
                -> listener.onSessionCreated(session));
    }

    private void notifyMessageSubmitted(
            Session session,
            Message message) {

        notifyListeners(listener
                -> listener.onMessageSubmitted(
                        session,
                        message
                ));
    }

    private void notifyMessageProcessingStarted(
            Session session,
            Message message) {

        notifyListeners(listener
                -> listener.onMessageProcessingStarted(
                        session,
                        message
                ));
    }

    private void notifyMessageProcessingFinished(
            Session session,
            Message message) {

        notifyListeners(listener
                -> listener.onMessageProcessingFinished(
                        session,
                        message
                ));
    }

    private void notifyMessage(
            Session session,
            Message message) {

        notifyListeners(listener
                -> listener.onMessage(
                        session,
                        message
                ));
    }

    private void notifyLog(
            Session session,
            LogRecord record) {

        notifyListeners(listener
                -> listener.onLog(
                        session,
                        record
                ));
    }

    private void notifyError(Session session, Throwable error) {
        notifyListeners(listener
                -> listener.onError(
                        session,
                        error
                ));
    }

    void notifyClosed(Session session) {
        notifyListeners(listener
                -> listener.onClosed(session));
    }

    @Override
    public void onMessage(
            Session session,
            Message message) {

        notifyMessage(session, message);
    }

    @Override
    public void onSessionCreated(Session session) {
        notifySessionCreated(session);
    }

    @Override
    public void onMessageSubmitted(
            Session session,
            Message message) {

        notifyMessageSubmitted(
                session,
                message
        );
    }

    @Override
    public void onMessageProcessingStarted(
            Session session,
            Message message) {

        notifyMessageProcessingStarted(
                session,
                message
        );
    }

    @Override
    public void onMessageProcessingFinished(
            Session session,
            Message message) {

        notifyMessageProcessingFinished(
                session,
                message
        );
    }

    @Override
    public void onLog(
            Session session,
            LogRecord record) {

        notifyLog(
                session,
                record
        );
    }

    @Override
    public void onError(
            Session session,
            Throwable error) {

        notifyError(
                session,
                error
        );
    }

    @Override
    public void onClosed(Session session) {
        notifyClosed(session);
    }

    //=============================================
    public Session addSession(User principal, String sessionId, Agent agent, SqliteAticDatasetGraph parent, InvocationContext ctx) {

        removeExpiredSessions();

        SessionKey key = new SessionKey(
                sessionId,
                agent.getUsername(),
                ctx.getUserId()
        );

        if (containsSession(sessionId, agent.getUsername(), ctx)) {
            throw new IllegalStateException(
                    "Session already exists for sessionId="
                    + sessionId
                    + ", agent="
                    + agent.getUsername()
            );
        }

        Instant expiresAt = Instant.now().plus(expirationDuration);

        Session session = new Session(
                principal,
                sessionId,
                agent,
                expiresAt
        );
        session.addListener(this);

        AgentProgram agentProgram = loadAgent(agent, session, parent);

        MessageWorker worker = new MessageWorker(agentProgram, session);

        session.setWorker(worker);

        sessions.put(key, session);

        worker.start();
        
        session.notifySessionCreated();

        return session;
    }

    public Session getSession(String sessionId, String agentUsername, InvocationContext ctx) {

        removeExpiredSessions();

        Session session = sessions.get(
                new SessionKey(sessionId, agentUsername, ctx.getUserId())
        );

        if (session != null) {
            session.setExpiresAt(
                    Instant.now().plus(expirationDuration)
            );
        }

        return session;
    }

    public boolean containsSession(String sessionId, String agentUsername, InvocationContext ctx) {

        removeExpiredSessions();

        return sessions.containsKey(
                new SessionKey(sessionId, agentUsername, ctx.getUserId())
        );
    }

    public void removeSession(String sessionId, String agentUsername, InvocationContext ctx) {

        Session removed = sessions.remove(
                new SessionKey(sessionId, agentUsername, ctx.getUserId())
        );

        if (removed != null) {
            removed.getWorker().close();
            removed.notifyClosed();
        }
    }

    public List<Session> listSessions(InvocationContext ctx) {

        removeExpiredSessions();

        return sessions.values()
                .stream()
                .filter(session -> session.getPrincipal().getId() == ctx.getUserId())
                .sorted(Comparator.comparing(Session::getExpiresAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public Session getOrAddSession(User principal, String sessionId, Agent agent, SqliteAticDatasetGraph parent, InvocationContext ctx) {
        Session session;
        if (this.containsSession(sessionId, agent.getUsername(), ctx)) {
            session = this.getSession(sessionId, agent.getUsername(), ctx);
        } else {
            session = this.addSession(principal, sessionId, agent, parent, ctx);
        }
        return session;
    }

    //=============================================
    public void closeSessionsForAgent(String agentUsername) {

        sessions.entrySet().removeIf(entry -> {

            SessionKey key = entry.getKey();

            if (key.agentUsername().equals(agentUsername)) {

                try {
                    entry.getValue().getWorker().close();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                return true;
            }

            return false;
        });
    }

    private void removeExpiredSessions() {

        Instant now = Instant.now();

        sessions.entrySet().removeIf(entry -> {

            Session session = entry.getValue();

            if (session.getExpiresAt().isBefore(now)) {
                session.getWorker().close();
                return true;
            }

            return false;
        });
    }

    @Override
    public void close() {

        sessions.values().forEach(session -> {
            try {
                session.getWorker().close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        sessions.clear();
    }

    private AgentProgram loadAgent(Agent agent, Session session, SqliteAticDatasetGraph parent) {

        String factoryMethodPath = agent.getFactory();
        JSONObject config = new JSONObject(agent.getConfig());

        Method factoryMethod;
        try {
            // ------------------------------------------------
            // resolve factory method
            // ------------------------------------------------
            int sep = factoryMethodPath.lastIndexOf('.');
            if (sep < 0) {
                throw new IllegalArgumentException("Invalid factory method path: " + factoryMethodPath);
            }

            String className = factoryMethodPath.substring(0, sep);
            String methodName = factoryMethodPath.substring(sep + 1);

            Class<?> factoryClass = Class.forName(className);

            factoryMethod = factoryClass.getMethod(methodName, Agent.class, String.class, Session.class, SqliteAticDatasetGraph.class);

        } catch (NoSuchMethodException ex) {
            throw new IllegalArgumentException(
                    "Method not found: " + factoryMethodPath, ex
            );
        } catch (SecurityException ex) {
            throw new IllegalArgumentException(
                    "Method not accessable: " + factoryMethodPath, ex
            );
        } catch (ClassNotFoundException ex) {
            throw new IllegalArgumentException(
                    "Class not found: " + factoryMethodPath, ex
            );
        }

        // ------------------------------------------------
        // invoke factory method
        // ------------------------------------------------
        try {
            Object result = factoryMethod.invoke(null, agent, config.toString(4), session, parent);

            if (!(result instanceof AgentProgram)) {
                throw new IllegalStateException(
                        "Factory method did not return Agent: " + factoryMethodPath
                );
            }

            AgentProgram agentProgram = (AgentProgram) result;

            return agentProgram;

        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
            throw new IllegalStateException(
                    "Could not invoke factory method: " + factoryMethodPath, ex
            );
        }
    }

}
