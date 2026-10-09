package com.subjex.platform.app.ratelimit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.ratelimit.RateLimitPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcRateLimitPortTest — JDBC 共享限流：两份端口实例共用一库时共享额度。
 * <p>
 * Proves Scale-4b shared budget on H2 (PostgreSQL and MySQL modes) with real Flyway scripts.
 * 在 H2 的两种兼容模式上用真实 Flyway 脚本证明 Scale-4b 共享额度。
 */
class JdbcRateLimitPortTest {

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void twoInstancesShareOneBudget(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        AdjustableClock clock = new AdjustableClock(Instant.parse("2026-10-09T00:00:00Z"));
        Duration window = Duration.ofMinutes(1);
        RateLimitPort instanceA = new JdbcRateLimitPort(jdbc, 3, window, clock);
        RateLimitPort instanceB = new JdbcRateLimitPort(jdbc, 3, window, clock);

        assertTrue(instanceA.permit("tenant-north", "submit-task"));
        assertTrue(instanceB.permit("tenant-north", "submit-task"));
        assertTrue(instanceA.permit("tenant-north", "submit-task"));
        assertFalse(instanceB.permit("tenant-north", "submit-task"), "shared budget exhausted across instances");
        assertFalse(instanceA.permit("tenant-north", "submit-task"));
        assertTrue(instanceB.permit("tenant-other", "submit-task"), "other tenant has its own bucket");

        clock.advance(Duration.ofMinutes(1));
        assertTrue(instanceB.permit("tenant-north", "submit-task"), "window reset is visible to both");
        assertTrue(instanceA.permit("tenant-north", "submit-task"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void rejectsBlankTenantOrAction(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        RateLimitPort limit = new JdbcRateLimitPort(jdbc, 2, Duration.ofMinutes(1), Clock.systemUTC());
        assertThrows(IllegalArgumentException.class, () -> limit.permit(" ", "submit-task"));
        assertThrows(IllegalArgumentException.class, () -> limit.permit("t", " "));
    }

    @Test
    void constructorRejectsNonPositivePermitsOrWindow() {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL));
        Clock clock = Clock.systemUTC();
        assertThrows(IllegalArgumentException.class, () -> new JdbcRateLimitPort(jdbc, 0, Duration.ofMinutes(1), clock));
        assertThrows(IllegalArgumentException.class, () -> new JdbcRateLimitPort(jdbc, 1, Duration.ZERO, clock));
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
