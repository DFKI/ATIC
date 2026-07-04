

package de.dfki.sds.atic.agent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 *
 */
public final class MessageWorker implements AutoCloseable {

    private final BlockingQueue<Message> queue =
            new LinkedBlockingQueue<>();

    private final Thread workerThread;
    private final AgentProgram agent;

    private volatile boolean running = true;

    public MessageWorker(AgentProgram agent) {

        this.agent = agent;

        this.workerThread = new Thread(
                this::runLoop,
                "message-worker"
        );
    }

    public void start() {
        workerThread.start();
    }

    public void submit(Message message) {

        if (!running) {
            throw new IllegalStateException(
                    "Worker is closed"
            );
        }

        queue.offer(message);
    }

    private void runLoop() {

        while (running) {

            try {

                Message message = queue.take();

                try {
                    agent.process(message);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

            } catch (InterruptedException e) {

                if (!running) {
                    break;
                }

                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {

        running = false;

        workerThread.interrupt();

        try {
            workerThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
