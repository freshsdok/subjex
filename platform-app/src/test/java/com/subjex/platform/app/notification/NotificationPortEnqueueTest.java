package com.subjex.platform.app.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.jdbc.JdbcIdempotencyPort;
import com.subjex.platform.app.jdbc.JdbcTaskMessagePort;
import com.subjex.platform.app.lock.SingleProcessLock;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.delivery.DeliveryAttempt;
import com.subjex.platform.contract.delivery.DeliveryPort;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.notification.NotificationChannel;
import com.subjex.platform.contract.notification.NotificationCommand;
import com.subjex.platform.contract.notification.NotificationCrossTenantDeniedException;
import com.subjex.platform.contract.notification.NotificationPort;
import com.subjex.platform.contract.notification.NotificationReceipt;
import com.subjex.platform.contract.notification.NotificationRecipient;
import com.subjex.platform.contract.notification.NotificationRequested;
import com.subjex.platform.contract.task.DeliveryResult;
import com.subjex.platform.contract.task.OutboxState;
import com.subjex.platform.contract.tenant.TenantMissingException;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NotificationPortEnqueueTest — purpose: enqueue NotificationRequested onto existing outbox_event.
 * Gates: template id + params (no kernel prose); missing/disabled/platform tenant denied (no row);
 * cross-tenant recipient denied; inbox and platform_task untouched; relay leaves the row PENDING.
 * <p>
 * 目的：把 NotificationRequested 写入已有出箱。门禁：模板标识+参数（无内核文案）；缺失/停用/保留租户拒绝且不写行；
 * 跨租户收件人拒绝；不写站内信与 platform_task；现有中继不取走该行。
 */
class NotificationPortEnqueueTest {

