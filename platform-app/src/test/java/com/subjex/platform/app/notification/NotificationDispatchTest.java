package com.subjex.platform.app.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import tools.jackson.databind.ObjectMapper;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.app.jdbc.JdbcIdempotencyPort;
import com.subjex.platform.app.jdbc.JdbcTaskMessagePort;
import com.subjex.platform.app.lock.SingleProcessLock;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.delivery.DeliveryAttempt;
import com.subjex.platform.contract.delivery.DeliveryPort;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.notification.NotificationChannel;
import com.subjex.platform.contract.notification.NotificationChannelAdapter;
import com.subjex.platform.contract.notification.NotificationCommand;
import com.subjex.platform.contract.notification.NotificationCrossTenantDeniedException;
import com.subjex.platform.contract.notification.NotificationDispatcher;
import com.subjex.platform.contract.notification.NotificationPort;
import com.subjex.platform.contract.notification.NotificationReceipt;
import com.subjex.platform.contract.notification.NotificationRecipient;
import com.subjex.platform.contract.notification.NotificationRequested;
import com.subjex.platform.contract.task.DeliveryResult;
import com.subjex.platform.contract.task.OriginKind;
import com.subjex.platform.contract.task.OutboxEvent;
import com.subjex.platform.contract.task.OutboxState;
import com.subjex.platform.contract.task.TaskLifecycle;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
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
 * NotificationDispatchTest — purpose: dispatch NotificationRequested via mock channel adapters.
 * Gates: SMS/email/WeChat sinks see template id + params; forced failure retries then dead-letters;
 * replay can succeed; OutboxRelay does not steal these rows; cross-tenant enqueue still denied.
 * <p>
 * 目的：经模拟渠道适配器调度 NotificationRequested。门禁：三渠道接收槽可见模板标识与参数；强制失败会重试并进入死信；
 * 重放可成功；OutboxRelay 不夺走这些行；跨租户入队仍被拒绝。
 */
class NotificationDispatchTest {

