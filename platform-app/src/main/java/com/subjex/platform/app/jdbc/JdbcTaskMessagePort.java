package com.subjex.platform.app.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subjex.platform.app.delivery.HttpEventStandIn;
import com.subjex.platform.app.delivery.StandInDelivery;
import com.subjex.platform.app.task.IdempotencyConflict;
import com.subjex.platform.app.task.SubmitLockHeld;
import com.subjex.platform.contract.audit.AuditEntry;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.audit.AuditPort;
import com.subjex.platform.contract.idempotency.IdempotencyClaim;
import com.subjex.platform.contract.idempotency.IdempotencyPort;
import com.subjex.platform.contract.lock.DistributedLockPort;
import com.subjex.platform.contract.task.DeadLetter;
import com.subjex.platform.contract.task.DeliveryProgress;
import com.subjex.platform.contract.task.DeliveryResult;
import com.subjex.platform.contract.task.HumanConfirmation;
import com.subjex.platform.contract.task.OriginKind;
import com.subjex.platform.contract.task.OutboxEvent;
import com.subjex.platform.contract.task.OutboxState;
import com.subjex.platform.contract.task.TaskCommand;
import com.subjex.platform.contract.task.TaskKind;
import com.subjex.platform.contract.task.TaskLifecycle;
import com.subjex.platform.contract.task.TaskMessagePort;
import com.subjex.platform.contract.task.TaskRecord;
import com.subjex.platform.contract.task.TaskRecordedNotice;
import com.subjex.platform.contract.task.TaskState;
import com.subjex.platform.contract.tenant.TenantState;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcTaskMessagePort — JDBC 任务消息端口：{@link TaskMessagePort} 的唯一实现。
 * <p>
 * The task row and the outbox row are written with SQL in one transaction. Delivery then uses
 * {@link HttpEventStandIn}, the v1 cross-process stand-in. There is no entity model on this path.
 * 任务行和出箱行在同一个事务里用 SQL 写入。随后的投递使用 {@link HttpEventStandIn}，也就是第一版的跨进程替身。
 * 这条路径上没有实体模型。
 */
public final class JdbcTaskMessagePort implements TaskMessagePort {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final IdempotencyPort idempotencyPort;
    private final AuditPort auditPort;
    private final DistributedLockPort lock;
    private final HttpEventStandIn standIn;
    private final OpenTelemetry openTelemetry;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JdbcTaskMessagePort(
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            IdempotencyPort idempotencyPort,
            AuditPort auditPort,
            DistributedLockPort lock,
            HttpEventStandIn standIn,
            OpenTelemetry openTelemetry,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.idempotencyPort = idempotencyPort;
        this.auditPort = auditPort;
        this.lock = lock;
        this.standIn = standIn;
        this.openTelemetry = openTelemetry;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public TaskRecord submit(TaskCommand command) {
        TaskLifecycle.validate(command);
        Tracer tracer = openTelemetry.getTracer("platform-app");
        Span span = tracer.spanBuilder("submit-task").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            String lockName = "task-submit/" + command.tenantId() + "/" + command.idempotencyToken();
            if (!lock.tryAcquire(lockName, command.idempotencyToken(), Duration.ofSeconds(15))) {
                throw new SubmitLockHeld();
            }
            try {
                FirstSubmission inserted = transaction.execute(status -> insertOrReplay(command, span.getSpanContext().getTraceId()));
                if (inserted == null) {
                    throw new IllegalStateException("task submission did not return a row");
                }
                if (!inserted.first()) {
                    return inserted.task();
                }
                StandInDelivery delivery = standIn.deliver(inserted.notice(), false);
                transaction.executeWithoutResult(status -> recordDelivery(inserted.notice().eventId(), delivery.result()));
                return loadTask(inserted.task().taskId());
            } finally {
                lock.release(lockName, command.idempotencyToken());
            }
        } finally {
            span.end();
        }
    }

