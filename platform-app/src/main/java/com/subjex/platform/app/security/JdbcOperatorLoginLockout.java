package com.subjex.platform.app.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcOperatorLoginLockout — 口令登录失败计数与临时锁定（按登录名）。
 * <p>
 * Keys match {@link JdbcOperatorDirectory}: trimmed login name as typed (not lowercased).
 * After {@code maxFailures} consecutive failures, {@code locked_until = now + lockoutDuration}.
 * Successful password auth (or MFA complete) clears the row. Expired locks reset on next check/failure.
 * 键与名录一致：trim 后原样登录名。连续失败达阈则锁定；成功清除；过期锁定在下次检查/失败时复位。
 */
public final class JdbcOperatorLoginLockout {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int maxFailures;
    private final Duration lockoutDuration;

    public JdbcOperatorLoginLockout(
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            Clock clock,
            int maxFailures,
            Duration lockoutDuration) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxFailures < 1) {
            throw new IllegalArgumentException("lockout-max-failures must be >= 1");
        }
        this.maxFailures = maxFailures;
        this.lockoutDuration = Objects.requireNonNull(lockoutDuration, "lockoutDuration");
        if (lockoutDuration.isNegative() || lockoutDuration.isZero()) {
            throw new IllegalArgumentException("lockout-duration must be positive");
        }
    }

    public int maxFailures() {
        return maxFailures;
    }

    public Duration lockoutDuration() {
        return lockoutDuration;
    }

    /**
     * Refuse when {@code locked_until} is still in the future — 仍在锁定窗口内则拒绝。
     */
    public void assertNotLocked(String loginName) {
        String key = requireKey(loginName);
        Instant now = clock.instant();
        List<Row> rows = jdbc.query(
                """
                SELECT failure_count, locked_until
                FROM operator_login_lockout
                WHERE login_name = ?
                """,
                (row, n) -> new Row(
                        row.getInt("failure_count"),
                        row.getTimestamp("locked_until") == null
                                ? null
                                : row.getTimestamp("locked_until").toInstant()),
                key);
        if (rows.isEmpty()) {
            return;
        }
        Instant lockedUntil = rows.get(0).lockedUntil();
        if (lockedUntil != null && now.isBefore(lockedUntil)) {
            throw new LoginLockoutException(key);
        }
    }

    /**
     * Increment failure count; lock when threshold reached — 累加失败；达阈则写入 locked_until。
     */
    public void recordFailure(String loginName) {
        String key = requireKey(loginName);
        Instant now = clock.instant();
        transaction.executeWithoutResult(status -> {
            List<Row> rows = jdbc.query(
                    """
                    SELECT failure_count, locked_until
                    FROM operator_login_lockout
                    WHERE login_name = ?
                    """,
                    (row, n) -> new Row(
                            row.getInt("failure_count"),
                            row.getTimestamp("locked_until") == null
                                    ? null
                                    : row.getTimestamp("locked_until").toInstant()),
                    key);
            int nextCount;
            if (rows.isEmpty()) {
                nextCount = 1;
            } else {
                Row existing = rows.get(0);
                boolean expiredLock =
                        existing.lockedUntil() != null && !now.isBefore(existing.lockedUntil());
                boolean activeLock =
                        existing.lockedUntil() != null && now.isBefore(existing.lockedUntil());
                if (activeLock) {
                    // Already locked; keep window, bump updated_at only.
                    // 已锁定：保持窗口，只刷新 updated_at。
                    jdbc.update(
                            """
                            UPDATE operator_login_lockout
                            SET updated_at = ?
                            WHERE login_name = ?
                            """,
                            java.sql.Timestamp.from(now),
                            key);
                    return;
                }
                nextCount = expiredLock ? 1 : existing.failureCount() + 1;
            }
            Instant lockedUntil = nextCount >= maxFailures ? now.plus(lockoutDuration) : null;
            if (rows.isEmpty()) {
                jdbc.update(
                        """
                        INSERT INTO operator_login_lockout
                            (login_name, failure_count, locked_until, updated_at)
                        VALUES (?, ?, ?, ?)
                        """,
                        key,
                        nextCount,
                        lockedUntil == null ? null : java.sql.Timestamp.from(lockedUntil),
                        java.sql.Timestamp.from(now));
            } else {
                jdbc.update(
                        """
                        UPDATE operator_login_lockout
                        SET failure_count = ?, locked_until = ?, updated_at = ?
                        WHERE login_name = ?
                        """,
                        nextCount,
                        lockedUntil == null ? null : java.sql.Timestamp.from(lockedUntil),
                        java.sql.Timestamp.from(now),
                        key);
            }
        });
    }

    /** Clear counters after successful password (or MFA complete) — 口令成功（或 MFA 完成）后清除。 */
    public void clear(String loginName) {
        String key = requireKey(loginName);
        jdbc.update("DELETE FROM operator_login_lockout WHERE login_name = ?", key);
    }

    private static String requireKey(String loginName) {
        if (loginName == null || loginName.isBlank()) {
            throw new IllegalArgumentException("loginName is required");
        }
        return loginName.trim();
    }

    private record Row(int failureCount, Instant lockedUntil) {}
}
