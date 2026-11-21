package com.subjex.platform.app.notification;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.subjex.platform.app.jdbc.PlatformTables;
import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.notification.NotificationCommand;
import com.subjex.platform.contract.notification.NotificationCrossTenantDeniedException;
import com.subjex.platform.contract.notification.NotificationPort;
import com.subjex.platform.contract.notification.NotificationReceipt;
import com.subjex.platform.contract.notification.NotificationRecipient;
import com.subjex.platform.contract.notification.NotificationRequested;
import com.subjex.platform.contract.task.OutboxState;
import com.subjex.platform.contract.tenant.TenantMissingException;
import com.subjex.platform.contract.tenant.TenantState;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcNotificationPort — JDBC 通知端口：把 {@link NotificationRequested} 写入已有 {@code outbox_event}。
 * <p>
 * This path does <em>not</em> call {@code TaskMessagePort.submit} and does not insert {@code platform_task}
 * or inbox rows. Verification codes stay on {@code VerificationCodeSender}.
 * 本路径不调用 {@code TaskMessagePort.submit}，不写 {@code platform_task} 或站内信。验证码仍走 {@code VerificationCodeSender}。
 * <p>
 * The row stays {@code PENDING} for {@link com.subjex.platform.contract.notification.NotificationDispatcher}.
 * Existing {@code OutboxRelay} / {@code relayPending} still skip {@code NotificationRequested}.
 * 行保持 {@code PENDING}，由 {@link com.subjex.platform.contract.notification.NotificationDispatcher} 调度。
 * 现有出箱中继仍然跳过 {@code NotificationRequested}。
 */
public final class JdbcNotificationPort implements NotificationPort {

    /**
     * Reserved operator-home tenant; never a default business send tenant.
     * 保留的操作员归属租户；绝不可作为默认业务发信租户。
     */
    public static final String RESERVED_TENANT = "platform";

    /**
     * {@code outbox_event.event_body} VARCHAR(4000). Fail closed rather than invent a payload store.
     * {@code outbox_event.event_body} 为 VARCHAR(4000)。超限失败关闭，不另起载荷库。
     */
    public static final int MAX_EVENT_BODY_CHARS = 4000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JdbcNotificationPort(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public NotificationReceipt enqueue(NotificationCommand command) {
        Objects.requireNonNull(command, "command");
        String tenantId = requireActiveBusinessTenant(command.tenantId());
        refuseCrossTenant(tenantId, command.recipient());
        String eventId = UUID.randomUUID().toString();
        NotificationRequested body = NotificationRequested.from(eventId, tenantId, command);
        String json = writeBody(body);
        if (json.length() > MAX_EVENT_BODY_CHARS) {
            throw new IllegalArgumentException(
                    "notification event_body exceeds " + MAX_EVENT_BODY_CHARS + " characters");
        }
        // trace_id is NOT NULL; reuse eventId when no publisher span exists yet (WP5-2 dispatcher).
        // trace_id 非空；尚无发布跨度时复用 eventId（WP5-2 调度器再补）。
        jdbc.update(
                """
                INSERT INTO outbox_event (
                    event_id, tenant_id, event_name, event_body, event_state, trace_id,
                    attempt_count, failure_reason, occurred_at, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                eventId,
                tenantId,
                NotificationRequested.EVENT_NAME,
                json,
                OutboxState.PENDING.name(),
                eventId,
                0,
                null,
                PlatformTables.timestamp(clock.instant()),
                null);
        // WP5-2: leave PENDING; do not call DeliveryPort or channel adapters here.
        // WP5-2：保持 PENDING；此处不调用 DeliveryPort 或渠道适配器。
        return new NotificationReceipt(
                eventId, tenantId, NotificationRequested.EVENT_NAME, OutboxState.PENDING);
    }

    /**
     * Explicit business tenant, ACTIVE, never reserved {@code platform}, never auto-created.
     * 必须是明确的业务租户且为 ACTIVE，绝不用保留租户 {@code platform}，也绝不自动建户。
     */
    private String requireActiveBusinessTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        String id = tenantId.trim();
        if (RESERVED_TENANT.equals(id)) {
            throw new IllegalArgumentException(
                    "reserved tenant " + RESERVED_TENANT + " is not a notification home");
        }
        List<String> states = jdbc.query(
                "SELECT tenant_state FROM tenant WHERE tenant_id = ?",
                (row, n) -> row.getString("tenant_state"),
                id);
        if (states.isEmpty()) {
            throw new TenantNotFoundException(id);
        }
        if (!TenantState.ACTIVE.name().equals(states.get(0))) {
            throw new TenantDisabledException(id);
        }
        return id;
    }

    /**
     * Deny a recipient stamped with, or only belonging to, another business tenant.
     * 拒绝带有其他业务租户戳、或仅属于其他业务租户的收件人。
     */
    private void refuseCrossTenant(String tenantId, NotificationRecipient recipient) {
        String stamped = recipient.recipientTenantId();
        if (stamped != null && !tenantId.equals(stamped)) {
            throw new NotificationCrossTenantDeniedException(tenantId, stamped);
        }
        if (recipient.subjectId() == null || recipient.realm() != InboxRecipientRealm.END_USER) {
            return;
        }
        List<String> homes = jdbc.query(
                "SELECT tenant_id FROM subject_identity WHERE subject_id = ?",
                (row, n) -> row.getString("tenant_id"),
                recipient.subjectId());
        boolean inSendTenant = homes.stream().anyMatch(tenantId::equals);
        String otherHome = homes.stream()
                .filter(home -> !tenantId.equals(home) && !RESERVED_TENANT.equals(home))
                .findFirst()
                .orElse(null);
        if (otherHome != null && !inSendTenant) {
            throw new NotificationCrossTenantDeniedException(tenantId, otherHome);
        }
    }

    private String writeBody(NotificationRequested body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException ex) {
            throw new IllegalStateException("could not write notification requested body", ex);
        }
    }
}
