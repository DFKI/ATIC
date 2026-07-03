package de.dfki.sds.aticsqlite.agent;

import de.dfki.sds.atic.ac.Agent;
import de.dfki.sds.atic.agent.AgentProgram;
import de.dfki.sds.atic.agent.JobWorker;
import de.dfki.sds.atic.agent.Session;
import de.dfki.sds.aticsqlite.SqliteAticDatasetGraph;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

public class AgentSessionManager implements AutoCloseable {

    private record SessionKey(
            String sessionId,
            String agentUsername
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

    public JobWorker addSession(String sessionId, Agent agent, SqliteAticDatasetGraph parent) {

        removeExpiredSessions();

        SessionKey key = new SessionKey(
                sessionId,
                agent.getUsername()
        );

        if (containsSession(sessionId, agent.getUsername())) {
            throw new IllegalStateException(
                    "Session already exists for sessionId="
                    + sessionId
                    + ", agent="
                    + agent.getUsername()
            );
        }

        Instant expiresAt = Instant.now().plus(expirationDuration);

        JobWorker jobWorker = new JobWorker(loadAgent(agent, parent));

        Session session = new Session(
                sessionId,
                jobWorker,
                expiresAt
        );

        sessions.put(key, session);

        jobWorker.start();

        return jobWorker;
    }

    public Session getSession(String sessionId, String agentUsername) {

        removeExpiredSessions();

        Session session = sessions.get(
                new SessionKey(sessionId, agentUsername)
        );

        if (session != null) {
            session.setExpiresAt(
                    Instant.now().plus(expirationDuration)
            );
        }

        return session;
    }

    public Session getSession(String sessionId, Agent agent) {
        return getSession(
                sessionId,
                agent.getUsername()
        );
    }

    public boolean containsSession(
            String sessionId,
            String agentUsername) {

        removeExpiredSessions();

        return sessions.containsKey(
                new SessionKey(sessionId, agentUsername)
        );
    }

    public void removeSession(
            String sessionId,
            String agentUsername) {

        Session removed = sessions.remove(
                new SessionKey(sessionId, agentUsername)
        );

        if (removed != null) {
            removed.getWorker().close();
        }
    }

    public List<Session> listSessions() {

        removeExpiredSessions();

        return List.copyOf(sessions.values());
    }

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

    private AgentProgram loadAgent(Agent agent, SqliteAticDatasetGraph parent) {

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

            factoryMethod = factoryClass.getMethod(methodName, Agent.class, String.class, SqliteAticDatasetGraph.class);

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
            Object result = factoryMethod.invoke(null, agent, config.toString(4), parent);

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
