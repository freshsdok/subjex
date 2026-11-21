package com.subjex.platform.app.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.payment.CreatePaymentCommand;
import com.subjex.platform.contract.payment.PaymentAmount;
import com.subjex.platform.contract.payment.PaymentCrossTenantDeniedException;
import com.subjex.platform.contract.payment.PaymentDocument;
import com.subjex.platform.contract.payment.PaymentPort;
import com.subjex.platform.contract.payment.PaymentStatus;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.time.Clock;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PaymentPortCreateQueryTest — purpose: WP6-1 create order + query with tenant and amount rules.
 * Gates: happy-path create then query; missing / disabled / reserved {@code platform} tenant denied;
 * cross-tenant query denied; missing or invalid currency and negative amount rejected.
 * <p>
 * 目的：WP6-1 创单与查单，含租户与金额规则。门禁：快乐路径创单后查单；缺失/停用/保留租户
 * {@code platform} 拒绝；禁止跨租户查单；缺失或非法币种以及负金额拒绝。
 */
class PaymentPortCreateQueryTest {

    private static final String TENANT_A = "tenant-alpha";
    private static final String TENANT_B = "tenant-bravo";
    private static final Clock CLOCK = Clock.systemUTC();

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void createOrderThenQueryReturnsPendingDocumentAndVoucher(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        PaymentDocument created = fx.port.createOrder(order(TENANT_A, 199L, "CNY", "ORD-41"));

        assertNotNull(created.paymentId());
        assertEquals(TENANT_A, created.tenantId());
        assertEquals(199L, created.amount().amountMinor());
        assertEquals("CNY", created.amount().currency());
        assertEquals(0L, created.refundedAmount().amountMinor());
        assertEquals("CNY", created.refundedAmount().currency());
        assertEquals(PaymentStatus.PENDING, created.status());
        assertEquals("ORD-41", created.reference());
        assertEquals("MOCK", created.providerId());
        assertTrue(created.refunds().isEmpty());

        PaymentDocument queried = fx.port.queryOrder(TENANT_A, created.paymentId());
        assertEquals(created.paymentId(), queried.paymentId());
        assertEquals(PaymentStatus.PENDING, queried.status());
        assertEquals(199L, queried.amount().amountMinor());
        assertEquals("CNY", queried.amount().currency());

        Map<String, Object> voucher = fx.jdbc.queryForMap(
                "SELECT * FROM payment_voucher WHERE payment_id = ?", created.paymentId());
        assertEquals(TENANT_A, voucher.get("tenant_id"));
        assertEquals(JdbcPaymentPort.FACT_ORDER_CREATED, voucher.get("fact_kind"));
        assertEquals(199L, ((Number) voucher.get("amount_minor")).longValue());
        assertEquals("CNY", String.valueOf(voucher.get("currency")).trim());
        assertEquals(PaymentStatus.PENDING.name(), voucher.get("resulting_status"));

        assertEquals(0, fx.count("outbox_event"));
        assertEquals(0, fx.count("platform_task"));
        assertEquals(0, fx.count("idempotency_claim"));
        assertEquals(0, fx.count("payment_refund"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void missingTenantIsDeniedAndWritesNoRow(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        assertThrows(TenantMissingException.class, () -> fx.port.createOrder(order(null, 100L, "CNY", "ORD-1")));
        assertThrows(TenantMissingException.class, () -> fx.port.createOrder(order("  ", 100L, "CNY", "ORD-1")));
        assertThrows(TenantMissingException.class, () -> fx.port.queryOrder(null, "pay-missing"));
        assertThrows(TenantMissingException.class, () -> fx.port.queryOrder("  ", "pay-missing"));
        assertEquals(0, fx.count("payment"));
        assertEquals(0, fx.count("payment_voucher"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void unknownAndDisabledAndReservedPlatformTenantsAreDenied(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        fx.jdbc.update("UPDATE tenant SET tenant_state = 'SUSPENDED' WHERE tenant_id = ?", TENANT_B);

        assertThrows(
                TenantNotFoundException.class,
                () -> fx.port.createOrder(order("tenant-missing", 100L, "CNY", "ORD-1")));
        assertThrows(
                TenantDisabledException.class, () -> fx.port.createOrder(order(TENANT_B, 100L, "CNY", "ORD-1")));
        assertThrows(
                IllegalArgumentException.class, () -> fx.port.createOrder(order("platform", 100L, "CNY", "ORD-1")));

        PaymentDocument created = fx.port.createOrder(order(TENANT_A, 50L, "CNY", "ORD-ok"));
        assertThrows(
                TenantNotFoundException.class, () -> fx.port.queryOrder("tenant-missing", created.paymentId()));
        assertThrows(TenantDisabledException.class, () -> fx.port.queryOrder(TENANT_B, created.paymentId()));
        assertThrows(IllegalArgumentException.class, () -> fx.port.queryOrder("platform", created.paymentId()));

        assertEquals(1, fx.count("payment"));
        assertEquals(1, fx.count("payment_voucher"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void queryFromOtherTenantIsDenied(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);
        PaymentDocument created = fx.port.createOrder(order(TENANT_A, 88L, "CNY", "ORD-88"));

        assertThrows(
                PaymentCrossTenantDeniedException.class,
                () -> fx.port.queryOrder(TENANT_B, created.paymentId()));
        PaymentDocument stillThere = fx.port.queryOrder(TENANT_A, created.paymentId());
        assertEquals(created.paymentId(), stillThere.paymentId());
        assertEquals(1, fx.count("payment"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void missingInvalidCurrencyAndNegativeAmountAreRejected(H2PlatformTables.Mode mode) {
        Fixture fx = Fixture.open(mode);

        assertThrows(IllegalArgumentException.class, () -> fx.port.createOrder(raw(TENANT_A, 100L, null)));
        assertThrows(IllegalArgumentException.class, () -> fx.port.createOrder(raw(TENANT_A, 100L, "  ")));
        assertThrows(IllegalArgumentException.class, () -> fx.port.createOrder(raw(TENANT_A, 100L, "CN")));
        assertThrows(IllegalArgumentException.class, () -> fx.port.createOrder(raw(TENANT_A, 100L, "ZZZ")));
        assertThrows(IllegalArgumentException.class, () -> fx.port.createOrder(raw(TENANT_A, -1L, "CNY")));

        assertEquals(0, fx.count("payment"));
        assertEquals(0, fx.count("payment_voucher"));
    }

    private static CreatePaymentCommand order(String tenantId, long amountMinor, String currency, String reference) {
        return new CreatePaymentCommand(
                tenantId,
                new PaymentAmount(amountMinor, currency),
                reference,
                "payer-ada",
                "END_USER",
                null,
                "MOCK");
    }

    private static CreatePaymentCommand raw(String tenantId, long amountMinor, String currency) {
        return new CreatePaymentCommand(
                tenantId, new PaymentAmount(amountMinor, currency), "ORD-bad", null, null, null, "MOCK");
    }

    private static final class Fixture {
        private final JdbcTemplate jdbc;
        private final PaymentPort port;

        private Fixture(JdbcTemplate jdbc, PaymentPort port) {
            this.jdbc = jdbc;
            this.port = port;
        }

        private static Fixture open(H2PlatformTables.Mode mode) {
            DataSource source = H2PlatformTables.migrated(mode);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            seedTenant(jdbc, TENANT_A);
            seedTenant(jdbc, TENANT_B);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            return new Fixture(jdbc, new JdbcPaymentPort(jdbc, tx, CLOCK));
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
    }
}
