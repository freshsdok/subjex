package com.subjex.platform.app.notification;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.subjex.platform.app.jdbc.PlatformTables;
import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.contract.audit.AuditEntry;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.audit.AuditPort;
import com.subjex.platform.contract.lock.DistributedLockPort;
import com.subjex.platform.contract.notification.NotificationChannel;
import com.subjex.platform.contract.notification.NotificationChannelAdapter;
import com.subjex.platform.contract.notification.NotificationDispatcher;
import com.subjex.platform.contract.notification.NotificationRecipient;
import com.subjex.platform.contract.notification.NotificationRequested;
import com.subjex.platform.contract.task.DeadLetter;
import com.subjex.platform.contract.task.DeliveryProgress;
import com.subjex.platform.contract.task.OriginKind;
import com.subjex.platform.contract.task.OutboxEvent;
import com.subjex.platform.contract.task.OutboxState;
import com.subjex.platform.contract.task.TaskLifecycle;
import com.subjex.platform.contract.tenant.TenantState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcNotificationDispatcher — JDBC 通知调度器：选取 PENDING 的 {@link NotificationRequested}，调用渠道适配器，复用出箱重试与死信。
 * <p>
 * Does not call {@code DeliveryPort} and does not share {@code OutboxRelay}'s lock, so TaskRecorded
 * frames are never used as the hop before an adapter. Max attempts follow {@link TaskLifecycle#DEFAULT_MAX_ATTEMPT}.
 * 不调用 {@code DeliveryPort}，也不与 {@code OutboxRelay} 共用锁，因此不会把 TaskRecorded 帧当作适配器前的必经一跳。
 * 最大尝试次数沿用 {@link TaskLifecycle#DEFAULT_MAX_ATTEMPT}。
 */
public final class JdbcNotificationDispatcher implements NotificationDispatcher {

    static final String LOCK_NAME = "notification-dispatch";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final DistributedLockPort lock;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    /**
     * Audit action for a channel delivery attempt — 渠道投递尝试的审计动作。
     */
    public static final String SEND_ACTION = "notification.channel.send";

    /**
     * System actor for in-process dispatch — 进程内调度的系统操作者。
     */
    public static final String SEND_AUDIT_ACTOR = "notification-dispatcher";

    private final Map<NotificationChannel, NotificationChannelAdapter> adapters;
    private final AuditPort audit;
    private final String ownerToken;

    public JdbcNotificationDispatcher(
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            DistributedLockPort lock,
            ObjectMapper objectMapper,
            Clock clock,
            List<NotificationChannelAdapter> adapters,
            AuditPort audit) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.lock = Objects.requireNonNull(lock, "lock");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.adapters = indexAdapters(adapters);
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ownerToken = UUID.randomUUID().toString();
    }

    private static Map<NotificationChannel, NotificationChannelAdapter> indexAdapters(
            List<NotificationChannelAdapter> adapters) {
        Objects.requireNonNull(adapters, "adapters");
        EnumMap<NotificationChannel, NotificationChannelAdapter> map = new EnumMap<>(NotificationChannel.class);
        for (NotificationChannelAdapter adapter : adapters) {
            Objects.requireNonNull(adapter, "adapter");
            NotificationChannelAdapter previous = map.put(adapter.channel(), adapter);
            if (previous != null) {
                throw new IllegalStateException("duplicate notification adapter for " + adapter.channel());
            }
        }
        for (NotificationChannel channel : NotificationChannel.values()) {
            map.putIfAbsent(channel, new FailClosedNotificationAdapter(channel));
        }
        return Map.copyOf(map);
    }

    @Override
    public int dispatchPending(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batch size must be at least 1");
        }
        if (!lock.tryAcquire(LOCK_NAME, ownerToken, Duration.ofSeconds(25))) {
            return 0;
        }
        try {
            int published = 0;
            for (OutboxEvent stored : listPending(batchSize)) {
                if (dispatchRow(stored) == OutboxState.PUBLISHED) {
                    published++;
                }
            }
            return published;
        } finally {
            lock.release(LOCK_NAME, ownerToken);
        }
    }

    @Override
    public OutboxEvent dispatchOne(String eventId) {
        String id = requireId(eventId, "eventId");
        if (!lock.tryAcquire(LOCK_NAME, ownerToken, Duration.ofSeconds(25))) {
            throw new IllegalStateException("notification dispatch lock held");
        }
        try {
            OutboxEvent stored = loadOutbox(id);
            if (!NotificationRequested.EVENT_NAME.equals(stored.eventName())) {
                throw new IllegalArgumentException("outbox-event-not-notification");
            }
            if (stored.eventState() != OutboxState.PENDING) {
                throw new IllegalArgumentException("outbox-event-not-pending");
            }
            dispatchRow(stored);
            return loadOutbox(id);
        } finally {
            lock.release(LOCK_NAME, ownerToken);
        }
    }

    @Override
    public OutboxEvent replayDeadLetter(String deadLetterId) {
        String id = requireId(deadLetterId, "deadLetterId");
        DeadLetter letter = loadDeadLetter(id);
        if (letter.discardedAt() != null) {
            throw new IllegalArgumentException("dead-letter-discarded");
        }
        if (letter.originKind() != OriginKind.OUTBOX_EVENT) {
            throw new IllegalArgumentException("dead-letter-not-outbox");
        }
        OutboxEvent current = loadOutbox(letter.originId());
        if (!NotificationRequested.EVENT_NAME.equals(current.eventName())) {
            throw new IllegalArgumentException("dead-letter-not-notification");
        }
        transaction.executeWithoutResult(status -> {
            int updated = jdbc.update(
                    """
                    UPDATE outbox_event
                    SET event_state = ?, attempt_count = 0, failure_reason = NULL, published_at = NULL
                    WHERE event_id = ? AND event_state = ?
                    """,
                    OutboxState.PENDING.name(),
                    letter.originId(),
                    OutboxState.DEAD.name());
            if (updated != 1) {
                throw new IllegalArgumentException("outbox-event-not-dead");
            }
            jdbc.update("DELETE FROM dead_letter WHERE dead_letter_id = ?", id);
        });
        return dispatchOne(letter.originId());
    }

    /**
     * Run one adapter call and record success or a retry/dead-letter outcome on the existing row.
     * 调用一次适配器，并在已有出箱行上记录成功或重试/死信结果。
     */
    private OutboxState dispatchRow(OutboxEvent stored) {
        SendAttempt attempt = validateAndSend(stored);
        transaction.executeWithoutResult(status -> {
            recordAttempt(stored.eventId(), attempt.delivered(), attempt.failureReason());
            recordSendAttempt(stored, attempt);
        });
        return loadOutbox(stored.eventId()).eventState();
    }

    /**
     * @return a send attempt with a {@code null} failure reason on success
     *         成功时失败原因为 {@code null} 的投递尝试
     */
    private SendAttempt validateAndSend(OutboxEvent stored) {
        NotificationRequested request;
        try {
            request = readBody(stored.eventBody());
        } catch (RuntimeException ex) {
            return SendAttempt.failed(
                    clip(ex.getMessage() == null ? "invalid notification event_body" : ex.getMessage()),
                    AuditOutcome.FAILED,
                    null);
        }
        if (!stored.tenantId().equals(request.tenantId())) {
            return SendAttempt.failed("cross-tenant notification denied", AuditOutcome.REFUSED, request);
        }
        try {
            requireActiveBusinessTenant(stored.tenantId());
        } catch (RuntimeException ex) {
            return SendAttempt.failed(
                    clip(ex.getMessage() == null ? "tenant is not ACTIVE" : ex.getMessage()),
                    AuditOutcome.REFUSED,
                    request);
        }
        NotificationRecipient recipient = request.recipient();
        if (recipient != null
                && recipient.recipientTenantId() != null
                && !stored.tenantId().equals(recipient.recipientTenantId())) {
            return SendAttempt.failed("cross-tenant notification denied", AuditOutcome.REFUSED, request);
        }
        NotificationChannelAdapter adapter = adapters.get(request.channel());
        try {
            adapter.send(request);
            return SendAttempt.ok(request);
        } catch (NotificationAdapterUnconfiguredException ex) {
            return SendAttempt.failed(clip(ex.getMessage()), AuditOutcome.REFUSED, request);
        } catch (NotificationChannelSendFailedException ex) {
            return SendAttempt.failed(NotificationChannelSendFailedException.REASON, AuditOutcome.FAILED, request);
        } catch (RuntimeException ex) {
            return SendAttempt.failed(
                    clip(ex.getMessage() == null ? "notification adapter failed" : ex.getMessage()),
                    AuditOutcome.FAILED,
                    request);
        }
    }

    private void recordSendAttempt(OutboxEvent stored, SendAttempt attempt) {
        audit.record(new AuditEntry(
                UUID.randomUUID().toString(),
                stored.tenantId(),
                SEND_AUDIT_ACTOR,
                SEND_ACTION,
                actionTarget(attempt.request()),
                attempt.outcome(),
                clock.instant()));
    }

    /**
     * Channel, template, and masked recipient only — never the body, parameters, or full destination.
     * 只含渠道、模板与遮蔽后的收件人，不含请求体、参数或完整目的地。
     */
    static String actionTarget(NotificationRequested request) {
        if (request == null) {
            return "unparsed";
        }
        return request.channel().name()
                + ":"
                + request.templateId()
                + ":"
                + maskRecipient(request.recipient());
    }

    /**
     * Mask a destination like the OTP slice; emails keep only the domain.
     * 与验证码切片一样遮蔽目的地；邮件只保留域名。
     */
    static String maskDestination(String destination) {
        if (destination == null || destination.isBlank()) {
            return "****";
        }
        String value = destination.trim();
        int at = value.indexOf('@');
        if (at >= 0 && at < value.length() - 1) {
            return "****@" + value.substring(at + 1);
        }
        if (value.length() <= 4) {
            return "****";
        }
        return "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
    }

    static String maskRecipient(NotificationRecipient recipient) {
        if (recipient == null) {
            return "****";
        }
        if (recipient.destination() != null) {
            return maskDestination(recipient.destination());
        }
        if (recipient.subjectId() != null) {
            return "subject:" + maskDestination(recipient.subjectId());
        }
        return "****";
    }

    private record SendAttempt(String failureReason, AuditOutcome outcome, NotificationRequested request) {
        static SendAttempt ok(NotificationRequested request) {
            return new SendAttempt(null, AuditOutcome.ALLOWED, request);
        }

        static SendAttempt failed(String reason, AuditOutcome outcome, NotificationRequested request) {
            return new SendAttempt(reason, outcome, request);
        }

        boolean delivered() {
            return failureReason == null;
        }
    }

    private void requireActiveBusinessTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenant is missing");
        }
        if (JdbcNotificationPort.RESERVED_TENANT.equals(tenantId)) {
            throw new IllegalArgumentException(
                    "reserved tenant " + JdbcNotificationPort.RESERVED_TENANT + " is not a notification home");
        }
        List<String> states = jdbc.query(
                "SELECT tenant_state FROM tenant WHERE tenant_id = ?",
                (row, n) -> row.getString("tenant_state"),
                tenantId);
        if (states.isEmpty()) {
            throw new IllegalArgumentException("tenant not found: " + tenantId);
        }
        if (!TenantState.ACTIVE.name().equals(states.get(0))) {
            throw new TenantDisabledException(tenantId);
        }
    }

    private void recordAttempt(String eventId, boolean delivered, String failureReason) {
        OutboxEvent current = loadOutbox(eventId);
        int attemptCount = current.attemptCount() + 1;
        OutboxState next = DeliveryProgress.next(delivered, attemptCount, TaskLifecycle.DEFAULT_MAX_ATTEMPT);
        String reason = delivered ? null : clip(failureReason);
        Instant publishedAt = next == OutboxState.PUBLISHED ? clock.instant() : null;
        jdbc.update(
                """
                UPDATE outbox_event
                SET event_state = ?, attempt_count = ?, failure_reason = ?, published_at = ?
                WHERE event_id = ?
                """,
                next.name(),
                attemptCount,
                reason,
                PlatformTables.timestamp(publishedAt),
                eventId);
        if (next == OutboxState.DEAD) {
            jdbc.update(
                    """
                    INSERT INTO dead_letter
                        (dead_letter_id, tenant_id, origin_kind, origin_id, failure_reason, recorded_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    current.tenantId(),
                    OriginKind.OUTBOX_EVENT.name(),
                    eventId,
                    reason == null ? "notification adapter failed" : reason,
                    PlatformTables.timestamp(clock.instant()));
        }
    }

    private List<OutboxEvent> listPending(int batchSize) {
        return jdbc.query(
                """
                SELECT event_id, tenant_id, event_name, event_body, event_state, trace_id,
                       attempt_count, failure_reason, occurred_at, published_at
                FROM outbox_event
                WHERE event_state = ?
                  AND event_name = ?
                ORDER BY occurred_at ASC, event_id ASC
                LIMIT ?
                """,
                (row, n) -> PlatformTables.mapOutbox(row),
                OutboxState.PENDING.name(),
                NotificationRequested.EVENT_NAME,
                batchSize);
    }

    private OutboxEvent loadOutbox(String eventId) {
        List<OutboxEvent> rows = jdbc.query(
                """
                SELECT event_id, tenant_id, event_name, event_body, event_state, trace_id,
                       attempt_count, failure_reason, occurred_at, published_at
                FROM outbox_event
                WHERE event_id = ?
                """,
                (row, n) -> PlatformTables.mapOutbox(row),
                eventId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("outbox-event-not-found");
        }
        return rows.get(0);
    }

    private DeadLetter loadDeadLetter(String deadLetterId) {
        List<DeadLetter> rows = jdbc.query(
                """
                SELECT dead_letter_id, tenant_id, origin_kind, origin_id, failure_reason, recorded_at, discarded_at
                FROM dead_letter
                WHERE dead_letter_id = ?
                """,
                (row, n) -> PlatformTables.mapDeadLetter(row),
                deadLetterId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("dead-letter-not-found");
        }
        return rows.get(0);
    }

    private NotificationRequested readBody(String eventBody) {
        try {
            return objectMapper.readValue(eventBody, NotificationRequested.class);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("could not read notification requested body", ex);
        }
    }

    private static String requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return value.trim();
    }

    private static String clip(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 512 ? reason : reason.substring(0, 512);
    }
}
