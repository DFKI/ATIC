

package de.dfki.sds.atic.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

public class InMemoryLogHandler extends Handler {

    private final List<LogRecord> records =
            new ArrayList<>();

    @Override
    public synchronized void publish(LogRecord record) {

        if (!isLoggable(record)) {
            return;
        }

        records.add(record);
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
}
