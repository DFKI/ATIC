package de.dfki.sds.atic.agent;

import java.util.logging.LogRecord;

/**
 *
 */
public interface SessionListener {

    default void onSessionCreated(Session session) {
    }

    default void onMessageSubmitted(
            Session session,
            Message message) {
    }

    default void onMessageProcessingStarted(
            Session session,
            Message message) {
    }

    default void onMessageProcessingFinished(
            Session session,
            Message message) {
    }

    default void onMessage(
            Session session,
            Message message) {
    }

    default void onLog(
            Session session,
            LogRecord record) {
    }

    default void onError(
            Session session,
            Throwable error) {
    }

    default void onClosed(
            Session session) {
    }
}
