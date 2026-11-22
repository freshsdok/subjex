package com.subjex.platform.app.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.jdbc.JdbcIdempotencyPort;
import com.subjex.platform.app.jdbc.JdbcTaskMessagePort;
import com.subjex.platform.app.lock.SingleProcessLock;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.delivery.DeliveryAttempt;
import com.subjex.platform.contract.delivery.DeliveryPort;
import com.subjex.platform.contract.payment.CreatePaymentCommand;
import com.subjex.platform.contract.payment.PaymentAdapterUnconfiguredException;
import com.subjex.platform.contract.payment.PaymentAmount;
import com.subjex.platform.contract.payment.PaymentCrossTenantDeniedException;
import com.subjex.platform.contract.payment.PaymentDocument;
import com.subjex.platform.contract.payment.PaymentIdempotencyConflictException;
import com.subjex.platform.contract.payment.PaymentPort;
import com.subjex.platform.contract.payment.PaymentProviderIds;
import com.subjex.platform.contract.payment.PaymentResultCallback;
import com.subjex.platform.contract.payment.PaymentStatus;
import com.subjex.platform.contract.payment.PaymentStatusChanged;
import com.subjex.platform.contract.payment.RefundCommand;
import com.subjex.platform.contract.task.DeliveryResult;
import com.subjex.platform.contract.task.OutboxState;
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
 * PaymentCallbackRefundTest — purpose: WP6-2 mock adapter, idempotent callback, partial refund, outbox notice.
 * Gates: create then mock success then partial and remaining refund; over-refund rejected; duplicate
 * callback is a no-op; conflicting duplicate rejected; duplicate refund token is a no-op; cross-tenant
 * callback/refund denied; one outbox row per state change (relayed via DeliveryPort); unconfigured
 * production adapter fails closed.
 * <p>
 * 目的：WP6-2 模拟适配器、幂等回调、部分退款与出箱通知。门禁：创单后模拟支付成功，再部分退款与余额退清；
 * 超额退款拒绝；重复回调为空操作；冲突重复拒绝；重复退款令牌为空操作；禁止跨租户回调/退款；
 * 每次状态变化恰好一行出箱（经 DeliveryPort 中继）；生产未配置适配器失败关闭。
 */
class PaymentCallbackRefundTest {

    private static final String TENANT_A = "tenant-alpha";
    private static final String TENANT_B = "tenant-bravo";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.systemUTC();

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void createThenMockSuccessThenPartialAndRemainingRefund(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        PaymentDocument created = fx.port.createOrder(order(TENANT_A, 100L, "CNY", "ORD-100"));
        assertEquals(0, fx.count("outbox_event"));

        PaymentDocument paid = fx.port.applyPaymentResult(success(TENANT_A, created.paymentId(), "notify-1"));
        assertEquals(PaymentStatus.SUCCEEDED, paid.status());
        assertEquals("prov-ref-1", paid.providerReference());
        assertEquals(1, fx.voucherFacts(created.paymentId(), JdbcPaymentPort.FACT_PAYMENT_SUCCEEDED));
        assertEquals(1, fx.outboxCount());
        assertStatusChanged(fx, created.paymentId(), PaymentStatus.SUCCEEDED, 100L, 0L);

        PaymentDocument partial = fx.port.refund(refund(TENANT_A, created.paymentId(), 30L, "CNY", "refund-1"));
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, partial.status());
        assertEquals(30L, partial.refundedAmount().amountMinor());
        assertEquals(1, partial.refunds().size());
        assertEquals(2, fx.outboxCount());
        assertStatusChanged(fx, created.paymentId(), PaymentStatus.PARTIALLY_REFUNDED, 100L, 30L);

        // 超过剩余可退金额必须拒绝且不写行 / Over-refund must be rejected with no extra row
        assertThrows(
                IllegalArgumentException.class,
                () -> fx.port.refund(refund(TENANT_A, created.paymentId(), 80L, "CNY", "refund-over")));
        assertEquals(1, fx.count("payment_refund"));
        assertEquals(2, fx.outboxCount());

