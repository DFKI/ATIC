package de.dfki.sds.atic.agent;

import java.time.Instant;

/**
 *
 */
public class Session {

    private final String sessionId;
    private final JobWorker worker;
    
    private String titel;

    private volatile Instant expiresAt;

    public Session(
            String sessionId,
            JobWorker worker,
            Instant expiresAt) {

        this.sessionId = sessionId;
        this.worker = worker;
        this.expiresAt = expiresAt;
    }

    public String getTitel() {
        return titel;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public JobWorker getWorker() {
        return worker;
    }

    public String getSessionId() {
        return sessionId;
    }
    
    
    
}
