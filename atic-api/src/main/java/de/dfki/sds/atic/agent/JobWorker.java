package de.dfki.sds.atic.agent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public final class JobWorker implements AutoCloseable {

    private final BlockingQueue<Job> queue = new LinkedBlockingQueue<>();
    private final Thread workerThread;
    private final AgentProgram agent;

    private volatile boolean running = true;

    public JobWorker(AgentProgram agent) {
        this.agent = agent;

        this.workerThread = new Thread(this::runLoop, "job-worker");
    }
    
    public void start() {
        this.workerThread.start();
    }

    public void submit(Job job) {
        if (!running) {
            throw new IllegalStateException("Worker is closed");
        }

        queue.offer(job);
    }

    private void runLoop() {
        try {
            while (running) {
                Job job = queue.take(); // blocks until a job is available

                if (job == ShutdownJob.INSTANCE) {
                    break;
                }

                try {
                    agent.process(job);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running = false;
        queue.offer(ShutdownJob.INSTANCE);

        try {
            workerThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}