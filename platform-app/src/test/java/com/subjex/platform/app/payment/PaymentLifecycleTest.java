package com.subjex.platform.app.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.payment.CreatePaymentCommand;
import com.subjex.platform.contract.payment.PaymentAmount;
import com.subjex.platform.contract.payment.PaymentCancelDeniedException;
import com.subjex.platform.contract.payment.PaymentDocument;
import com.subjex.platform.contract.payment.PaymentPort;
import com.subjex.platform.contract.payment.PaymentProviderIds;
import com.subjex.platform.contract.payment.PaymentRefundWindowClosedException;
import com.subjex.platform.contract.payment.PaymentResultCallback;
import com.subjex.platform.contract.payment.PaymentStatus;
import com.subjex.platform.contract.payment.PaymentStatusChanged;
import com.subjex.platform.contract.payment.RefundCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PaymentLifecycleTest — pending expiry (30 minutes from created_at), payer-only cancel, 7-day refund window.
 * 目的：待支付自 created_at 起 30 分钟过期、仅付款人可取消、成功后 7 日内可退。
 */
class PaymentLifecycleTest {

    private static final String TENANT_A = "tenant-alpha";
    private static final String PAYER = "payer-ada";
    private static final String REALM = "END_USER";
    private static final Instant T0 = Instant.parse("2026-11-26T12:00:00Z");

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void pendingExpiresOnReadAndCannotBeCharged(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        PaymentDocument created = fx.port.createOrder(order(100L, "ORD-exp"));
        assertEquals(PaymentStatus.PENDING, created.status());
        assertEquals(T0.plus(PaymentLifecycleRules.PENDING_TTL), created.expiresAt());

        fx.now.set(T0.plus(PaymentLifecycleRules.PENDING_TTL).minusSeconds(1));
        assertEquals(PaymentStatus.PENDING, fx.port.queryOrder(TENANT_A, created.paymentId()).status());

        fx.now.set(T0.plus(PaymentLifecycleRules.PENDING_TTL));
        PaymentDocument expired = fx.port.queryOrder(TENANT_A, created.paymentId());
        assertEquals(PaymentStatus.EXPIRED, expired.status());
        assertEquals(T0.plus(PaymentLifecycleRules.PENDING_TTL), expired.expiresAt());

        IllegalStateException charged = assertThrows(
                IllegalStateException.class,
                () -> fx.port.applyPaymentResult(success(created.paymentId(), "notify-late")));
        assertTrue(charged.getMessage().contains("EXPIRED"));
        assertEquals(PaymentStatus.EXPIRED, fx.port.queryOrder(TENANT_A, created.paymentId()).status());
        assertStatusLast(fx, created.paymentId(), PaymentStatus.EXPIRED);
        assertEquals(
                1,
                fx.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payment_voucher WHERE payment_id = ? AND fact_kind = ?",
                        Integer.class,
                        created.paymentId(),
                        JdbcPaymentPort.FACT_PAYMENT_EXPIRED));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void chargeJustBeforeExpirySucceedsAndSweepExpiresUnqueriedPending(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        PaymentDocument early = fx.port.createOrder(order(40L, "ORD-early"));
        PaymentDocument stale = fx.port.createOrder(order(60L, "ORD-stale"));

        fx.now.set(T0.plus(PaymentLifecycleRules.PENDING_TTL).minusSeconds(1));
        PaymentDocument paid = fx.port.applyPaymentResult(success(early.paymentId(), "notify-early"));
        assertEquals(PaymentStatus.SUCCEEDED, paid.status());
        assertEquals(T0.plus(PaymentLifecycleRules.PENDING_TTL).minusSeconds(1), paid.succeededAt());

        fx.now.set(T0.plus(PaymentLifecycleRules.PENDING_TTL));
        assertEquals(1, fx.payments.expireOverdue(50));
        assertEquals(PaymentStatus.SUCCEEDED, fx.port.queryOrder(TENANT_A, early.paymentId()).status());
        assertEquals(PaymentStatus.EXPIRED, fx.port.queryOrder(TENANT_A, stale.paymentId()).status());
        assertEquals(0, fx.payments.expireOverdue(50));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void payerCanCancelPendingAndRepeatIsNoOp(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        PaymentDocument created = fx.port.createOrder(order(80L, "ORD-cancel"));

        PaymentDocument cancelled = fx.port.cancelOrder(TENANT_A, created.paymentId(), PAYER, REALM);
        assertEquals(PaymentStatus.CANCELLED, cancelled.status());
        assertEquals(T0, cancelled.cancelledAt());
        assertStatusLast(fx, created.paymentId(), PaymentStatus.CANCELLED);

        PaymentDocument again = fx.port.cancelOrder(TENANT_A, created.paymentId(), PAYER, REALM);
        assertEquals(PaymentStatus.CANCELLED, again.status());
        assertEquals(1, fx.outboxCount(created.paymentId()));

        IllegalStateException charged = assertThrows(
                IllegalStateException.class,
                () -> fx.port.applyPaymentResult(success(created.paymentId(), "notify-cancelled")));
        assertTrue(charged.getMessage().contains("CANCELLED"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void cancelIsDeniedForWrongPayerAndNonPending(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        PaymentDocument created = fx.port.createOrder(order(25L, "ORD-auth"));

        assertThrows(
                PaymentCancelDeniedException.class,
                () -> fx.port.cancelOrder(TENANT_A, created.paymentId(), "other-payer", REALM));
        assertThrows(
                PaymentCancelDeniedException.class,
                () -> fx.port.cancelOrder(TENANT_A, created.paymentId(), PAYER, "OPERATOR"));
        assertEquals(PaymentStatus.PENDING, fx.port.queryOrder(TENANT_A, created.paymentId()).status());

        PaymentDocument paid = fx.port.applyPaymentResult(success(created.paymentId(), "notify-ok"));
        assertEquals(PaymentStatus.SUCCEEDED, paid.status());
        IllegalStateException notPending = assertThrows(
                IllegalStateException.class,
                () -> fx.port.cancelOrder(TENANT_A, created.paymentId(), PAYER, REALM));
        assertTrue(notPending.getMessage().contains("SUCCEEDED"));

        PaymentDocument leftover = fx.port.createOrder(order(15L, "ORD-exp-cancel"));
        fx.now.set(T0.plus(PaymentLifecycleRules.PENDING_TTL));
        assertEquals(PaymentStatus.EXPIRED, fx.port.queryOrder(TENANT_A, leftover.paymentId()).status());
        IllegalStateException expired = assertThrows(
                IllegalStateException.class,
                () -> fx.port.cancelOrder(TENANT_A, leftover.paymentId(), PAYER, REALM));
        assertTrue(expired.getMessage().contains("EXPIRED"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void refundIsAllowedForSevenDaysFromSuccessAndThenRefused(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        PaymentDocument created = fx.port.createOrder(order(100L, "ORD-window"));
        PaymentDocument paid = fx.port.applyPaymentResult(success(created.paymentId(), "notify-win"));
        Instant succeededAt = paid.succeededAt();
        assertNotNull(succeededAt);
        assertNull(paid.cancelledAt());

        fx.now.set(succeededAt.plus(PaymentLifecycleRules.REFUND_WINDOW));
        PaymentDocument partial = fx.port.refund(refund(created.paymentId(), 30L, "refund-in"));
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, partial.status());
        assertEquals(30L, partial.refundedAmount().amountMinor());

        fx.now.set(succeededAt.plus(PaymentLifecycleRules.REFUND_WINDOW).plusSeconds(1));
        assertThrows(
                PaymentRefundWindowClosedException.class,
                () -> fx.port.refund(refund(created.paymentId(), 20L, "refund-late")));
        assertEquals(
                PaymentStatus.PARTIALLY_REFUNDED,
                fx.port.queryOrder(TENANT_A, created.paymentId()).status());
        assertEquals(30L, fx.port.queryOrder(TENANT_A, created.paymentId()).refundedAmount().amountMinor());

        PaymentDocument replayed = fx.port.refund(refund(created.paymentId(), 30L, "refund-in"));
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, replayed.status());
        assertEquals(30L, replayed.refundedAmount().amountMinor());
        assertEquals(1, replayed.refunds().size());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void missingSucceededAtFailsClosedAndLegacyVoucherClockIsUsed(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        PaymentDocument created = fx.port.createOrder(order(50L, "ORD-legacy"));
        fx.port.applyPaymentResult(success(created.paymentId(), "notify-legacy"));
        fx.jdbc.update("UPDATE payment SET succeeded_at = NULL WHERE payment_id = ?", created.paymentId());

        fx.now.set(T0.plus(PaymentLifecycleRules.REFUND_WINDOW));
        PaymentDocument partial = fx.port.refund(refund(created.paymentId(), 10L, "refund-legacy"));
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, partial.status());

        fx.jdbc.update(
                "UPDATE payment_voucher SET created_at = ? WHERE payment_id = ? AND fact_kind = ?",
                java.sql.Timestamp.from(T0.minus(PaymentLifecycleRules.REFUND_WINDOW).minusSeconds(1)),
                created.paymentId(),
                JdbcPaymentPort.FACT_PAYMENT_SUCCEEDED);
        fx.jdbc.update("UPDATE payment SET succeeded_at = NULL WHERE payment_id = ?", created.paymentId());
        assertThrows(
                PaymentRefundWindowClosedException.class,
                () -> fx.port.refund(refund(created.paymentId(), 10L, "refund-legacy-late")));
    }

    private static void assertStatusLast(Fixture fx, String paymentId, PaymentStatus status) {
        List<Map<String, Object>> rows = fx.jdbc.queryForList(
                "SELECT event_body FROM outbox_event WHERE event_name = ? AND event_body LIKE ? ORDER BY occurred_at",
                PaymentStatusChanged.EVENT_NAME,
                "%" + paymentId + "%");
        assertTrue(!rows.isEmpty());
        String body = (String) rows.get(rows.size() - 1).get("event_body");
        assertTrue(body.contains("\"" + status.name() + "\"") || body.contains(status.name()));
    }

    private static CreatePaymentCommand order(long amountMinor, String reference) {
        return new CreatePaymentCommand(
                TENANT_A,
                new PaymentAmount(amountMinor, "CNY"),
                reference,
                PAYER,
                REALM,
                null,
                PaymentProviderIds.MOCK);
    }

    private static PaymentResultCallback success(String paymentId, String notificationId) {
        return new PaymentResultCallback(TENANT_A, paymentId, notificationId, PaymentStatus.SUCCEEDED, "prov-ref-1");
    }

    private static RefundCommand refund(String paymentId, long amountMinor, String token) {
        return new RefundCommand(TENANT_A, paymentId, new PaymentAmount(amountMinor, "CNY"), token);
    }

    private static final class Fixture {
        private final JdbcTemplate jdbc;
        private final PaymentPort port;
        private final JdbcPaymentPort payments;
        private final AtomicReference<Instant> now;

        private Fixture(JdbcTemplate jdbc, JdbcPaymentPort payments, AtomicReference<Instant> now) {
            this.jdbc = jdbc;
            this.port = payments;
            this.payments = payments;
            this.now = now;
        }

        private static Fixture open(H2PlatformTables.Mode mode) {
            DataSource source = H2PlatformTables.migrated(mode);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    TENANT_A,
                    TENANT_A);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            AtomicReference<Instant> now = new AtomicReference<>(T0);
            Clock clock = new Clock() {
                @Override
                public ZoneOffset getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(java.time.ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return now.get();
                }
            };
            return new Fixture(jdbc, new JdbcPaymentPort(jdbc, tx, clock), now);
        }

        private int outboxCount(String paymentId) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE event_name = ? AND event_body LIKE ?",
                    Integer.class,
                    PaymentStatusChanged.EVENT_NAME,
                    "%" + paymentId + "%");
            return n == null ? 0 : n;
        }
    }
}
