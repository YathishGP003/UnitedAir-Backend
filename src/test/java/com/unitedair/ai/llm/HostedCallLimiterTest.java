package com.unitedair.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class HostedCallLimiterTest {

    @Test
    void timedAcquireDoesNotStartAnUnboundedHostedCall() {
        HostedCallLimiter limiter = new HostedCallLimiter(1);
        assertThat(limiter.tryAcquire(Duration.ZERO)).isTrue();

        long started = System.nanoTime();
        assertThat(limiter.tryAcquire(Duration.ofMillis(20))).isFalse();

        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(200);
        limiter.release();
        assertThat(limiter.tryAcquire(Duration.ZERO)).isTrue();
    }
}