        PaymentDocument full = fx.port.refund(refund(TENANT_A, created.paymentId(), 70L, "CNY", "refund-2"));
        assertEquals(PaymentStatus.REFUNDED, full.status());
        assertEquals(100L, full.refundedAmount().amountMinor());
        assertEquals(2, full.refunds().size());
        assertEquals(3, fx.outboxCount());
        assertEquals(2, fx.count("payment_refund"));
        assertEquals(2, fx.voucherFacts(created.paymentId(), JdbcPaymentPort.FACT_REFUND_APPLIED));
        assertStatusChanged(fx, created.paymentId(), PaymentStatus.REFUNDED, 100L, 100L);

        assertThrows(
                IllegalStateException.class,
                () -> fx.port.refund(refund(TENANT_A, created.paymentId(), 1L, "CNY", "refund-3")));
        assertEquals(2, fx.count("payment_refund"));
        assertEquals(3, fx.outboxCount());
        assertEquals(0, fx.count("platform_task"));
        assertEquals(0, fx.count("idempotency_claim"));

        RecordingDeliveryPort delivery = new RecordingDeliveryPort();
        assertEquals(3, fx.relay(delivery, 10));
        assertEquals(3, delivery.calls.get());
        assertTrue(delivery.names.stream().allMatch(PaymentStatusChanged.EVENT_NAME::equals));
        assertEquals(3, fx.publishedOutbox());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void duplicateCallbackIsNoOpAndConflictIsRejected(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        PaymentDocument created = fx.port.createOrder(order(TENANT_A, 80L, "USD", "ORD-80"));
        PaymentDocument paid = fx.port.applyPaymentResult(success(TENANT_A, created.paymentId(), "notify-dup"));
        assertEquals(PaymentStatus.SUCCEEDED, paid.status());

        PaymentDocument replayed = fx.port.applyPaymentResult(success(TENANT_A, created.paymentId(), "notify-dup"));
        assertEquals(paid.paymentId(), replayed.paymentId());
        assertEquals(PaymentStatus.SUCCEEDED, replayed.status());
        assertEquals(1, fx.voucherFacts(created.paymentId(), JdbcPaymentPort.FACT_PAYMENT_SUCCEEDED));
        assertEquals(1, fx.outboxCount());

        assertThrows(
                PaymentIdempotencyConflictException.class,
                () -> fx.port.applyPaymentResult(new PaymentResultCallback(
                        TENANT_A, created.paymentId(), "notify-dup", PaymentStatus.FAILED, "other-ref")));
        PaymentDocument still = fx.port.queryOrder(TENANT_A, created.paymentId());
        assertEquals(PaymentStatus.SUCCEEDED, still.status());
        assertEquals("prov-ref-1", still.providerReference());
        assertEquals(1, fx.outboxCount());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void duplicateRefundTokenIsNoOp(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        PaymentDocument created = fx.port.createOrder(order(TENANT_A, 50L, "CNY", "ORD-50"));
        fx.port.applyPaymentResult(success(TENANT_A, created.paymentId(), "notify-r"));
        PaymentDocument first = fx.port.refund(refund(TENANT_A, created.paymentId(), 20L, "CNY", "token-r"));
        PaymentDocument replayed = fx.port.refund(refund(TENANT_A, created.paymentId(), 20L, "CNY", "token-r"));

        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, replayed.status());
        assertEquals(20L, replayed.refundedAmount().amountMinor());
        assertEquals(first.refunds().size(), replayed.refunds().size());
        assertEquals(1, fx.count("payment_refund"));
        assertEquals(2, fx.outboxCount());
        assertEquals(1, fx.voucherFacts(created.paymentId(), JdbcPaymentPort.FACT_REFUND_APPLIED));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void crossTenantCallbackAndRefundAreDenied(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        PaymentDocument created = fx.port.createOrder(order(TENANT_A, 40L, "CNY", "ORD-40"));

        assertThrows(
                PaymentCrossTenantDeniedException.class,
                () -> fx.port.applyPaymentResult(success(TENANT_B, created.paymentId(), "notify-x")));
        assertEquals(PaymentStatus.PENDING, fx.port.queryOrder(TENANT_A, created.paymentId()).status());
        assertEquals(0, fx.outboxCount());

        fx.port.applyPaymentResult(success(TENANT_A, created.paymentId(), "notify-ok"));
        assertThrows(
                PaymentCrossTenantDeniedException.class,
                () -> fx.port.refund(refund(TENANT_B, created.paymentId(), 10L, "CNY", "refund-x")));
        PaymentDocument still = fx.port.queryOrder(TENANT_A, created.paymentId());
        assertEquals(PaymentStatus.SUCCEEDED, still.status());
        assertEquals(0L, still.refundedAmount().amountMinor());
        assertEquals(0, fx.count("payment_refund"));
        assertEquals(1, fx.outboxCount());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void productionWithNoAdapterFailsClosed(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.openUnconfigured(mode);
        PaymentDocument created = fx.port.createOrder(order(TENANT_A, 25L, "EUR", "ORD-25"));

        assertThrows(
                PaymentAdapterUnconfiguredException.class,
                () -> fx.port.applyPaymentResult(success(TENANT_A, created.paymentId(), "notify-prod")));
        PaymentDocument still = fx.port.queryOrder(TENANT_A, created.paymentId());
        assertEquals(PaymentStatus.PENDING, still.status());
        assertEquals(0, fx.outboxCount());
        assertEquals(0, fx.voucherFacts(created.paymentId(), JdbcPaymentPort.FACT_PAYMENT_SUCCEEDED));
        assertEquals(1, fx.voucherFacts(created.paymentId(), JdbcPaymentPort.FACT_ORDER_CREATED));
        List<Map<String, Object>> audits = fx.auditRows(JdbcPaymentPort.CHARGE_ACTION);
        assertEquals(1, audits.size());
        assertEquals(AuditOutcome.REFUSED.name(), audits.get(0).get("outcome"));
        assertFalse(fx.auditLeaks(created.paymentId()));
        assertFalse(fx.auditLeaks("notify-prod"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void chargeAndRefundAuditDoNotLeakIdentifiers(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        PaymentDocument created = fx.port.createOrder(order(TENANT_A, 40L, "CNY", "ORD-40"));
        fx.port.applyPaymentResult(success(TENANT_A, created.paymentId(), "notify-secret"));
        fx.port.refund(refund(TENANT_A, created.paymentId(), 10L, "CNY", "refund-secret"));
        Map<String, Object> charge = fx.auditRows(JdbcPaymentPort.CHARGE_ACTION).get(0);
        Map<String, Object> refunded = fx.auditRows(JdbcPaymentPort.REFUND_ACTION).get(0);
        assertEquals(TENANT_A, charge.get("tenant_id"));
        assertEquals(JdbcPaymentPort.AUDIT_ACTOR, charge.get("actor_identity_id"));
        assertEquals(AuditOutcome.ALLOWED.name(), charge.get("outcome"));
        assertEquals(
                JdbcPaymentPort.actionTarget(PaymentProviderIds.MOCK, JdbcPaymentPort.CHARGE_ACTION, created.paymentId()),
                charge.get("action_target"));
        assertEquals(AuditOutcome.ALLOWED.name(), refunded.get("outcome"));
        assertEquals(
                JdbcPaymentPort.actionTarget(PaymentProviderIds.MOCK, JdbcPaymentPort.REFUND_ACTION, created.paymentId()),
                refunded.get("action_target"));
        assertFalse(fx.auditLeaks(created.paymentId()));
        assertFalse(fx.auditLeaks("notify-secret"));
        assertFalse(fx.auditLeaks("refund-secret"));
        assertFalse(fx.auditLeaks("prov-ref-1"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void httpTestDoubleChargesAndRefundsWithoutAPaymentNetwork(H2PlatformTables.Mode mode) {
        RecordingHttpClient client = new RecordingHttpClient();
        var adapter = PaymentProviderAdapterFactory.create(
                PaymentProviderIds.WECHAT_PAY,
                new String[] {},
                new PaymentProviderSettings(
                        "http",
                        "https://pay.example.invalid/v1/charges",
                        "test-api-key",
                        "mch-test-1"),
                client,
                null);
        Fixture fx = Fixture.openWithAdapters(mode, List.of(adapter));
        PaymentDocument created = fx.port.createOrder(
                orderWithProvider(TENANT_A, 40L, "CNY", "ORD-40", PaymentProviderIds.WECHAT_PAY));
        fx.port.applyPaymentResult(success(TENANT_A, created.paymentId(), "notify-secret"));
        assertEquals(PaymentStatus.SUCCEEDED, fx.port.queryOrder(TENANT_A, created.paymentId()).status());
        assertEquals(1, client.posts);
        assertEquals("https://pay.example.invalid/v1/charges", client.endpoint);
        assertEquals("test-api-key", client.apiKey);
        assertTrue(client.jsonBody.contains("\"action\":\"payment-result\""));
        assertTrue(client.jsonBody.contains(created.paymentId()));
        fx.port.refund(refund(TENANT_A, created.paymentId(), 10L, "CNY", "refund-secret"));
        assertEquals(2, client.posts);
        assertTrue(client.jsonBody.contains("\"action\":\"refund\""));
        assertEquals(AuditOutcome.ALLOWED.name(), fx.auditRows(JdbcPaymentPort.CHARGE_ACTION).get(0).get("outcome"));
        assertEquals(AuditOutcome.ALLOWED.name(), fx.auditRows(JdbcPaymentPort.REFUND_ACTION).get(0).get("outcome"));
        assertEquals(
                "WECHAT_PAY:charge:" + JdbcPaymentPort.maskIdentifier(created.paymentId()),
                fx.auditRows(JdbcPaymentPort.CHARGE_ACTION).get(0).get("action_target"));
        assertFalse(fx.auditLeaks(created.paymentId()));
        assertFalse(fx.auditLeaks("test-api-key"));
        assertFalse(fx.auditLeaks("mch-test-1"));
        assertFalse(fx.auditLeaks("notify-secret"));
        assertFalse(fx.auditLeaks("refund-secret"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void incompleteHttpAdapterFailsClosedAndDoesNotUseMock(H2PlatformTables.Mode mode) {
        var adapter = PaymentProviderAdapterFactory.create(
                PaymentProviderIds.WECHAT_PAY,
                new String[] {"local"},
                new PaymentProviderSettings("http", "https://pay.example.invalid/v1/charges", "", "mch-test-1"),
                new RecordingHttpClient(),
                new RecordingPaymentSink());
        assertInstanceOf(FailClosedPaymentAdapter.class, adapter);
        Fixture fx = Fixture.openWithAdapters(mode, List.of(adapter));
        PaymentDocument created = fx.port.createOrder(
                orderWithProvider(TENANT_A, 25L, "EUR", "ORD-25", PaymentProviderIds.WECHAT_PAY));
        assertThrows(
                PaymentAdapterUnconfiguredException.class,
                () -> fx.port.applyPaymentResult(success(TENANT_A, created.paymentId(), "notify-prod")));
        assertEquals(PaymentStatus.PENDING, fx.port.queryOrder(TENANT_A, created.paymentId()).status());
        assertEquals(0, fx.outboxCount());
        assertEquals(AuditOutcome.REFUSED.name(), fx.auditRows(JdbcPaymentPort.CHARGE_ACTION).get(0).get("outcome"));
    }

    private static void assertStatusChanged(
            Fixture fx, String paymentId, PaymentStatus status, long amountMinor, long refundedMinor) {
        List<Map<String, Object>> rows = fx.jdbc.queryForList(
                "SELECT event_body FROM outbox_event WHERE event_name = ? AND event_body LIKE ? ORDER BY occurred_at",
                PaymentStatusChanged.EVENT_NAME,
                "%" + paymentId + "%");
        JsonNode last = MAPPER.readTree((String) rows.get(rows.size() - 1).get("event_body"));
        assertEquals(PaymentStatusChanged.EVENT_NAME, fx.jdbc.queryForObject(
                "SELECT event_name FROM outbox_event WHERE event_body LIKE ? ORDER BY occurred_at DESC LIMIT 1",
                String.class,
                "%" + paymentId + "%"));
        assertEquals(paymentId, last.get("paymentId").asText());
        assertEquals(TENANT_A, last.get("tenantId").asText());
        assertEquals(status.name(), last.get("status").asText());
        assertEquals(amountMinor, last.get("amountMinor").asLong());
        assertEquals(refundedMinor, last.get("refundedAmountMinor").asLong());
        assertEquals("CNY".equals(last.get("currency").asText()) || "USD".equals(last.get("currency").asText())
                        || last.get("currency").asText().length() == 3,
                true);
    }

    private static CreatePaymentCommand order(String tenantId, long amountMinor, String currency, String reference) {
        return orderWithProvider(tenantId, amountMinor, currency, reference, PaymentProviderIds.MOCK);
    }

    private static CreatePaymentCommand orderWithProvider(
            String tenantId, long amountMinor, String currency, String reference, String providerId) {
        return new CreatePaymentCommand(
                tenantId, new PaymentAmount(amountMinor, currency), reference, "payer-ada", "END_USER", null, providerId);
    }

    private static PaymentResultCallback success(String tenantId, String paymentId, String notificationId) {
        return new PaymentResultCallback(tenantId, paymentId, notificationId, PaymentStatus.SUCCEEDED, "prov-ref-1");
    }

    private static RefundCommand refund(
            String tenantId, String paymentId, long amountMinor, String currency, String token) {
        return new RefundCommand(tenantId, paymentId, new PaymentAmount(amountMinor, currency), token);
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
        private final PaymentPort port;
        private final TransactionTemplate tx;

        private Fixture(DataSource source, JdbcTemplate jdbc, PaymentPort port, TransactionTemplate tx) {
            this.source = source;
            this.jdbc = jdbc;
            this.port = port;
            this.tx = tx;
        }

        private static Fixture open(H2PlatformTables.Mode mode) {
            return openWithAdapters(mode, List.of(new MockPaymentAdapter(new RecordingPaymentSink())));
        }

        private static Fixture openUnconfigured(H2PlatformTables.Mode mode) {
            return openWithAdapters(mode, List.of(new FailClosedPaymentAdapter(PaymentProviderIds.MOCK)));
        }

        private static Fixture openWithAdapters(
                H2PlatformTables.Mode mode, List<com.subjex.platform.contract.payment.PaymentProviderAdapter> adapters) {
            DataSource source = H2PlatformTables.migrated(mode);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            seedTenant(jdbc, TENANT_A);
            seedTenant(jdbc, TENANT_B);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            PaymentPort port = new JdbcPaymentPort(jdbc, tx, CLOCK, MAPPER, adapters, new JdbcAuditPort(jdbc));
            return new Fixture(source, jdbc, port, tx);
        }

        private static void seedTenant(JdbcTemplate jdbc, String tenantId) {
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    tenantId,
                    tenantId);
        }

        private int count(String table) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            return n == null ? 0 : n;
        }

        private int outboxCount() {
            return count("outbox_event");
        }

        private int publishedOutbox() {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE event_state = ?",
                    Integer.class,
                    OutboxState.PUBLISHED.name());
            return n == null ? 0 : n;
        }

        private int voucherFacts(String paymentId, String factKind) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM payment_voucher WHERE payment_id = ? AND fact_kind = ?",
                    Integer.class,
                    paymentId,
                    factKind);
            return n == null ? 0 : n;
        }

        private List<Map<String, Object>> auditRows(String action) {
            return jdbc.queryForList(
                    """
                    SELECT tenant_id, actor_identity_id, action_name, action_target, outcome
                    FROM audit_entry
                    WHERE action_name = ?
                    ORDER BY occurred_at
                    """,
                    action);
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

        private int relay(RecordingDeliveryPort delivery, int batch) {
            JdbcTaskMessagePort tasks = new JdbcTaskMessagePort(
                    jdbc,
                    tx,
                    new JdbcIdempotencyPort(jdbc, CLOCK),
                    new JdbcAuditPort(jdbc),
                    new SingleProcessLock(CLOCK),
                    delivery,
                    OpenTelemetry.noop(),
                    MAPPER,
                    CLOCK);
            return tasks.relayPending(batch);
        }
    }

    /**
     * Test double: records HTTP posts and never opens a network.
     * 测试替身：记录 HTTP 投递，绝不打开网络。
     */
    private static final class RecordingHttpClient implements PaymentHttpClient {
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
