package com.subjex.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * GatewayRateLimitTest — 网关限流测试：同一客户端在时间窗内超限被拒绝，换窗后恢复。
 */
class GatewayRateLimitTest {

    @Test
    void refusesAfterPermitsThenAllowsInTheNextWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-05T06:00:00Z"));
        GatewayRateLimit limit = new GatewayRateLimit(2, Duration.ofSeconds(60), clock);
        assertTrue(limit.permit("10.0.0.1", GatewayWiring.HTTP_ACTION));
        assertTrue(limit.permit("10.0.0.1", GatewayWiring.HTTP_ACTION));
        assertFalse(limit.permit("10.0.0.1", GatewayWiring.HTTP_ACTION));
        assertTrue(limit.permit("10.0.0.2", GatewayWiring.HTTP_ACTION));
        clock.advance(Duration.ofSeconds(61));
        assertTrue(limit.permit("10.0.0.1", GatewayWiring.HTTP_ACTION));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
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