    @Override
    public TaskRecord retry(String tenantId, String taskId, boolean stepSucceeded) {
        return transaction.execute(status -> {
            TaskRecord current = loadTask(taskId);
            TaskLifecycle.requireSameTenant(tenantId, current.tenantId());
            if (current.taskKind() != TaskKind.DETERMINISTIC) {
                throw new IllegalArgumentException("only a deterministic task is retried as a step");
            }
            if (current.taskState() != TaskState.PENDING) {
                throw new IllegalArgumentException("only a pending task can be retried");
            }
            int attemptCount = current.attemptCount() + 1;
            TaskState next = TaskLifecycle.stateAfterStep(
                    TaskKind.DETERMINISTIC, null, stepSucceeded, attemptCount, current.maxAttempt());
            String failureReason = next == TaskState.DEAD ? "step failed" : null;
            jdbc.update(
                    """
                    UPDATE platform_task
                    SET task_state = ?, attempt_count = ?, failure_reason = ?
                    WHERE task_id = ?
                    """,
                    next.name(),
                    attemptCount,
                    failureReason,
                    taskId);
            if (next == TaskState.DEAD) {
                insertDeadLetter(current.tenantId(), OriginKind.TASK, taskId, failureReason);
            }
            return loadTask(taskId);
        });
    }

    @Override
    public TaskRecord confirm(String tenantId, String taskId) {
        return transaction.execute(status -> {
            TaskRecord current = loadTask(taskId);
            TaskLifecycle.requireSameTenant(tenantId, current.tenantId());
            if (current.taskKind() != TaskKind.NON_DETERMINISTIC) {
                throw new IllegalArgumentException("only a non-deterministic task has human confirmation");
            }
            TaskState next = TaskLifecycle.stateAfterConfirmation(current.taskState(), HumanConfirmation.CONFIRMED);
            jdbc.update(
                    """
                    UPDATE platform_task
                    SET human_confirmation = ?, task_state = ?, failure_reason = NULL
                    WHERE task_id = ?
                    """,
                    HumanConfirmation.CONFIRMED.name(),
                    next.name(),
                    taskId);
            return loadTask(taskId);
        });
    }

