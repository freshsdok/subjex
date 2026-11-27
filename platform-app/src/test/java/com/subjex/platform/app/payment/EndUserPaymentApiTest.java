package com.subjex.platform.app.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.app.identity.enduser.EndUserRecord;
import com.subjex.platform.app.identity.enduser.JdbcEndUserAccountStore;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.payment.CreatePaymentCommand;
import com.subjex.platform.contract.payment.PaymentAmount;
import com.subjex.platform.contract.payment.PaymentDocument;
import com.subjex.platform.contract.payment.PaymentProviderIds;
import com.subjex.platform.contract.payment.PaymentStatus;
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
 * EndUserPaymentApiTest — end-user list/status/cancel of own payments; cross-realm and payer isolation.
 * 终端本人支付单的列表、状态与取消；跨领域与付款人隔离。不能创单或扣费。
 */
class EndUserPaymentApiTest {

    private static final String TENANT_A = "tenant-eu-pay-a";
    private static final String TENANT_B = "tenant-eu-pay-b";
    private static final Instant T0 = Instant.parse("2026-11-28T08:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-28T08:01:00Z");

    private JdbcPaymentPort port;
    private MockMvc mvc;
    private EndUserRecord alice;
    private EndUserRecord bob;
    private EndUserRecord carol;
    private OperatorPrincipal operator;
    private PaymentDocument alicePending;
    private PaymentDocument aliceSucceeded;
    private PaymentDocument bobPending;
    private PaymentDocument otherTenant;
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    @BeforeEach
    void setUp() {
        DataSource ds = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
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
        JdbcEndUserAccountStore accounts = new JdbcEndUserAccountStore(jdbc, tx, clock);
        port = new JdbcPaymentPort(jdbc, tx, clock);
        mvc = MockMvcBuilders.standaloneSetup(new EndUserPaymentEndpoint(new EndUserPaymentService(port, accounts)))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();

        alice = accounts.provision(TENANT_A, "Alice");
        bob = accounts.provision(TENANT_A, "Bob");
        carol = accounts.provision(TENANT_B, "Carol");
        operator = new OperatorPrincipal("op-reader", "hash", "id-reader", "sub-reader", Set.of("payment.read"), true);

        now.set(T0);
        alicePending = port.createOrder(order(TENANT_A, 199L, "ORD-ALICE-1", alice.subject().subjectId()));
        now.set(T1);
        aliceSucceeded = port.createOrder(order(TENANT_A, 50L, "ORD-ALICE-2", alice.subject().subjectId()));
        jdbc.update(
                "UPDATE payment SET payment_status = ?, succeeded_at = ?, updated_at = ? WHERE payment_id = ?",
                PaymentStatus.SUCCEEDED.name(),
                java.sql.Timestamp.from(T1),
                java.sql.Timestamp.from(T1),
                aliceSucceeded.paymentId());
        bobPending = port.createOrder(order(TENANT_A, 10L, "ORD-BOB", bob.subject().subjectId()));
        otherTenant = port.createOrder(order(TENANT_B, 30L, "ORD-CAROL", carol.subject().subjectId()));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listShowsOnlyOwnNewestFirstAndGetReturnsStatus() throws Exception {
        mvc.perform(get(EndUserPaymentEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].paymentId").value(aliceSucceeded.paymentId()))
                .andExpect(jsonPath("$[0].status").value(PaymentStatus.SUCCEEDED.name()))
                .andExpect(jsonPath("$[0].amount.amountMinor").value(50))
                .andExpect(jsonPath("$[1].paymentId").value(alicePending.paymentId()))
                .andExpect(jsonPath("$[1].status").value(PaymentStatus.PENDING.name()))
                .andExpect(jsonPath("$[1].reference").value("ORD-ALICE-1"));

        mvc.perform(get(EndUserPaymentEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].paymentId").value(alicePending.paymentId()));

        mvc.perform(get(EndUserPaymentEndpoint.PATH + "/" + alicePending.paymentId())
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(alicePending.paymentId()))
                .andExpect(jsonPath("$.status").value(PaymentStatus.PENDING.name()))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void payerCanCancelPendingAndRepeatCancelIsIdempotent() throws Exception {
        mvc.perform(post(EndUserPaymentEndpoint.PATH + "/" + alicePending.paymentId() + "/cancel")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(PaymentStatus.CANCELLED.name()))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

        mvc.perform(post(EndUserPaymentEndpoint.PATH + "/" + alicePending.paymentId() + "/cancel")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(PaymentStatus.CANCELLED.name()));
    }

    @Test
    void cancelOfSucceededOrExpiredIsConflict() throws Exception {
        mvc.perform(post(EndUserPaymentEndpoint.PATH + "/" + aliceSucceeded.paymentId() + "/cancel")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value(PaymentCancelNotPendingException.REASON));

        now.set(T0.plus(PaymentLifecycleRules.PENDING_TTL));
        mvc.perform(post(EndUserPaymentEndpoint.PATH + "/" + alicePending.paymentId() + "/cancel")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value(PaymentCancelNotPendingException.REASON));

        mvc.perform(get(EndUserPaymentEndpoint.PATH + "/" + alicePending.paymentId())
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(PaymentStatus.EXPIRED.name()));
    }

    @Test
    void anotherEndUserAndOtherTenantAreHiddenAndCancelIsPayerOnly() throws Exception {
        mvc.perform(get(EndUserPaymentEndpoint.PATH)
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].paymentId").value(bobPending.paymentId()));

        mvc.perform(get(EndUserPaymentEndpoint.PATH + "/" + alicePending.paymentId())
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("payment-not-found"));

        mvc.perform(post(EndUserPaymentEndpoint.PATH + "/" + alicePending.paymentId() + "/cancel")
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("payment-not-found"));

        mvc.perform(get(EndUserPaymentEndpoint.PATH + "/" + otherTenant.paymentId())
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("payment-cross-tenant-denied"));

        mvc.perform(get(EndUserPaymentEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isForbidden());

        mvc.perform(get(EndUserPaymentEndpoint.PATH)
                        .with(asEndUser(carol))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].paymentId").value(otherTenant.paymentId()));
    }

    @Test
    void operatorTokenIsForbiddenAndCreateRouteDoesNotExist() throws Exception {
        mvc.perform(get(EndUserPaymentEndpoint.PATH)
                        .with(asOperator(operator))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden());

        mvc.perform(post(EndUserPaymentEndpoint.PATH + "/" + alicePending.paymentId() + "/cancel")
                        .with(asOperator(operator))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden());

        // No create-from-shell route; standalone MockMvc reports POST on the collection as 405 or 404.
        // 没有从壳里创单的路由；独立 MockMvc 对集合 POST 返回 405 或 404。
        mvc.perform(post(EndUserPaymentEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType("application/json")
                        .content("{\"amountMinor\":1,\"currency\":\"CNY\"}"))
                .andExpect(status().isMethodNotAllowed());
    }

    private static CreatePaymentCommand order(String tenantId, long amountMinor, String reference, String payer) {
        return new CreatePaymentCommand(
                tenantId,
                new PaymentAmount(amountMinor, "CNY"),
                reference,
                payer,
                EndUserPaymentService.PAYER_REALM,
                null,
                PaymentProviderIds.MOCK);
    }

    private static RequestPostProcessor asEndUser(EndUserRecord record) {
        return asPrincipal(new EndUserPrincipal(
                record.account().accountId(),
                record.subject().subjectId(),
                record.account().loginName(),
                true));
    }

    private static RequestPostProcessor asOperator(OperatorPrincipal operator) {
        return asPrincipal(operator);
    }

    private static RequestPostProcessor asPrincipal(Object principal) {
        return request -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a", List.of()));
            SecurityContextHolder.setContext(context);
            return request;
        };
    }
}
