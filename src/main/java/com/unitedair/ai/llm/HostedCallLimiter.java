package com.unitedair.ai.llm;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * Shared bulkhead for hosted model calls.
 *
 * <p>A fair semaphore prevents a burst of embeddings from starving passenger chat calls.
 * Callers use a short timed acquisition and degrade locally when capacity is exhausted,
 * rather than creating an unbounded queue of network work.
 */
@Component
public final class HostedCallLimiter {

    private final Semaphore permits;

    public HostedCallLimiter() {
        this(Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())));
    }

    public HostedCallLimiter(int permits) {
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be at least one");
        }
        this.permits = new Semaphore(permits, true);
    }

    public boolean tryAcquire(Duration timeout) {
        long millis = Math.max(0, timeout == null ? 0 : timeout.toMillis());
        try {
            return permits.tryAcquire(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void release() {
        permits.release();
    }
}
