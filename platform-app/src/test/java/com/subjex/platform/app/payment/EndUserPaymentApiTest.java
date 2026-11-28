package com.subjex.platform.app.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.app.identity.enduser.EndUserRecord;
import com.subjex.platform.app.identity.enduser.JdbcEndUserAccountStore;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.payment.CreatePaymentCommand;
import com.subjex.platform.contract.payment.PaymentAmount;
import com.subjex.platform.contract.payment.PaymentDocument;
import com.subjex.platform.contract.payment.PaymentResultCallback;
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
 * EndUserPaymentApiTest — end-user create/charge/list/status/cancel of own payments; payer isolation.
 * 终端本人支付单的创单、扣款、列表、状态与取消；付款人隔离。
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
    void operatorTokenIsForbiddenOnEndUserPaymentPaths() throws Exception {
        mvc.perform(get(EndUserPaymentEndpoint.PATH)
                        .with(asOperator(operator))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden());

        mvc.perform(post(EndUserPaymentEndpoint.PATH + "/" + alicePending.paymentId() + "/cancel")
                        .with(asOperator(operator))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden());

        mvc.perform(post(EndUserPaymentEndpoint.PATH)
                        .with(asOperator(operator))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "op-create")
                        .contentType("application/json")
                        .content(createBody("order", "OP-1", 1L, "CNY", null)))
                .andExpect(status().isForbidden());

        mvc.perform(post(EndUserPaymentEndpoint.PATH + "/" + alicePending.paymentId() + "/charge")
                        .with(asOperator(operator))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden());
    }

    @Test
    void payerCanCreateOwnPaymentAndCannotCreateForAnotherSubject() throws Exception {
        String body = createBody("order", "ALICE-NEW", 150L, "CNY", "afternoon tea");
        // A foreign payer field on the body must be ignored; the signed-in subject is the payer.
        // 请求体里的他人付款人字段必须被忽略；付款人只能是当前登录主体。
        String withForeignPayer = body.substring(0, body.length() - 1)
                + ",\"payerSubjectId\":\""
                + bob.subject().subjectId()
                + "\"}";
        var created = mvc.perform(post(EndUserPaymentEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "alice-create-1")
                        .contentType("application/json")
                        .content(withForeignPayer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount.amountMinor").value(150))
                .andExpect(jsonPath("$.amount.currency").value("CNY"))
                .andExpect(jsonPath("$.reference").value("order/ALICE-NEW"))
                .andExpect(jsonPath("$.referenceType").value("order"))
                .andExpect(jsonPath("$.referenceId").value("ALICE-NEW"))
                .andExpect(jsonPath("$.memo").value("afternoon tea"))
                .andExpect(jsonPath("$.payerSubjectId").value(alice.subject().subjectId()))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();
        String paymentId = com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.paymentId");
        mvc.perform(get(EndUserPaymentEndpoint.PATH + "/" + paymentId)
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound());
        mvc.perform(post(EndUserPaymentEndpoint.PATH + "/" + paymentId + "/cancel")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void createIsIdempotentForTheSameTokenAndConflictsOnADifferentFingerprint() throws Exception {
        String body = createBody("ticket", "T-1", 80L, "CNY", null);
        String firstId = com.jayway.jsonpath.JsonPath.read(
                mvc.perform(post(EndUserPaymentEndpoint.PATH)
                                .with(asEndUser(alice))
                                .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                                .header("X-Idempotency-Token", "alice-idem-1")
                                .contentType("application/json")
                                .content(body))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.paymentId");
        mvc.perform(post(EndUserPaymentEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "alice-idem-1")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(firstId));
        mvc.perform(post(EndUserPaymentEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "alice-idem-1")
                        .contentType("application/json")
                        .content(createBody("ticket", "T-1", 81L, "CNY", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("payment-idempotency-conflict"));
    }

    @Test
    void createValidatesAmountCurrencyReferenceAndIdempotency() throws Exception {
        mvc.perform(post(EndUserPaymentEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType("application/json")
                        .content(createBody("order", "X-1", 10L, "CNY", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("idempotency token is missing"));
        mvc.perform(post(EndUserPaymentEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "alice-val-0")
                        .contentType("application/json")
                        .content(createBody("order", "X-1", 0L, "CNY", null)))
                .andExpect(status().isBadRequest());
        mvc.perform(post(EndUserPaymentEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "alice-val-cur")
                        .contentType("application/json")
                        .content(createBody("order", "X-1", 10L, "ZZZ", null)))
                .andExpect(status().isBadRequest());
        mvc.perform(post(EndUserPaymentEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "alice-val-ref")
                        .contentType("application/json")
                        .content("{\"amountMinor\":10,\"currency\":\"CNY\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(EndUserPaymentEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "alice-val-type")
                        .contentType("application/json")
                        .content(createBody("1bad", "X-1", 10L, "CNY", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void payerCanStartChargeOnPendingAndSuccessStillNeedsCallback() throws Exception {
        String paymentId = com.jayway.jsonpath.JsonPath.read(
                mvc.perform(post(EndUserPaymentEndpoint.PATH)
                                .with(asEndUser(alice))
                                .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                                .header("X-Idempotency-Token", "alice-charge-1")
                                .contentType("application/json")
                                .content(createBody("order", "PAY-1", 200L, "CNY", null)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.paymentId");
        mvc.perform(post(EndUserPaymentEndpoint.PATH + "/" + paymentId + "/charge")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("PENDING"))
                .andExpect(jsonPath("$.charge.providerId").value("MOCK"))
                .andExpect(jsonPath("$.charge.params.mode").value("TEST"))
                .andExpect(jsonPath("$.charge.params.paymentId").value(paymentId));
        mvc.perform(get(EndUserPaymentEndpoint.PATH + "/" + paymentId)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        port.applyPaymentResult(new PaymentResultCallback(
                TENANT_A, paymentId, "notify-" + paymentId, PaymentStatus.SUCCEEDED, "prov-ref-1"));
        mvc.perform(get(EndUserPaymentEndpoint.PATH + "/" + paymentId)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        mvc.perform(post(EndUserPaymentEndpoint.PATH + "/" + paymentId + "/charge")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("payment-charge-not-pending"));
        mvc.perform(post(EndUserPaymentEndpoint.PATH + "/" + alicePending.paymentId() + "/charge")
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound());
    }

    @Test
    void expiredPendingCannotBeChargedAndFailClosedAdapterDoesNotSucceed() throws Exception {
        String paymentId = com.jayway.jsonpath.JsonPath.read(
                mvc.perform(post(EndUserPaymentEndpoint.PATH)
                                .with(asEndUser(alice))
                                .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                                .header("X-Idempotency-Token", "alice-expire-1")
                                .contentType("application/json")
                                .content(createBody("order", "EXP-1", 20L, "CNY", null)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.paymentId");
        now.set(T0.plusSeconds(31 * 60));
        mvc.perform(post(EndUserPaymentEndpoint.PATH + "/" + paymentId + "/charge")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("payment-charge-not-pending"));
        mvc.perform(get(EndUserPaymentEndpoint.PATH + "/" + paymentId)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
        now.set(T1);

        DataSource ds = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        var jdbc = new JdbcTemplate(ds);
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                TENANT_A,
                "Tenant A");
        var tx = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
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
        var accounts = new JdbcEndUserAccountStore(jdbc, tx, clock);
        var aliceClosed = accounts.provision(TENANT_A, "Alice");
        var closedPort = new JdbcPaymentPort(
                jdbc,
                tx,
                clock,
                new tools.jackson.databind.ObjectMapper(),
                java.util.List.of(new FailClosedPaymentAdapter(PaymentProviderIds.MOCK)),
                new JdbcAuditPort(jdbc));
        var closedMvc = MockMvcBuilders.standaloneSetup(
                        new EndUserPaymentEndpoint(new EndUserPaymentService(closedPort, accounts)))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
        String closedId = com.jayway.jsonpath.JsonPath.read(
                closedMvc.perform(post(EndUserPaymentEndpoint.PATH)
                                .with(asEndUser(aliceClosed))
                                .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                                .header("X-Idempotency-Token", "alice-closed-1")
                                .contentType("application/json")
                                .content(createBody("order", "CLOSED-1", 30L, "CNY", null)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("PENDING"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.paymentId");
        closedMvc.perform(post(EndUserPaymentEndpoint.PATH + "/" + closedId + "/charge")
                        .with(asEndUser(aliceClosed))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reason").value("payment-adapter-not-configured"));
        closedMvc.perform(get(EndUserPaymentEndpoint.PATH + "/" + closedId)
                        .with(asEndUser(aliceClosed))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    private static String createBody(String type, String id, long amountMinor, String currency, String memo) {
        StringBuilder json = new StringBuilder();
        json.append("{\"amountMinor\":")
                .append(amountMinor)
                .append(",\"currency\":\"")
                .append(currency)
                .append("\",\"reference\":{\"type\":\"")
                .append(type)
                .append("\",\"id\":\"")
                .append(id)
                .append("\"}");
        if (memo != null) {
            json.append(",\"memo\":\"").append(memo).append("\"");
        }
        json.append("}");
        return json.toString();
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
