package com.subjex.platform.app.schedule;

import com.subjex.platform.app.workflow.WorkflowService;
import com.subjex.platform.contract.schedule.ScheduleActionType;
import com.subjex.platform.contract.schedule.ScheduleClaim;
import com.subjex.platform.contract.schedule.SchedulePort;
import com.subjex.platform.contract.task.TaskCommand;
import com.subjex.platform.contract.task.TaskKind;
import com.subjex.platform.contract.task.TaskMessagePort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ScheduleWorker — claim due operator schedules and fire the closed action set.
 * 领取已到期的操作员调度，并执行封闭动作集合。
 * <p>
 * Claim takes a short FOR UPDATE, writes a lease, then commits so the row lock is released, the same
 * honesty as the export worker. Two workers cannot hold a still-valid lease on the same row. If a
 * worker dies after the action and before {@code complete}, a later owner may retry the same tick
 * (at-least-once). Task enqueue is idempotent per schedule+tick; workflow start uses a unique
 * {@code recordId@epochMillis} so a retried tick does not open a second instance.
 * 领取时短时锁行写入租约再提交释放行锁，与导出工作器同一诚实口径。有效租约不会被第二个工作器同时持有。
 * 若动作已执行但尚未 complete，后续持有人可能重试同一拍（至少一次）。任务入队按调度+拍幂等；
 * 工作流启动使用 recordId@纪元毫秒，重试同一拍不会再开实例。
 */
@Component
public final class ScheduleWorker {

    static final Duration LEASE_TTL = Duration.ofMinutes(2);
    private final SchedulePort schedules;
    private final TaskMessagePort tasks;
    private final WorkflowService workflows;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int batchSize;
    private final String owner;

    /**
     * Production constructor. {@code @Autowired} is required because a package-private test
     * constructor also exists; otherwise Spring looks for a no-arg constructor and the app fails to start.
     * 生产构造。因为还有一个包内测试构造，必须标明 {@code @Autowired}，否则 Spring 会去找无参构造，应用无法启动。
     */
    @Autowired
    public ScheduleWorker(
            SchedulePort schedules,
            TaskMessagePort tasks,
            WorkflowService workflows,
            TransactionTemplate transaction,
            Clock clock,
            @Value("${platform.schedule.poll-batch-size:10}") int batchSize) {
        this(
                schedules,
                tasks,
                workflows,
                transaction,
                clock,
                batchSize,
                "schedule-worker-" + UUID.randomUUID());
    }

    ScheduleWorker(
            SchedulePort schedules,
            TaskMessagePort tasks,
            WorkflowService workflows,
            TransactionTemplate transaction,
            Clock clock,
            int batchSize,
            String owner) {
        this.schedules = Objects.requireNonNull(schedules, "schedules");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (batchSize < 1) {
            throw new IllegalArgumentException("schedule poll batch size must be at least 1");
        }
        this.batchSize = batchSize;
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    /**
     * Drain due schedules — 领取并执行已到期的调度.
     */
    @Scheduled(fixedDelayString = "${platform.schedule.poll-interval-ms:2000}")
    public void processDue() {
        processDue(batchSize);
    }

    /**
     * Claim and fire up to {@code limit} due rows. Returns how many actions completed.
     * 领取并触发最多 {@code limit} 条到期行；返回完成的动作数。
     */
    public int processDue(int limit) {
        Instant now = clock.instant();
        int count = 0;
        for (String scheduleId : schedules.listDue(now, limit)) {
            if (processOne(scheduleId)) {
                count++;
            }
        }
        return count;
    }

    private boolean processOne(String scheduleId) {
        AtomicBoolean claimed = new AtomicBoolean(false);
        ScheduleClaim[] held = new ScheduleClaim[1];
        transaction.executeWithoutResult(status -> {
            schedules.claim(scheduleId, owner, clock.instant(), LEASE_TTL).ifPresent(row -> {
                held[0] = row;
                claimed.set(true);
            });
        });
        if (!claimed.get()) {
            return false;
        }
        ScheduleClaim claim = held[0];
        try {
            if (!schedules.renewLease(scheduleId, owner, clock.instant(), LEASE_TTL)) {
                return false;
            }
            fire(claim);
            Instant now = clock.instant();
            Instant next = ScheduleCron.nextRunAfter(claim.cronExpression(), now);
            AtomicBoolean completed = new AtomicBoolean(false);
            transaction.executeWithoutResult(status -> {
                if (schedules.complete(scheduleId, owner, now, next)) {
                    completed.set(true);
                }
            });
            return completed.get();
        } catch (RuntimeException ex) {
            transaction.executeWithoutResult(status ->
                    schedules.releaseFailed(scheduleId, owner, clock.instant(), ex.getMessage()));
            return false;
        }
    }

    private void fire(ScheduleClaim claim) {
        if (claim.actionType() == ScheduleActionType.ENQUEUE_TASK) {
            tasks.submit(new TaskCommand(
                    claim.tenantId(),
                    ScheduleCron.taskIdempotencyToken(claim.scheduleId(), claim.dueAt()),
                    claim.createdByIdentityId(),
                    TaskKind.DETERMINISTIC,
                    claim.taskStepName(),
                    true,
                    null,
                    null,
                    null));
            return;
        }
        if (claim.actionType() == ScheduleActionType.START_WORKFLOW) {
            workflows.startFromSchedule(
                    claim.workflowDefinitionKey(),
                    claim.tenantId(),
                    claim.workflowEntityKey(),
                    ScheduleCron.workflowRecordId(claim.workflowRecordId(), claim.dueAt()),
                    claim.createdBySubjectId());
            return;
        }
        throw new IllegalArgumentException("unknown schedule action: " + claim.actionType());
    }
}