    private FirstSubmission insertOrReplay(TaskCommand command, String traceId) {
        String fingerprint = fingerprint(command);
        Optional<IdempotencyClaim> existing = idempotencyPort.find(command.tenantId(), command.idempotencyToken());
        if (existing.isPresent()) {
            if (!existing.get().requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyConflict();
            }
            return new FirstSubmission(loadTask(existing.get().taskId()), null, false);
        }
        ensureTenant(command.tenantId());
        Instant now = clock.instant();
        String taskId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        int attemptCount = 1;
        TaskState state = TaskLifecycle.stateAfterStep(
                command.taskKind(),
                command.humanConfirmation(),
                command.stepSucceeded(),
                attemptCount,
                TaskLifecycle.DEFAULT_MAX_ATTEMPT);
        String failureReason = state == TaskState.DEAD ? "step failed" : null;
        jdbc.update(
                """
                INSERT INTO platform_task (
                    task_id, tenant_id, task_kind, task_state, step_name, attempt_count, max_attempt,
                    model_id, input_digest, human_confirmation, failure_reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                taskId,
                command.tenantId(),
                command.taskKind().name(),
                state.name(),
                command.stepName(),
                attemptCount,
                TaskLifecycle.DEFAULT_MAX_ATTEMPT,
                command.modelId(),
                command.inputDigest(),
                command.humanConfirmation() == null ? null : command.humanConfirmation().name(),
                failureReason,
                PlatformTables.timestamp(now));
        TaskRecordedNotice notice = new TaskRecordedNotice(eventId, command.tenantId(), taskId, command.stepName());
        jdbc.update(
                """
                INSERT INTO outbox_event (
                    event_id, tenant_id, event_name, event_body, event_state, trace_id,
                    attempt_count, failure_reason, occurred_at, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                eventId,
                command.tenantId(),
                TaskRecordedNotice.EVENT_NAME,
                writeNotice(notice),
                OutboxState.PENDING.name(),
                traceId,
                0,
                null,
                PlatformTables.timestamp(now),
                null);
        idempotencyPort.insert(new IdempotencyClaim(
                command.tenantId(), command.idempotencyToken(), fingerprint, taskId));
        auditPort.record(new AuditEntry(
                UUID.randomUUID().toString(),
                command.tenantId(),
                command.actorIdentityId(),
                "submit-task",
                state == TaskState.DEAD ? AuditOutcome.FAILED : AuditOutcome.ALLOWED,
                now));
        if (state == TaskState.DEAD) {
            insertDeadLetter(command.tenantId(), OriginKind.TASK, taskId, failureReason);
        }
        return new FirstSubmission(loadTask(taskId), notice, true);
    }

    private void recordDelivery(String eventId, DeliveryResult result) {
        OutboxEvent current = loadOutbox(eventId);
        int attemptCount = current.attemptCount() + 1;
        boolean delivered = result.eventState() == OutboxState.PUBLISHED;
        OutboxState next = DeliveryProgress.next(delivered, attemptCount, TaskLifecycle.DEFAULT_MAX_ATTEMPT);
        String failureReason = delivered ? null : clip(result.failureReason());
        Instant publishedAt = next == OutboxState.PUBLISHED ? clock.instant() : null;
        jdbc.update(
                """
                UPDATE outbox_event
                SET event_state = ?, attempt_count = ?, failure_reason = ?, published_at = ?
                WHERE event_id = ?
                """,
                next.name(),
                attemptCount,
                failureReason,
                PlatformTables.timestamp(publishedAt),
                eventId);
        if (next == OutboxState.DEAD) {
            insertDeadLetter(current.tenantId(), OriginKind.OUTBOX_EVENT, eventId,
                    failureReason == null ? "delivery failed" : failureReason);
        }
    }

    private void ensureTenant(String tenantId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Long.class, tenantId);
        if (count != null && count > 0) {
            return;
        }
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, ?)",
                tenantId,
                tenantId,
                TenantState.ACTIVE.name());
    }

    private void insertDeadLetter(String tenantId, OriginKind originKind, String originId, String failureReason) {
        jdbc.update(
                """
                INSERT INTO dead_letter
                    (dead_letter_id, tenant_id, origin_kind, origin_id, failure_reason, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                tenantId,
                originKind.name(),
                originId,
                clip(failureReason == null ? "failed" : failureReason),
                PlatformTables.timestamp(clock.instant()));
    }

    private TaskRecord loadTask(String taskId) {
        return jdbc.queryForObject(
                """
                SELECT task_id, tenant_id, task_kind, task_state, step_name, attempt_count, max_attempt,
                       model_id, input_digest, human_confirmation, failure_reason, created_at
                FROM platform_task
                WHERE task_id = ?
                """,
                (row, rowNumber) -> PlatformTables.mapTask(row),
                taskId);
    }

    private OutboxEvent loadOutbox(String eventId) {
        return jdbc.queryForObject(
                """
                SELECT event_id, tenant_id, event_name, event_body, event_state, trace_id,
                       attempt_count, failure_reason, occurred_at, published_at
                FROM outbox_event
                WHERE event_id = ?
                """,
                (row, rowNumber) -> PlatformTables.mapOutbox(row),
                eventId);
    }

    private String writeNotice(TaskRecordedNotice notice) {
        try {
            return objectMapper.writeValueAsString(notice);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("could not write task recorded notice", ex);
        }
    }

    private static String fingerprint(TaskCommand command) {
        String raw = String.join(
                "\n",
                command.tenantId(),
                command.actorIdentityId(),
                command.taskKind().name(),
                command.stepName(),
                Boolean.toString(command.stepSucceeded()),
                nullToEmpty(command.modelId()),
                nullToEmpty(command.inputDigest()),
                command.humanConfirmation() == null ? "" : command.humanConfirmation().name());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String clip(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 512 ? reason : reason.substring(0, 512);
    }

    private record FirstSubmission(TaskRecord task, TaskRecordedNotice notice, boolean first) {
    }
}
