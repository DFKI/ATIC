package de.dfki.sds.atic.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

public class InMemoryLogHandler extends Handler {

    private final List<LogRecord> records
            = new ArrayList<>();

    private final List<LogRecordListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public synchronized void publish(LogRecord record) {

        if (!isLoggable(record)) {
            return;
        }

        records.add(record);
        notifyLog(record);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    public synchronized List<LogRecord> getRecords() {
        return List.copyOf(records);
    }

    public void addListener(LogRecordListener listener) {
        listeners.add(listener);
    }

    public void removeListener(LogRecordListener listener) {
        listeners.remove(listener);
    }

    public void notifyLog(LogRecord record) {
        notifyListeners(listener
                -> listener.onLogRecord(
                        record
                ));
    }

    private void notifyListeners(Consumer<LogRecordListener> action) {

        for (LogRecordListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public interface LogRecordListener {

        void onLogRecord(LogRecord record);
    }

}
