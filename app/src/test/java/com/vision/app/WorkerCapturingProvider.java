package com.vision.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Test helper: records the exact worker threads that enter propose(), in call order. */
final class WorkerCapturingProvider implements ReasoningProvider {
    private final ReasoningProvider delegate;
    private final List<Thread> workers = Collections.synchronizedList(new ArrayList<Thread>());

    WorkerCapturingProvider(ReasoningProvider delegate) { this.delegate = delegate; }

    @Override public String propose(String userRequest) {
        workers.add(Thread.currentThread());
        return delegate.propose(userRequest);
    }

    @Override public String propose(String userRequest, List<String> recentRequests) {
        workers.add(Thread.currentThread());
        return delegate.propose(userRequest, recentRequests);
    }

    Thread worker(int index) { return workers.get(index); }

    /** Snapshot of every recorded worker, in entry order. */
    java.util.List<Thread> capturedWorkers() {
        synchronized (workers) {
            return new ArrayList<>(workers);
        }
    }

    boolean awaitWorkers(int count, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (workers.size() >= count) return true;
            Thread.sleep(10);
        }
        return workers.size() >= count;
    }
}
