package com.subjex.platform.app.delivery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.delivery.DeliveryCircuitBreakerPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;


/**
 * JdbcDeliveryCircuitBreakerPortTest — purpose: shared JDBC delivery circuit breaker.
 * Gates: threshold opens breaker (fail-closed deliver); success resets; cooldown half-open.
 * <p>
 * 目的：共享 JDBC 投递熔断。门禁：达阈打开（失败关闭不再投递）；成功复位；冷却半开。
 */
class JdbcDeliveryCircuitBreakerPortTest {

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void twoInstancesShareOpenState(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        MovableClock clock = new MovableClock(Instant.parse("2026-10-09T06:00:00Z"));
        Duration cooldown = Duration.ofSeconds(30);
        DeliveryCircuitBreakerPort instanceA =
                new JdbcDeliveryCircuitBreakerPort(jdbc, "outbox", 2, cooldown, clock);
        DeliveryCircuitBreakerPort instanceB =
                new JdbcDeliveryCircuitBreakerPort(jdbc, "outbox", 2, cooldown, clock);

        assertTrue(instanceA.allowCall());
        assertTrue(instanceB.allowCall());
        instanceA.recordFailure();
        assertFalse(instanceA.isOpen());
        assertFalse(instanceB.isOpen());
        instanceB.recordFailure();
        assertTrue(instanceA.isOpen(), "open after shared failure streak");
        assertTrue(instanceB.isOpen(), "second instance sees the same open state");
        assertFalse(instanceA.allowCall());
        assertFalse(instanceB.allowCall());

        clock.advance(Duration.ofSeconds(30));
        assertTrue(instanceB.allowCall(), "half-open probe visible to both");
        instanceB.recordSuccess();
        assertFalse(instanceA.isTripped());
        assertTrue(instanceA.allowCall());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void destinationsAreIsolated(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        Clock clock = Clock.systemUTC();
        Duration cooldown = Duration.ofHours(1);
        DeliveryCircuitBreakerPort socket =
                new JdbcDeliveryCircuitBreakerPort(jdbc, "socket:demo", 1, cooldown, clock);
        DeliveryCircuitBreakerPort kafka =
                new JdbcDeliveryCircuitBreakerPort(jdbc, "kafka:demo", 1, cooldown, clock);

        socket.recordFailure();
        assertTrue(socket.isOpen());
        assertTrue(kafka.allowCall());
        assertFalse(kafka.isTripped());
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
