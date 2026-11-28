package com.subjex.platform.contract.schedule;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SchedulePort — persist and claim operator schedules.
 * 持久化并领取操作员调度。
 * <p>
 * Claim uses a short row lock to write a lease, then the caller commits so the lock is released.
 * 领取时短时锁行写入租约，由调用方提交以释放行锁。
 */
public interface SchedulePort {

    ScheduleRecord create(CreateScheduleCommand command, String workflowEntityKey, Instant nextRunAt);

    List<ScheduleRecord> list(String tenantId, int limit);

    Optional<ScheduleRecord> find(String tenantId, String scheduleId);

    ScheduleRecord enable(String tenantId, String scheduleId, Instant nextRunAt, Instant now);

    ScheduleRecord disable(String tenantId, String scheduleId, Instant now);

    /**
     * Enabled rows whose next run is due and whose lease is missing or expired, oldest due first.
     * 已启用、已到期、且租约缺失或已过期的行，按到期时间从早到晚。
     */
    List<String> listDue(Instant now, int limit);

    /**
     * Take a short FOR UPDATE, write the lease, then let the caller commit so the lock is released.
     * Must run inside a transaction.
     * 短时 FOR UPDATE 写入租约，由调用方提交以释放行锁。必须在事务中调用。
     */
    Optional<ScheduleClaim> claim(String scheduleId, String owner, Instant now, Duration ttl);

    /**
     * Extend a still-valid lease held by {@code owner}. Returns false if the lease was lost.
     * 为仍由该持有人持有的有效租约续期；租约丢失则返回 false。
     */
    boolean renewLease(String scheduleId, String owner, Instant now, Duration ttl);

    /**
     * Mark a successful fire, advance next_run_at, and clear the lease. Must run inside a transaction.
     * 记下一次成功触发，推进下次运行时间并清租约。必须在事务中调用。
     */
    boolean complete(String scheduleId, String owner, Instant now, Instant nextRunAt);

    /**
     * Clear the lease after a failed fire without advancing next_run_at, so the same tick can retry.
     * 失败后清租约但不推进下次运行时间，同一拍可以再试。
     */
    boolean releaseFailed(String scheduleId, String owner, Instant now, String error);
}
