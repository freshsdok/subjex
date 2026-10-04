package com.subjex.platform.app.ratelimit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.contract.ratelimit.RateLimitPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class SingleProcessRateLimitTest {

    @Test
    void permitsStopAtTheWindowLimitAndResumeAfterTheWindow() {
        AdjustableClock clock = new AdjustableClock(Instant.parse("2026-10-05T00:00:00Z"));
        RateLimitPort limit = new SingleProcessRateLimit(2, Duration.ofMinutes(1), clock);

        assertTrue(limit.permit("tenant-north", "submit-task"));
        assertTrue(limit.permit("tenant-north", "submit-task"));
        assertFalse(limit.permit("tenant-north", "submit-task"));
        assertTrue(limit.permit("tenant-other", "submit-task"));

        clock.advance(Duration.ofMinutes(1));
        assertTrue(limit.permit("tenant-north", "submit-task"));
    }

    private static final class AdjustableClock extends Clock {
        private Instant now;

        private AdjustableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
