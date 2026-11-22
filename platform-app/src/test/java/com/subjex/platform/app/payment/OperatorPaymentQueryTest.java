package com.subjex.platform.app.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorTenantAccess;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.PolicyEngineFactory;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.payment.CreatePaymentCommand;
import com.subjex.platform.contract.payment.PaymentAmount;
import com.subjex.platform.contract.payment.PaymentDocument;
import com.subjex.platform.contract.payment.PaymentProviderIds;
import com.subjex.platform.contract.payment.PaymentStatus;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OperatorPaymentQueryTest — operator read-only payment list/get, filters, isolation, no write routes.
 * 操作员只读支付列表与详情、筛选、租户隔离；不能创单或退款。
 */
class OperatorPaymentQueryTest {

    private static final String TENANT_A = "tenant-pay-ui-a";
    private static final String TENANT_B = "tenant-pay-ui-b";
    private static final Instant T0 = Instant.parse("2026-11-22T08:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-22T10:00:00Z");
    private static final String PAYER_ADA = "payer-ada";
    private static final String PAYER_BEA = "payer-bea";

    private JdbcTemplate jdbc;
    private JdbcPaymentPort port;
    private MockMvc mvc;
    private OperatorPrincipal reader;
    private OperatorPrincipal none;
    private PaymentDocument first;
    private PaymentDocument second;
    private PaymentDocument otherTenant;
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    @BeforeEach
    void setUp() {
        DataSource ds = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        jdbc = new JdbcTemplate(ds);
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                TENANT_A,
                "Tenant A");
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                TENANT_B,
                "Tenant B");
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
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
        JdbcOperatorTenantAccess tenantAccess = new JdbcOperatorTenantAccess(jdbc, tx);
        port = new JdbcPaymentPort(jdbc, tx, clock);
        OperatorPaymentService service = new OperatorPaymentService(
                port, tenantAccess, PolicyEngineFactory.create("sql", new DenyWhenTenantMissing(), tenantAccess));
        mvc = MockMvcBuilders.standaloneSetup(new OperatorPaymentEndpoint(service))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();

        reader = operator("reader", "payment.read");
        none = operator("none");
        grant(reader, TENANT_A);
        grant(none, TENANT_A);

        now.set(T0);
        first = port.createOrder(order(TENANT_A, 199L, "CNY", "ORD-1", PAYER_ADA));
        now.set(T1);
        second = port.createOrder(order(TENANT_A, 50L, "CNY", "ORD-2", PAYER_BEA));
        otherTenant = port.createOrder(order(TENANT_B, 10L, "CNY", "ORD-B", PAYER_ADA));
        jdbc.update(
                "UPDATE payment SET payment_status = ?, refunded_amount_minor = ?, updated_at = ? WHERE payment_id = ?",
                PaymentStatus.PARTIALLY_REFUNDED.name(),
                20L,
                java.sql.Timestamp.from(T1),
                second.paymentId());
        jdbc.update(
                "INSERT INTO payment_refund ("
                        + "refund_id, tenant_id, payment_id, amount_minor, currency, "
                        + "refund_status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "refund-2",
                TENANT_A,
                second.paymentId(),
                20L,
                "CNY",
                "APPLIED",
                java.sql.Timestamp.from(T1));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listIsNewestFirstAndHidesOtherTenant() throws Exception {
        mvc.perform(get(OperatorPaymentEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].paymentId").value(second.paymentId()))
                .andExpect(jsonPath("$[0].status").value(PaymentStatus.PARTIALLY_REFUNDED.name()))
                .andExpect(jsonPath("$[0].refundedAmount.amountMinor").value(20))
                .andExpect(jsonPath("$[0].refunds.length()").value(1))
                .andExpect(jsonPath("$[0].refunds[0].refundId").value("refund-2"))
                .andExpect(jsonPath("$[1].paymentId").value(first.paymentId()))
                .andExpect(jsonPath("$[1].status").value(PaymentStatus.PENDING.name()));
    }

    @Test
    void listFiltersByStatusAndPayer() throws Exception {
        mvc.perform(get(OperatorPaymentEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("status", "PENDING")
                        .param("payerSubjectId", PAYER_ADA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].paymentId").value(first.paymentId()))
                .andExpect(jsonPath("$[0].reference").value("ORD-1"));
    }

    @Test
    void getReturnsRefundLinesAndUnknownIdIsNotFound() throws Exception {
        mvc.perform(get(OperatorPaymentEndpoint.PATH + "/" + second.paymentId())
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(second.paymentId()))
                .andExpect(jsonPath("$.status").value(PaymentStatus.PARTIALLY_REFUNDED.name()))
                .andExpect(jsonPath("$.amount.amountMinor").value(50))
                .andExpect(jsonPath("$.amount.currency").value("CNY"))
                .andExpect(jsonPath("$.refundedAmount.amountMinor").value(20))
                .andExpect(jsonPath("$.refunds.length()").value(1))
                .andExpect(jsonPath("$.refunds[0].amount.amountMinor").value(20));

        mvc.perform(get(OperatorPaymentEndpoint.PATH + "/missing-payment")
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("payment-not-found"));
    }

    @Test
    void missingPermissionOtherTenantAndCrossTenantGetAreDenied() throws Exception {
        mvc.perform(get(OperatorPaymentEndpoint.PATH)
                        .with(asOperator(none))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden());

        mvc.perform(get(OperatorPaymentEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isForbidden());

        mvc.perform(get(OperatorPaymentEndpoint.PATH + "/" + otherTenant.paymentId())
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("payment-cross-tenant-denied"));
    }

    @Test
    void reservedOrMissingTenantIsRejectedAndWriteRouteDoesNotExist() throws Exception {
        mvc.perform(get(OperatorPaymentEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, "platform"))
                .andExpect(status().isForbidden());

        mvc.perform(get(OperatorPaymentEndpoint.PATH).with(asOperator(reader)))
                .andExpect(status().isForbidden());

        mvc.perform(post(OperatorPaymentEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType("application/json")
                        .content("{\"amountMinor\":1,\"currency\":\"CNY\"}"))
                .andExpect(status().isMethodNotAllowed());
    }

    private void grant(OperatorPrincipal operator, String tenantId) {
        jdbc.update(
                "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                operator.subjectId(),
                tenantId);
    }

    private static CreatePaymentCommand order(
            String tenantId, long amountMinor, String currency, String reference, String payer) {
        return new CreatePaymentCommand(
                tenantId,
                new PaymentAmount(amountMinor, currency),
                reference,
                payer,
                "END_USER",
                null,
                PaymentProviderIds.MOCK);
    }

    private static OperatorPrincipal operator(String slug, String... permissions) {
        return new OperatorPrincipal(
                "op-" + slug, "hash", "id-" + slug, "sub-" + slug, Set.of(permissions), true);
    }

    private static RequestPostProcessor asOperator(OperatorPrincipal operator) {
        return request -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(operator, "n/a", List.of()));
            SecurityContextHolder.setContext(context);
            return request;
        };
    }
}
