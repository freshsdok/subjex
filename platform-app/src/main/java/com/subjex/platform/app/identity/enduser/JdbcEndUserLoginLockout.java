package com.subjex.platform.app.identity.enduser;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcEndUserLoginLockout — phone-login failure counter and window (ADR 0014 / WP2-3).
 * 终端手机号登录失败计数与锁定窗口。
 * <p>
 * Same grade as operators (default 5 / PT15M) but writes {@code end_user_login_lockout} only.
 * Phone keys use {@link #keyForPhone(String)}; WeChat keys use {@link #keyForWeChat(String, String)}.
 * 手机号键见 {@link #keyForPhone}；微信键见 {@link #keyForWeChat}。
 * 与操作员同级（默认 5 次 / 15 分钟），只写终端锁定表，不共享 {@code operator_login_lockout}。
 */
public final class JdbcEndUserLoginLockout {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int maxFailures;
    private final Duration lockoutDuration;

    public JdbcEndUserLoginLockout(
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

    /** Build the row key for a phone — 由手机号构造锁定键。 */
    public static String keyForPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("phone is required");
        }
        return "phone:" + phone.trim();
    }

    /** Build the row key for a WeChat IdP subject — 由微信 IdP 主体构造锁定键。 */
    public static String keyForWeChat(String issuer, String idpSubject) {
        if (issuer == null || issuer.isBlank() || idpSubject == null || idpSubject.isBlank()) {
            throw new IllegalArgumentException("wechat issuer and idp_subject are required");
        }
        return "wechat:" + issuer.trim() + ":" + idpSubject.trim();
    }

    /**
     * Refuse when {@code locked_until} is still in the future — 仍在锁定窗口内则拒绝。
     */
    public void assertNotLocked(String phone) {
        String key = keyForPhone(phone);
        Instant now = clock.instant();
        List<Row> rows = load(key);
        if (rows.isEmpty()) {
            return;
        }
        Instant lockedUntil = rows.get(0).lockedUntil();
        if (lockedUntil != null && now.isBefore(lockedUntil)) {
            throw new EndUserLoginLockoutException(key);
        }
    }

    /**
     * Increment failure count; lock when the threshold is reached.
     * 累加失败；达阈则写入 locked_until。
     */
    public void recordFailure(String phone) {
        recordFailureOnKey(keyForPhone(phone));
    }

    private void recordFailureOnKey(String key) {
        Instant now = clock.instant();
        transaction.executeWithoutResult(status -> {
            List<Row> rows = load(key);
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
                    jdbc.update(
                            """
                            UPDATE end_user_login_lockout
                            SET updated_at = ?
                            WHERE lockout_key = ?
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
                        INSERT INTO end_user_login_lockout
                            (lockout_key, failure_count, locked_until, updated_at)
                        VALUES (?, ?, ?, ?)
                        """,
                        key,
                        nextCount,
                        lockedUntil == null ? null : java.sql.Timestamp.from(lockedUntil),
                        java.sql.Timestamp.from(now));
            } else {
                jdbc.update(
                        """
                        UPDATE end_user_login_lockout
                        SET failure_count = ?, locked_until = ?, updated_at = ?
                        WHERE lockout_key = ?
                        """,
                        nextCount,
                        lockedUntil == null ? null : java.sql.Timestamp.from(lockedUntil),
                        java.sql.Timestamp.from(now),
                        key);
            }
        });
    }

    /** Clear counters after a successful login — 登录成功后清除。 */
    public void clear(String phone) {
        clearKey(keyForPhone(phone));
    }

    /** Same as {@link #assertNotLocked(String)} for an already-formed key — 按已构造的锁定键检查。 */
    public void assertNotLockedKey(String lockoutKey) {
        String key = requireRawKey(lockoutKey);
        Instant now = clock.instant();
        List<Row> rows = load(key);
        if (rows.isEmpty()) {
            return;
        }
        Instant lockedUntil = rows.get(0).lockedUntil();
        if (lockedUntil != null && now.isBefore(lockedUntil)) {
            throw new EndUserLoginLockoutException(key);
        }
    }

    /** Record a failure for an already-formed key — 按已构造的锁定键记一次失败。 */
    public void recordFailureKey(String lockoutKey) {
        recordFailureOnKey(requireRawKey(lockoutKey));
    }

    /** Clear an already-formed key — 清除已构造的锁定键。 */
    public void clearKey(String lockoutKey) {
        jdbc.update(
                "DELETE FROM end_user_login_lockout WHERE lockout_key = ?", requireRawKey(lockoutKey));
    }

    private static String requireRawKey(String lockoutKey) {
        if (lockoutKey == null || lockoutKey.isBlank()) {
            throw new IllegalArgumentException("lockout key is required");
        }
        String key = lockoutKey.trim();
        if (key.length() > 256) {
            throw new IllegalArgumentException("lockout key must be at most 256 characters");
        }
        return key;
    }

    private List<Row> load(String key) {
        return jdbc.query(
                """
                SELECT failure_count, locked_until
                FROM end_user_login_lockout
                WHERE lockout_key = ?
                """,
                (row, n) -> new Row(
                        row.getInt("failure_count"),
                        row.getTimestamp("locked_until") == null
                                ? null
                                : row.getTimestamp("locked_until").toInstant()),
                key);
    }

    private record Row(int failureCount, Instant lockedUntil) {}
}
