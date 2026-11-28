package com.subjex.platform.app.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.lock.SingleProcessLock;
import com.subjex.platform.app.notification.EndUserNotificationDestinationResolver;
import com.subjex.platform.app.notification.FailClosedNotificationAdapter;
import com.subjex.platform.app.notification.JdbcNotificationDispatcher;
import com.subjex.platform.app.notification.MockNotificationAdapter;
import com.subjex.platform.app.notification.RecordingNotificationSink;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.notification.NotificationChannel;
import com.subjex.platform.contract.notification.NotificationDispatcher;
import com.subjex.platform.contract.notification.NotificationRequested;
import com.subjex.platform.contract.payment.CreatePaymentCommand;
import com.subjex.platform.contract.payment.PaymentAmount;
import com.subjex.platform.contract.payment.PaymentDocument;
import com.subjex.platform.contract.payment.PaymentPort;
import com.subjex.platform.contract.payment.PaymentProviderIds;
import com.subjex.platform.contract.payment.PaymentResultCallback;
import com.subjex.platform.contract.payment.PaymentStatus;
import com.subjex.platform.contract.payment.PaymentStatusChanged;
import com.subjex.platform.contract.payment.RefundCommand;
import com.subjex.platform.contract.task.OutboxState;
import com.subjex.platform.contract.task.TaskLifecycle;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PaymentSucceededNotificationTest — first SUCCEEDED enqueues NotificationRequested for the payer.
 * 首次进入 SUCCEEDED 时在同一出箱为付款人入队 NotificationRequested；非成功变迁不发此通知。
 */
class PaymentSucceededNotificationTest {

