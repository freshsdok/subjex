package com.subjex.platform.app.delivery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SamplePathCircuitBreakerTest {

    @Test
    void consecutiveFailuresOpenTheBreakerAndSuccessClearsTheStreak() {
        SamplePathCircuitBreaker breaker = new SamplePathCircuitBreaker(3);
        assertTrue(breaker.allowCall());
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        breaker.recordFailure();
        assertFalse(breaker.isOpen());
        breaker.recordFailure();
        assertTrue(breaker.isOpen());
        assertFalse(breaker.allowCall());
    }

    @Test
    void halfOpenAllowsAProbeAfterCooldown() {
        MovableClock clock = new MovableClock(Instant.parse("2026-10-05T00:00:00Z"));
        SamplePathCircuitBreaker breaker =
                new SamplePathCircuitBreaker(2, Duration.ofSeconds(10), clock);
        breaker.recordFailure();
        breaker.recordFailure();
        assertTrue(breaker.isTripped());
        assertFalse(breaker.allowCall());

        clock.advance(Duration.ofSeconds(9));
        assertFalse(breaker.allowCall());

        clock.advance(Duration.ofSeconds(1));
        assertTrue(breaker.allowCall());
        breaker.recordSuccess();
        assertFalse(breaker.isTripped());
        assertTrue(breaker.allowCall());
    }

    @Test
    void halfOpenFailureExtendsTheOpenWindow() {
        MovableClock clock = new MovableClock(Instant.parse("2026-10-05T00:00:00Z"));
        SamplePathCircuitBreaker breaker =
                new SamplePathCircuitBreaker(1, Duration.ofSeconds(5), clock);
        breaker.recordFailure();
        assertFalse(breaker.allowCall());

        clock.advance(Duration.ofSeconds(5));
        assertTrue(breaker.allowCall());
        breaker.recordFailure();
        assertFalse(breaker.allowCall());

        clock.advance(Duration.ofSeconds(4));
        assertFalse(breaker.allowCall());
        clock.advance(Duration.ofSeconds(1));
        assertTrue(breaker.allowCall());
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        private MovableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
