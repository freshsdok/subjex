package com.subjex.platform.app.jdbc;

import com.subjex.platform.contract.lock.DistributedLockPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcRowLock — JDBC 行锁：一把具名锁是 {@code platform_lock} 里的一行，写着持有者和到期时间。
 * <p>
 * Acquire inserts the row; if the row exists, a single UPDATE takes it only when it has expired or already
 * belongs to this owner. Release deletes the row only for its owner. Each statement commits on its own, so a
 * primary-key loss does not poison a surrounding transaction. Expiry uses this process's clock, so processes
 * sharing the database must keep their clocks in step (NTP); the hold duration should exceed any skew.
 * 加锁先插入这一行；行已存在时，只有它已到期或本来就属于这个持有者，一条 UPDATE 才会接手。
 * 释放只删除属于该持有者的行。每条语句各自提交，主键冲突不会弄坏外层事务。
 * 到期用本进程的时钟，因此共用库的进程要保持时钟一致（NTP），持有时长应大于时钟偏差。
 */
public final class JdbcRowLock implements DistributedLockPort {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcRowLock(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean tryAcquire(String lockName, String ownerToken, Duration holdFor) {
        requireNames(lockName, ownerToken);
        if (holdFor == null || holdFor.isNegative() || holdFor.isZero()) {
            throw new IllegalArgumentException("hold duration must be positive");
        }
        Instant now = clock.instant();
        Instant until = now.plus(holdFor);
        try {
            jdbc.update(
                    "INSERT INTO platform_lock (lock_name, owner_token, held_until) VALUES (?, ?, ?)",
                    lockName, ownerToken, PlatformTables.timestamp(until));
            return true;
        } catch (DuplicateKeyException held) {
            int taken = jdbc.update(
                    """
                    UPDATE platform_lock SET owner_token = ?, held_until = ?
                    WHERE lock_name = ? AND (owner_token = ? OR held_until <= ?)
                    """,
                    ownerToken, PlatformTables.timestamp(until), lockName, ownerToken, PlatformTables.timestamp(now));
            return taken == 1;
        }
    }

    @Override
    public boolean release(String lockName, String ownerToken) {
        requireNames(lockName, ownerToken);
        return jdbc.update(
                "DELETE FROM platform_lock WHERE lock_name = ? AND owner_token = ?", lockName, ownerToken) == 1;
    }

    private static void requireNames(String lockName, String ownerToken) {
        if (lockName == null || lockName.isBlank() || ownerToken == null || ownerToken.isBlank()) {
            throw new IllegalArgumentException("lock name and owner are required");
        }
    }
}