    private static final String TENANT_A = "tenant-alpha";
    private static final String PAYER = "payer-ada";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.systemUTC();

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void succeededEnqueuesPayerNoticeOnTheSameOutbox(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        fx.seedEndUser("acct-payer", PAYER, "id-payer", TENANT_A);
        fx.seedVerifiedEmail("acct-payer", "payer@example.com");
        PaymentDocument created = fx.port.createOrder(order(TENANT_A, 199L, "CNY", "ORD-OK", PAYER, "END_USER"));

        PaymentDocument paid = fx.port.applyPaymentResult(success(created.paymentId(), "notify-ok"));
        assertEquals(PaymentStatus.SUCCEEDED, paid.status());
        assertEquals(PaymentStatus.SUCCEEDED, fx.port.queryOrder(TENANT_A, created.paymentId()).status());
        assertEquals(1, fx.countByName(PaymentStatusChanged.EVENT_NAME));
        assertEquals(1, fx.countByName(NotificationRequested.EVENT_NAME));

        JsonNode body = fx.latestNotice();
        assertEquals(PaymentSucceededNotice.TEMPLATE_ID, body.get("templateId").asText());
        assertEquals(PaymentSucceededNotice.CHANNEL.name(), body.get("channel").asText());
        assertEquals(created.paymentId(), body.get("parameters").get(PaymentSucceededNotice.PARAM_PAYMENT_ID).asText());
        assertEquals("199", body.get("parameters").get(PaymentSucceededNotice.PARAM_AMOUNT_MINOR).asText());
        assertEquals("CNY", body.get("parameters").get(PaymentSucceededNotice.PARAM_CURRENCY).asText());
        assertEquals(PaymentStatus.SUCCEEDED.name(), body.get("parameters").get(PaymentSucceededNotice.PARAM_STATUS).asText());
        assertEquals(PAYER, body.get("recipient").get("subjectId").asText());
        assertEquals(InboxRecipientRealm.END_USER.name(), body.get("recipient").get("realm").asText());
        assertEquals(TENANT_A, body.get("tenantId").asText());

        assertEquals(1, fx.dispatcher.dispatchPending(10));
        assertEquals(OutboxState.PUBLISHED.name(), fx.noticeState());
        assertEquals(1, fx.sink.sent(PaymentSucceededNotice.CHANNEL).size());
        assertEquals(PaymentSucceededNotice.TEMPLATE_ID, fx.sink.sent().get(0).templateId());
        assertEquals("199", fx.sink.sent().get(0).parameters().get(PaymentSucceededNotice.PARAM_AMOUNT_MINOR));
        assertEquals("payer@example.com", fx.sink.sent().get(0).recipient().destination());
        assertEquals(PAYER, fx.sink.sent().get(0).recipient().subjectId());
        assertNull(fx.latestNotice().path("recipient").path("destination").asText(null));
        assertEquals(PaymentStatus.SUCCEEDED, fx.port.queryOrder(TENANT_A, created.paymentId()).status());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void nonSucceededTransitionsDoNotEnqueueTheSuccessNotice(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        PaymentDocument failed = fx.port.createOrder(order(TENANT_A, 40L, "CNY", "ORD-FAIL", PAYER, "END_USER"));
        PaymentDocument afterFail = fx.port.applyPaymentResult(failedCallback(failed.paymentId(), "notify-fail"));
        assertEquals(PaymentStatus.FAILED, afterFail.status());
        assertEquals(1, fx.countByName(PaymentStatusChanged.EVENT_NAME));
        assertEquals(0, fx.countByName(NotificationRequested.EVENT_NAME));

        PaymentDocument pending = fx.port.createOrder(order(TENANT_A, 80L, "CNY", "ORD-CAN", PAYER, "END_USER"));
        fx.port.cancelOrder(TENANT_A, pending.paymentId(), PAYER, "END_USER");
        assertEquals(0, fx.countByName(NotificationRequested.EVENT_NAME));

        PaymentDocument refundable = fx.port.createOrder(order(TENANT_A, 50L, "CNY", "ORD-REF", PAYER, "END_USER"));
        fx.port.applyPaymentResult(success(refundable.paymentId(), "notify-ref"));
        assertEquals(1, fx.countByName(NotificationRequested.EVENT_NAME));
        fx.port.refund(new RefundCommand(TENANT_A, refundable.paymentId(), new PaymentAmount(10L, "CNY"), "token-ref"));
        assertEquals(1, fx.countByName(NotificationRequested.EVENT_NAME));
        assertEquals(4, fx.countByName(PaymentStatusChanged.EVENT_NAME));

        PaymentDocument replayed = fx.port.applyPaymentResult(success(refundable.paymentId(), "notify-ref"));
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, replayed.status());
        assertEquals(1, fx.countByName(NotificationRequested.EVENT_NAME));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void unconfiguredAdapterFailsClosedAndPaymentStillSucceeds(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.openFailClosed(mode);
        fx.seedEndUser("acct-payer", PAYER, "id-payer", TENANT_A);
        fx.seedVerifiedEmail("acct-payer", "payer@example.com");
        PaymentDocument created = fx.port.createOrder(order(TENANT_A, 25L, "EUR", "ORD-FC", PAYER, "END_USER"));

        PaymentDocument paid = fx.port.applyPaymentResult(success(created.paymentId(), "notify-fc"));
        assertEquals(PaymentStatus.SUCCEEDED, paid.status());
        assertEquals(PaymentStatus.SUCCEEDED, fx.port.queryOrder(TENANT_A, created.paymentId()).status());
        assertEquals(1, fx.countByName(PaymentStatusChanged.EVENT_NAME));
        assertEquals(1, fx.countByName(NotificationRequested.EVENT_NAME));
        assertEquals(OutboxState.PENDING.name(), fx.noticeState());

        for (int i = 0; i < TaskLifecycle.DEFAULT_MAX_ATTEMPT; i++) {
            fx.dispatcher.dispatchPending(10);
        }
        assertEquals(PaymentStatus.SUCCEEDED, fx.port.queryOrder(TENANT_A, created.paymentId()).status());
        assertEquals(OutboxState.DEAD.name(), fx.noticeState());
        assertEquals(1, fx.deadLetterCount(fx.noticeId()));
        assertTrue(fx.sink.sent().isEmpty());
        List<Map<String, Object>> audits = fx.sendAuditRows();
        assertEquals(TaskLifecycle.DEFAULT_MAX_ATTEMPT, audits.size());
        for (Map<String, Object> row : audits) {
            assertEquals(AuditOutcome.REFUSED.name(), row.get("outcome"));
        }
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void missingPayerSkipsNoticeAndPaymentStillSucceeds(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        PaymentDocument created =
                fx.port.createOrder(order(TENANT_A, 10L, "CNY", "ORD-NOPAYER", null, null));
        PaymentDocument paid = fx.port.applyPaymentResult(success(created.paymentId(), "notify-nopayer"));
        assertEquals(PaymentStatus.SUCCEEDED, paid.status());
        assertEquals(1, fx.countByName(PaymentStatusChanged.EVENT_NAME));
        assertEquals(0, fx.countByName(NotificationRequested.EVENT_NAME));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void missingVerifiedEmailDeadLettersTheNoticeAndPaymentStillSucceeds(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        fx.seedEndUser("acct-payer", PAYER, "id-payer", TENANT_A);
        PaymentDocument created = fx.port.createOrder(order(TENANT_A, 60L, "CNY", "ORD-NOMAIL", PAYER, "END_USER"));
        PaymentDocument paid = fx.port.applyPaymentResult(success(created.paymentId(), "notify-nomail"));
        assertEquals(PaymentStatus.SUCCEEDED, paid.status());
        assertEquals(1, fx.countByName(NotificationRequested.EVENT_NAME));

        for (int i = 0; i < TaskLifecycle.DEFAULT_MAX_ATTEMPT; i++) {
            fx.dispatcher.dispatchPending(10);
        }
        assertEquals(PaymentStatus.SUCCEEDED, fx.port.queryOrder(TENANT_A, created.paymentId()).status());
        assertEquals(OutboxState.DEAD.name(), fx.noticeState());
        assertEquals(
                EndUserNotificationDestinationResolver.UNRESOLVED_REASON, fx.noticeFailure());
        assertEquals(1, fx.deadLetterCount(fx.noticeId()));
        assertTrue(fx.sink.sent().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void pendingEmailIsNotUsedAsAPaymentNoticeDestination(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        fx.seedEndUser("acct-payer", PAYER, "id-payer", TENANT_A);
        fx.seedPendingEmail("acct-payer", "pending@example.com");
        PaymentDocument created = fx.port.createOrder(order(TENANT_A, 70L, "CNY", "ORD-PENDING", PAYER, "END_USER"));
        fx.port.applyPaymentResult(success(created.paymentId(), "notify-pending"));
        fx.dispatcher.dispatchPending(10);
        assertEquals(PaymentStatus.SUCCEEDED, fx.port.queryOrder(TENANT_A, created.paymentId()).status());
        assertEquals(
                EndUserNotificationDestinationResolver.UNRESOLVED_REASON, fx.noticeFailure());
        assertTrue(fx.sink.sent().isEmpty());
    }

        private static CreatePaymentCommand order(
            String tenantId, long amountMinor, String currency, String reference, String payer, String realm) {
        return new CreatePaymentCommand(
                tenantId,
                new PaymentAmount(amountMinor, currency),
                reference,
                payer,
                realm,
                null,
                PaymentProviderIds.MOCK);
    }

    private static PaymentResultCallback success(String paymentId, String notificationId) {
        return new PaymentResultCallback(TENANT_A, paymentId, notificationId, PaymentStatus.SUCCEEDED, "prov-ref-ok");
    }

    private static PaymentResultCallback failedCallback(String paymentId, String notificationId) {
        return new PaymentResultCallback(TENANT_A, paymentId, notificationId, PaymentStatus.FAILED, "prov-ref-fail");
    }

    private static final class Fixture {
        private final JdbcTemplate jdbc;
        private final PaymentPort port;
        private final NotificationDispatcher dispatcher;
        private final RecordingNotificationSink sink;

        private Fixture(
                JdbcTemplate jdbc,
                PaymentPort port,
                NotificationDispatcher dispatcher,
                RecordingNotificationSink sink) {
            this.jdbc = jdbc;
            this.port = port;
            this.dispatcher = dispatcher;
            this.sink = sink;
        }

        private static Fixture open(H2PlatformTables.Mode mode) {
            RecordingNotificationSink sink = new RecordingNotificationSink(CLOCK);
            return openWithAdapters(
                    mode,
                    List.of(
                            new MockNotificationAdapter(NotificationChannel.SMS, sink),
                            new MockNotificationAdapter(NotificationChannel.EMAIL, sink),
                            new MockNotificationAdapter(NotificationChannel.WECHAT_TEMPLATE, sink)),
                    sink);
        }

        private static Fixture openFailClosed(H2PlatformTables.Mode mode) {
            return openWithAdapters(
                    mode,
                    List.of(new FailClosedNotificationAdapter(PaymentSucceededNotice.CHANNEL)),
                    new RecordingNotificationSink(CLOCK));
        }

        private static Fixture openWithAdapters(
                H2PlatformTables.Mode mode,
                List<com.subjex.platform.contract.notification.NotificationChannelAdapter> adapters,
                RecordingNotificationSink sink) {
            DataSource source = H2PlatformTables.migrated(mode);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    TENANT_A,
                    TENANT_A);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            NotificationDispatcher dispatcher = new JdbcNotificationDispatcher(
                    jdbc, tx, new SingleProcessLock(CLOCK), MAPPER, CLOCK, adapters, new JdbcAuditPort(jdbc));
            PaymentPort port = new JdbcPaymentPort(jdbc, tx, CLOCK);
            return new Fixture(jdbc, port, dispatcher, sink);
        }

        private int countByName(String eventName) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE event_name = ?", Integer.class, eventName);
            return n == null ? 0 : n;
        }

        private JsonNode latestNotice() {
            String json = jdbc.queryForObject(
                    "SELECT event_body FROM outbox_event WHERE event_name = ? ORDER BY occurred_at DESC, event_id DESC LIMIT 1",
                    String.class,
                    NotificationRequested.EVENT_NAME);
            return MAPPER.readTree(json);
        }

        private String noticeId() {
            return jdbc.queryForObject(
                    "SELECT event_id FROM outbox_event WHERE event_name = ? ORDER BY occurred_at DESC, event_id DESC LIMIT 1",
                    String.class,
                    NotificationRequested.EVENT_NAME);
        }

        private String noticeState() {
            return jdbc.queryForObject(
                    "SELECT event_state FROM outbox_event WHERE event_id = ?",
                    String.class,
                    noticeId());
        }

        private int deadLetterCount(String originId) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM dead_letter WHERE origin_id = ?", Integer.class, originId);
            return n == null ? 0 : n;
        }

        private void seedEndUser(String accountId, String subjectId, String identityId, String tenantId) {
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

        private void seedVerifiedEmail(String accountId, String email) {
            Instant at = Instant.parse("2026-11-28T08:00:00Z");
            jdbc.update(
                    """
                    INSERT INTO end_user_profile (account_id, email, email_verified_at, updated_at)
                    VALUES (?, ?, ?, ?)
                    """,
                    accountId,
                    email,
                    Timestamp.from(at),
                    Timestamp.from(at));
        }

        private void seedPendingEmail(String accountId, String email) {
            Instant at = Instant.parse("2026-11-28T08:00:00Z");
            jdbc.update(
                    """
                    INSERT INTO end_user_profile (account_id, pending_email, pending_email_requested_at, updated_at)
                    VALUES (?, ?, ?, ?)
                    """,
                    accountId,
                    email,
                    Timestamp.from(at),
                    Timestamp.from(at));
        }

        private String noticeFailure() {
            return jdbc.queryForObject(
                    "SELECT failure_reason FROM outbox_event WHERE event_id = ?",
                    String.class,
                    noticeId());
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
    }
}