    private static final String TENANT_A = "tenant-alpha";
    private static final String TENANT_B = "tenant-bravo";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.systemUTC();

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void dispatchDeliversToMockSmsEmailAndWeChatSinks(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        NotificationReceipt sms = fx.port.enqueue(command(NotificationChannel.SMS, "order.approved", "ORD-41"));
        NotificationReceipt email = fx.port.enqueue(command(NotificationChannel.EMAIL, "order.shipped", "ORD-42"));
        NotificationReceipt wechat =
                fx.port.enqueue(command(NotificationChannel.WECHAT_TEMPLATE, "order.ready", "ORD-43"));

        assertEquals(3, fx.dispatcher.dispatchPending(10));

        assertEquals("order.approved", only(fx.sink.sent(NotificationChannel.SMS)).templateId());
        assertEquals("ORD-41", only(fx.sink.sent(NotificationChannel.SMS)).parameters().get("orderId"));
        assertEquals("order.shipped", only(fx.sink.sent(NotificationChannel.EMAIL)).templateId());
        assertEquals("ORD-42", only(fx.sink.sent(NotificationChannel.EMAIL)).parameters().get("orderId"));
        assertEquals("order.ready", only(fx.sink.sent(NotificationChannel.WECHAT_TEMPLATE)).templateId());
        assertEquals("ORD-43", only(fx.sink.sent(NotificationChannel.WECHAT_TEMPLATE)).parameters().get("orderId"));

        assertEquals(OutboxState.PUBLISHED.name(), fx.stateOf(sms.notificationId()));
        assertEquals(OutboxState.PUBLISHED.name(), fx.stateOf(email.notificationId()));
        assertEquals(OutboxState.PUBLISHED.name(), fx.stateOf(wechat.notificationId()));
        assertEquals(0, fx.inboxRows());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void forcedAdapterFailureIsRetriedAndBecomesDeadLetter(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        NotificationReceipt receipt = fx.port.enqueue(command(NotificationChannel.SMS, "order.approved", "ORD-41"));
        fx.sink.failNext(TaskLifecycle.DEFAULT_MAX_ATTEMPT);

        assertEquals(0, fx.dispatcher.dispatchPending(10));
        assertEquals(OutboxState.PENDING.name(), fx.stateOf(receipt.notificationId()));
        assertEquals(1, fx.attemptOf(receipt.notificationId()));

        assertEquals(0, fx.dispatcher.dispatchPending(10));
        assertEquals(OutboxState.PENDING.name(), fx.stateOf(receipt.notificationId()));
        assertEquals(2, fx.attemptOf(receipt.notificationId()));

        assertEquals(0, fx.dispatcher.dispatchPending(10));
        assertEquals(OutboxState.DEAD.name(), fx.stateOf(receipt.notificationId()));
        assertEquals(TaskLifecycle.DEFAULT_MAX_ATTEMPT, fx.attemptOf(receipt.notificationId()));
        assertEquals("forced mock adapter failure", fx.failureOf(receipt.notificationId()));
        assertEquals(1, fx.deadLetterCount(receipt.notificationId()));
        assertEquals(OriginKind.OUTBOX_EVENT.name(), fx.deadLetterOriginKind(receipt.notificationId()));
        assertTrue(fx.sink.sent().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void replayAfterDeadLetterCanSucceed(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        NotificationReceipt receipt = fx.port.enqueue(command(NotificationChannel.EMAIL, "order.approved", "ORD-41"));
        fx.sink.failNext(TaskLifecycle.DEFAULT_MAX_ATTEMPT);
        for (int i = 0; i < TaskLifecycle.DEFAULT_MAX_ATTEMPT; i++) {
            fx.dispatcher.dispatchPending(10);
        }
        String deadLetterId = fx.deadLetterId(receipt.notificationId());

        OutboxEvent after = fx.dispatcher.replayDeadLetter(deadLetterId);
        assertEquals(OutboxState.PUBLISHED, after.eventState());
        assertEquals(0, fx.deadLetterCount(receipt.notificationId()));
        assertEquals(1, fx.sink.sent(NotificationChannel.EMAIL).size());
        assertEquals("order.approved", only(fx.sink.sent()).templateId());
        assertEquals("ORD-41", only(fx.sink.sent()).parameters().get("orderId"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void existingRelayDoesNotStealNotificationRows(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        NotificationReceipt receipt = fx.port.enqueue(command(NotificationChannel.SMS, "order.approved", "ORD-41"));
        RecordingDeliveryPort delivery = new RecordingDeliveryPort();
        JdbcTaskMessagePort tasks = fx.taskPort(delivery);

        assertEquals(0, tasks.relayPending(10));
        assertEquals(0, delivery.calls.get());
        assertEquals(OutboxState.PENDING.name(), fx.stateOf(receipt.notificationId()));
        assertEquals(0, fx.attemptOf(receipt.notificationId()));
        assertTrue(fx.sink.sent().isEmpty());

        assertEquals(1, fx.dispatcher.dispatchPending(10));
        assertEquals(OutboxState.PUBLISHED.name(), fx.stateOf(receipt.notificationId()));
        assertEquals(1, fx.sink.sent().size());
        assertEquals(0, delivery.calls.get());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void crossTenantEnqueueStillDeniedAndNothingIsDispatched(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        assertThrows(
                NotificationCrossTenantDeniedException.class,
                () -> fx.port.enqueue(new NotificationCommand(
                        TENANT_A,
                        "order.approved",
                        NotificationChannel.SMS,
                        NotificationRecipient.ofDestination("+8613800000000", TENANT_B),
                        Map.of("orderId", "ORD-41"))));
        assertEquals(0, fx.dispatcher.dispatchPending(10));
        assertEquals(0, fx.count("outbox_event"));
        assertTrue(fx.sink.sent().isEmpty());
        assertEquals(0, fx.inboxRows());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void unconfiguredAdapterFailsClosedIntoDeadLetter(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(
                mode, List.of(new FailClosedNotificationAdapter(NotificationChannel.SMS)));
        NotificationReceipt receipt = fx.port.enqueue(command(NotificationChannel.SMS, "order.approved", "ORD-41"));
        for (int i = 0; i < TaskLifecycle.DEFAULT_MAX_ATTEMPT; i++) {
            fx.dispatcher.dispatchPending(10);
        }
        assertEquals(OutboxState.DEAD.name(), fx.stateOf(receipt.notificationId()));
        assertTrue(fx.failureOf(receipt.notificationId()).contains("SMS"));
        assertEquals(1, fx.deadLetterCount(receipt.notificationId()));
        assertTrue(fx.sink.sent().isEmpty());
        List<Map<String, Object>> audits = fx.sendAuditRows();
        assertEquals(TaskLifecycle.DEFAULT_MAX_ATTEMPT, audits.size());
        for (Map<String, Object> row : audits) {
            assertEquals(AuditOutcome.REFUSED.name(), row.get("outcome"));
            assertEquals(
                    "SMS:order.approved:subject:" + JdbcNotificationDispatcher.maskDestination("end-user-ada"),
                    row.get("action_target"));
        }
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void dispatchAuditsMockSendWithoutLeakingDestinationOrParameters(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        String phone = "13800138000";
        NotificationReceipt receipt = fx.port.enqueue(new NotificationCommand(
                TENANT_A,
                "order.approved",
                NotificationChannel.SMS,
                NotificationRecipient.ofDestination(phone, TENANT_A),
                Map.of("orderId", "SECRET-ORD")));
        assertEquals(1, fx.dispatcher.dispatchPending(10));
        assertEquals(OutboxState.PUBLISHED.name(), fx.stateOf(receipt.notificationId()));
        List<Map<String, Object>> audits = fx.sendAuditRows();
        assertEquals(1, audits.size());
        Map<String, Object> row = audits.get(0);
        assertEquals(TENANT_A, row.get("tenant_id"));
        assertEquals(JdbcNotificationDispatcher.SEND_AUDIT_ACTOR, row.get("actor_identity_id"));
        assertEquals(AuditOutcome.ALLOWED.name(), row.get("outcome"));
        assertEquals(
                "SMS:order.approved:" + JdbcNotificationDispatcher.maskDestination(phone),
                row.get("action_target"));
        assertFalse(fx.auditLeaks("SECRET-ORD"));
        assertFalse(fx.auditLeaks(phone));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void httpTestDoubleDeliversAndAuditsWithoutAVendorNetwork(H2PlatformTables.Mode mode) {
        RecordingHttpClient client = new RecordingHttpClient();
        NotificationChannelAdapter sms = NotificationChannelAdapterFactory.create(
                NotificationChannel.SMS,
                new String[] {},
                new NotificationChannelSettings(
                        "http", "https://notify.example.invalid/v1/sms", "test-api-key", "Subjex"),
                client,
                null);
        Fixture fx = Fixture.open(mode, List.of(sms));
        String phone = "13800138000";
        NotificationReceipt receipt = fx.port.enqueue(new NotificationCommand(
                TENANT_A,
                "order.approved",
                NotificationChannel.SMS,
                NotificationRecipient.ofDestination(phone, TENANT_A),
                Map.of("orderId", "SECRET-ORD")));
        assertEquals(1, fx.dispatcher.dispatchPending(10));
        assertEquals(OutboxState.PUBLISHED.name(), fx.stateOf(receipt.notificationId()));
        assertEquals(1, client.posts);
        assertEquals("https://notify.example.invalid/v1/sms", client.endpoint);
        assertEquals("test-api-key", client.apiKey);
        assertTrue(client.jsonBody.contains(phone));
        assertTrue(client.jsonBody.contains("SECRET-ORD"));
        Map<String, Object> row = fx.sendAuditRows().get(0);
        assertEquals(AuditOutcome.ALLOWED.name(), row.get("outcome"));
        assertEquals(
                "SMS:order.approved:" + JdbcNotificationDispatcher.maskDestination(phone),
                row.get("action_target"));
        assertFalse(fx.auditLeaks("SECRET-ORD"));
        assertFalse(fx.auditLeaks(phone));
        assertFalse(fx.auditLeaks("test-api-key"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void incompleteHttpAdapterFailsClosedAndDoesNotUseMock(H2PlatformTables.Mode mode) {
        NotificationChannelAdapter sms = NotificationChannelAdapterFactory.create(
                NotificationChannel.SMS,
                new String[] {"local"},
                new NotificationChannelSettings("http", "https://notify.example.invalid/v1/sms", "", ""),
                new RecordingHttpClient(),
                new RecordingNotificationSink());
        assertTrue(sms instanceof FailClosedNotificationAdapter);
        Fixture fx = Fixture.open(mode, List.of(sms));
        NotificationReceipt receipt = fx.port.enqueue(command(NotificationChannel.SMS, "order.approved", "ORD-41"));
        fx.dispatcher.dispatchPending(10);
        assertEquals(OutboxState.PENDING.name(), fx.stateOf(receipt.notificationId()));
        assertEquals(AuditOutcome.REFUSED.name(), fx.sendAuditRows().get(0).get("outcome"));
        assertTrue(fx.sink.sent().isEmpty());
    }

    private static NotificationCommand command(NotificationChannel channel, String templateId, String orderId) {
        return new NotificationCommand(
                TENANT_A,
                templateId,
                channel,
                NotificationRecipient.ofSubject(InboxRecipientRealm.END_USER, "end-user-ada", TENANT_A),
                Map.of("orderId", orderId));
    }

    private static NotificationDispatchRecord only(List<NotificationDispatchRecord> records) {
        assertEquals(1, records.size());
        return records.get(0);
    }

    private static final class RecordingDeliveryPort implements DeliveryPort {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public DeliveryAttempt deliver(
                com.subjex.platform.contract.task.OutboxEvent event, boolean failureRequested) {
            calls.incrementAndGet();
            return new DeliveryAttempt(DeliveryResult.published(), "trace-test");
        }
    }

    private static final class Fixture {
        private final DataSource source;
        private final JdbcTemplate jdbc;
        private final NotificationPort port;
        private final NotificationDispatcher dispatcher;
        private final RecordingNotificationSink sink;

        private Fixture(
                DataSource source,
                JdbcTemplate jdbc,
                NotificationPort port,
                NotificationDispatcher dispatcher,
                RecordingNotificationSink sink) {
            this.source = source;
            this.jdbc = jdbc;
            this.port = port;
            this.dispatcher = dispatcher;
            this.sink = sink;
        }

        private static Fixture open(H2PlatformTables.Mode mode) {
            return open(mode, null);
        }

        private static Fixture open(H2PlatformTables.Mode mode, List<NotificationChannelAdapter> adapters) {
            DataSource source = H2PlatformTables.migrated(mode);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            seedTenant(jdbc, TENANT_A);
            seedTenant(jdbc, TENANT_B);
            seedEndUser(jdbc, "acct-ada", "end-user-ada", "id-ada", TENANT_A);
            RecordingNotificationSink sink = new RecordingNotificationSink(CLOCK);
            List<NotificationChannelAdapter> resolved = adapters != null
                    ? adapters
                    : List.of(
                            new MockNotificationAdapter(NotificationChannel.SMS, sink),
                            new MockNotificationAdapter(NotificationChannel.EMAIL, sink),
                            new MockNotificationAdapter(NotificationChannel.WECHAT_TEMPLATE, sink));
            NotificationDispatcher dispatcher = new JdbcNotificationDispatcher(
                    jdbc,
                    new TransactionTemplate(new DataSourceTransactionManager(source)),
                    new SingleProcessLock(CLOCK),
                    MAPPER,
                    CLOCK,
                    resolved,
                    new JdbcAuditPort(jdbc));
            return new Fixture(
                    source, jdbc, new JdbcNotificationPort(jdbc, MAPPER, CLOCK), dispatcher, sink);
        }

        private JdbcTaskMessagePort taskPort(DeliveryPort delivery) {
            return new JdbcTaskMessagePort(
                    jdbc,
                    new TransactionTemplate(new DataSourceTransactionManager(source)),
                    new JdbcIdempotencyPort(jdbc, CLOCK),
                    new JdbcAuditPort(jdbc),
                    new SingleProcessLock(CLOCK),
                    delivery,
                    OpenTelemetry.noop(),
                    MAPPER,
                    CLOCK);
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

        private String stateOf(String eventId) {
            return jdbc.queryForObject(
                    "SELECT event_state FROM outbox_event WHERE event_id = ?", String.class, eventId);
        }

        private int attemptOf(String eventId) {
            Integer n = jdbc.queryForObject(
                    "SELECT attempt_count FROM outbox_event WHERE event_id = ?", Integer.class, eventId);
            return n == null ? 0 : n;
        }

        private String failureOf(String eventId) {
            return jdbc.queryForObject(
                    "SELECT failure_reason FROM outbox_event WHERE event_id = ?", String.class, eventId);
        }

        private List<Map<String, Object>> sendAuditRows() {
            return jdbc.queryForList(
                    """
                    SELECT tenant_id, actor_identity_id, action_name, action_target, outcome
                    FROM audit_entry
                    WHERE action_name = ?
                    ORDER BY occurred_at
                    """,
                    JdbcNotificationDispatcher.SEND_ACTION);
        }

        private boolean auditLeaks(String secret) {
            Integer n = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM audit_entry
                    WHERE action_target LIKE ? OR actor_identity_id LIKE ? OR action_name LIKE ?
                    """,
                    Integer.class,
                    "%" + secret + "%",
                    "%" + secret + "%",
                    "%" + secret + "%");
            return n != null && n > 0;
        }

        private int deadLetterCount(String eventId) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM dead_letter WHERE origin_id = ? AND discarded_at IS NULL",
                    Integer.class,
                    eventId);
            return n == null ? 0 : n;
        }

        private String deadLetterOriginKind(String eventId) {
            return jdbc.queryForObject(
                    "SELECT origin_kind FROM dead_letter WHERE origin_id = ?", String.class, eventId);
        }

        private String deadLetterId(String eventId) {
            return jdbc.queryForObject(
                    "SELECT dead_letter_id FROM dead_letter WHERE origin_id = ? AND discarded_at IS NULL",
                    String.class,
                    eventId);
        }

        private int count(String table) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            return n == null ? 0 : n;
        }

        private int inboxRows() {
            return count("inbox_message") + count("inbox_entry") + count("inbox_fanout_job");
        }
    }

    /**
     * Test double: records HTTP posts and never opens a network.
     * 测试替身：记录 HTTP 投递，绝不打开网络。
     */
    private static final class RecordingHttpClient implements NotificationHttpClient {
        int posts;
        String endpoint;
        String apiKey;
        String jsonBody;
        RuntimeException failWith;

        @Override
        public void post(String endpoint, String apiKey, String jsonBody) {
            posts++;
            this.endpoint = endpoint;
            this.apiKey = apiKey;
            this.jsonBody = jsonBody;
            if (failWith != null) {
                throw failWith;
            }
        }
    }
}
