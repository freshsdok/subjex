package com.subjex.platform.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcOperatorLoginLockoutTest — purpose: lockout counter/threshold/expiry/clear.
 * Gates: N failures lock; lock expires; success clears (dual H2 MODE).
 * <p>
 * 目的：登录锁定计数/阈值/过期/清除。门禁：N 次失败锁定；到期解除；成功清除。
 */
class JdbcOperatorLoginLockoutTest {

    private static final String LOGIN = "lockout-user";

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void thresholdsLockExpireAndClear(H2PlatformTables.Mode mode) {
        AdjustableClock clock = new AdjustableClock(Instant.parse("2026-10-09T00:00:00Z"));
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOperatorLoginLockout lockout = new JdbcOperatorLoginLockout(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                clock,
                3,
                Duration.ofMinutes(15));

        lockout.recordFailure(LOGIN);
        lockout.recordFailure(LOGIN);
        assertThatCode(() -> lockout.assertNotLocked(LOGIN)).doesNotThrowAnyException();

        lockout.recordFailure(LOGIN);
        assertThatThrownBy(() -> lockout.assertNotLocked(LOGIN)).isInstanceOf(LoginLockoutException.class);

        Integer count = jdbc.queryForObject(
                "SELECT failure_count FROM operator_login_lockout WHERE login_name = ?", Integer.class, LOGIN);
        assertThat(count).isEqualTo(3);

        clock.advance(Duration.ofMinutes(15));
        assertThatCode(() -> lockout.assertNotLocked(LOGIN)).doesNotThrowAnyException();

        lockout.recordFailure(LOGIN);
        assertThatCode(() -> lockout.assertNotLocked(LOGIN)).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject(
                        "SELECT failure_count FROM operator_login_lockout WHERE login_name = ?",
                        Integer.class,
                        LOGIN))
                .isEqualTo(1);

        lockout.clear(LOGIN);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM operator_login_lockout WHERE login_name = ?", Integer.class, LOGIN))
                .isZero();
        assertThatCode(() -> lockout.assertNotLocked(LOGIN)).doesNotThrowAnyException();
    }

    @Test
    void clearRemovesActiveLock() {
        AdjustableClock clock = new AdjustableClock(Instant.parse("2026-10-09T00:00:00Z"));
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL));
        JdbcOperatorLoginLockout lockout = new JdbcOperatorLoginLockout(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())),
                clock,
                2,
                Duration.ofMinutes(10));
        lockout.recordFailure(LOGIN);
        lockout.recordFailure(LOGIN);
        assertThatThrownBy(() -> lockout.assertNotLocked(LOGIN)).isInstanceOf(LoginLockoutException.class);
        lockout.clear(LOGIN);
        assertThatCode(() -> lockout.assertNotLocked(LOGIN)).doesNotThrowAnyException();
    }

    private static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> now;

        private AdjustableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        void advance(Duration by) {
            now.updateAndGet(t -> t.plus(by));
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
            return now.get();
        }
    }
}