    private static final String TENANT_A = "tenant-alpha";
    private static final String TENANT_B = "tenant-bravo";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.systemUTC();

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void domainCallerEnqueuesTemplateNotificationIntoExistingOutbox(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        NotificationReceipt receipt = fx.port.enqueue(approvedOrderCommand(TENANT_A, endUserIn(TENANT_A)));

        assertEquals(NotificationRequested.EVENT_NAME, receipt.eventName());
        assertEquals(TENANT_A, receipt.tenantId());
        assertEquals(OutboxState.PENDING, receipt.eventState());

        Map<String, Object> row = fx.outboxRow(receipt.notificationId());
        assertEquals(TENANT_A, row.get("tenant_id"));
        assertEquals(NotificationRequested.EVENT_NAME, row.get("event_name"));
        assertEquals(OutboxState.PENDING.name(), row.get("event_state"));
        assertEquals(0, ((Number) row.get("attempt_count")).intValue());

        JsonNode body = MAPPER.readTree((String) row.get("event_body"));
        assertEquals("order.approved", body.get("templateId").asText());
        assertEquals("SMS", body.get("channel").asText());
        assertEquals("ORD-41", body.get("parameters").get("orderId").asText());
        assertEquals("end-user-ada", body.get("recipient").get("subjectId").asText());
        assertFalse(body.has("title"), "kernel must not add a title field");
        assertFalse(body.has("body"), "kernel must not add a body/prose field");
        assertFalse(body.has("message"), "kernel must not add a message/prose field");
        String raw = (String) row.get("event_body");
        assertFalse(raw.contains("Your order is approved"), "body must not carry hardcoded prose");
        assertFalse(raw.contains("您的订单已批准"), "body must not carry hardcoded prose");

        assertEquals(0, fx.count("platform_task"));
        assertEquals(0, fx.inboxRows());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void missingTenantIsDeniedAndWritesNoRow(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        assertThrows(
                TenantMissingException.class,
                () -> fx.port.enqueue(approvedOrderCommand(null, endUserIn(TENANT_A))));
        assertThrows(
                TenantMissingException.class,
                () -> fx.port.enqueue(approvedOrderCommand("  ", endUserIn(TENANT_A))));
        assertEquals(0, fx.count("outbox_event"));
        assertEquals(0, fx.inboxRows());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void unknownAndDisabledAndReservedPlatformTenantsAreDenied(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        fx.jdbc.update(
                "UPDATE tenant SET tenant_state = 'SUSPENDED' WHERE tenant_id = ?", TENANT_B);

        assertThrows(
                TenantNotFoundException.class,
                () -> fx.port.enqueue(approvedOrderCommand("tenant-missing", endUserIn("tenant-missing"))));
        assertThrows(
                TenantDisabledException.class,
                () -> fx.port.enqueue(approvedOrderCommand(TENANT_B, endUserIn(TENANT_B))));
        assertThrows(
                IllegalArgumentException.class,
                () -> fx.port.enqueue(approvedOrderCommand("platform", endUserIn("platform"))));

        assertEquals(0, fx.count("outbox_event"));
        assertEquals(0, fx.inboxRows());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void recipientInOtherTenantCannotBeUsedUnderSendTenant(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);

        assertThrows(
                NotificationCrossTenantDeniedException.class,
                () -> fx.port.enqueue(approvedOrderCommand(
                        TENANT_A,
                        NotificationRecipient.ofDestination("+8613800000000", TENANT_B))));
        assertThrows(
                NotificationCrossTenantDeniedException.class,
                () -> fx.port.enqueue(approvedOrderCommand(
                        TENANT_A,
                        NotificationRecipient.ofSubject(
                                InboxRecipientRealm.END_USER, "end-user-only-in-b"))));

        assertEquals(0, fx.count("outbox_event"));
        assertEquals(0, fx.inboxRows());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void existingRelayLeavesNotificationRequestedPending(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        NotificationReceipt receipt =
                fx.port.enqueue(approvedOrderCommand(TENANT_A, NotificationRecipient.ofDestination("ada@example.test")));

        RecordingDeliveryPort delivery = new RecordingDeliveryPort();
        JdbcTaskMessagePort tasks = new JdbcTaskMessagePort(
                fx.jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(fx.source)),
                new JdbcIdempotencyPort(fx.jdbc, CLOCK),
                new JdbcAuditPort(fx.jdbc),
                new SingleProcessLock(CLOCK),
                delivery,
                OpenTelemetry.noop(),
                MAPPER,
                CLOCK);
        assertEquals(0, tasks.relayPending(10));
        assertEquals(0, delivery.calls.get());
        Map<String, Object> row = fx.outboxRow(receipt.notificationId());
        assertEquals(OutboxState.PENDING.name(), row.get("event_state"));
        assertEquals(0, ((Number) row.get("attempt_count")).intValue());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void oversizedParameterMapIsRejectedWithoutWritingARow(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        String huge = "x".repeat(JdbcNotificationPort.MAX_EVENT_BODY_CHARS);
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> fx.port.enqueue(new NotificationCommand(
                        TENANT_A,
                        "order.approved",
                        NotificationChannel.EMAIL,
                        NotificationRecipient.ofDestination("ada@example.test"),
                        Map.of("note", huge))));
        assertTrue(ex.getMessage().contains("4000"));
        assertEquals(0, fx.count("outbox_event"));
    }

    private static NotificationCommand approvedOrderCommand(String tenantId, NotificationRecipient recipient) {
        return new NotificationCommand(
                tenantId,
                "order.approved",
                NotificationChannel.SMS,
                recipient,
                Map.of("orderId", "ORD-41"));
    }

    private static NotificationRecipient endUserIn(String tenantId) {
        return NotificationRecipient.ofSubject(InboxRecipientRealm.END_USER, "end-user-ada", tenantId);
    }

    private static final class RecordingDeliveryPort implements DeliveryPort {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> names = new ArrayList<>();

        @Override
        public DeliveryAttempt deliver(
                com.subjex.platform.contract.task.OutboxEvent event, boolean failureRequested) {
            calls.incrementAndGet();
            names.add(event.eventName());
            return new DeliveryAttempt(DeliveryResult.published(), "trace-test");
        }
    }

    private static final class Fixture {
        private final DataSource source;
        private final JdbcTemplate jdbc;
        private final NotificationPort port;

        private Fixture(DataSource source, JdbcTemplate jdbc, NotificationPort port) {
            this.source = source;
            this.jdbc = jdbc;
            this.port = port;
        }

        private static Fixture open(H2PlatformTables.Mode mode) {
            DataSource source = H2PlatformTables.migrated(mode);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            seedTenant(jdbc, TENANT_A);
            seedTenant(jdbc, TENANT_B);
            seedEndUser(jdbc, "acct-ada", "end-user-ada", "id-ada", TENANT_A);
            seedEndUser(jdbc, "acct-bev", "end-user-only-in-b", "id-bev", TENANT_B);
            return new Fixture(source, jdbc, new JdbcNotificationPort(jdbc, MAPPER, CLOCK));
        }

        private static void seedTenant(JdbcTemplate jdbc, String tenantId) {
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    tenantId,
                    tenantId);
        }

        private static void seedEndUser(
                JdbcTemplate jdbc, String accountId, String subjectId, String identityId, String tenantId) {
            jdbc.update(
                    "INSERT INTO account (account_id, login_name, account_state) VALUES (?, ?, 'ACTIVE')",
                    accountId,
                    "login-" + accountId);
            jdbc.update(
                    "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?, ?, 'PERSON')",
                    subjectId,
                    subjectId);
            jdbc.update(
                    """
                    INSERT INTO subject_identity (identity_id, account_id, subject_id, tenant_id, identity_state)
                    VALUES (?, ?, ?, ?, 'ACTIVE')
                    """,
                    identityId,
                    accountId,
                    subjectId,
                    tenantId);
        }

        private Map<String, Object> outboxRow(String eventId) {
            return jdbc.queryForMap("SELECT * FROM outbox_event WHERE event_id = ?", eventId);
        }

        private int count(String table) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            return n == null ? 0 : n;
        }

        private int inboxRows() {
            return count("inbox_message") + count("inbox_entry") + count("inbox_fanout_job");
        }
    }
}
