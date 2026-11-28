package com.subjex.platform.app.points;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.app.identity.enduser.EndUserRecord;
import com.subjex.platform.app.identity.enduser.JdbcEndUserAccountStore;
import com.subjex.platform.app.payment.JdbcPaymentPort;
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
import com.subjex.platform.contract.payment.PaymentResultCallback;
import com.subjex.platform.contract.payment.PaymentStatus;
import com.subjex.platform.contract.points.PointsIdempotencyConflictException;
import com.subjex.platform.contract.points.PointsInsufficientException;
import com.subjex.platform.contract.points.PointsSubjectNotFoundException;
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
import org.springframework.http.MediaType;
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
 * PointsLedgerApiTest — tenant isolation, own-only end-user read, operator permission,
 * overdraft refusal, idempotency, history order, and no auto-earn on payment success.
 * 租户隔离、终端只能看本人、操作员权限、拒绝透支、幂等、历史顺序，以及支付成功不会自动发放。
 */
class PointsLedgerApiTest {

    private static final String TENANT_A = "tenant-points-a";
    private static final String TENANT_B = "tenant-points-b";
    private static final Instant T0 = Instant.parse("2026-11-28T08:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-28T08:01:00Z");
    private static final Instant T2 = Instant.parse("2026-11-28T08:02:00Z");

    private JdbcTemplate jdbc;
    private JdbcPointsPort port;
    private JdbcPaymentPort payments;
    private MockMvc operatorMvc;
    private MockMvc endUserMvc;
    private OperatorPrincipal reader;
    private OperatorPrincipal writer;
    private OperatorPrincipal none;
    private EndUserRecord alice;
    private EndUserRecord bob;
    private EndUserRecord carol;
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
        port = new JdbcPointsPort(jdbc, tx, clock);
        payments = new JdbcPaymentPort(jdbc, tx, clock);
        JdbcEndUserAccountStore accounts = new JdbcEndUserAccountStore(jdbc, tx, clock);
        OperatorPointsService operatorService = new OperatorPointsService(
                port, tenantAccess, PolicyEngineFactory.create("sql", new DenyWhenTenantMissing(), tenantAccess));
        operatorMvc = MockMvcBuilders.standaloneSetup(new OperatorPointsEndpoint(operatorService))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
        endUserMvc = MockMvcBuilders.standaloneSetup(new EndUserPointsEndpoint(new EndUserPointsService(port, accounts)))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();

        reader = operator("reader", "points.read");
        writer = operator("writer", "points.read", "points.adjust");
        none = operator("none");
        grant(reader, TENANT_A);
        grant(writer, TENANT_A);
        grant(writer, TENANT_B);
        grant(none, TENANT_A);

        alice = accounts.provision(TENANT_A, "Alice");
        bob = accounts.provision(TENANT_A, "Bob");
        carol = accounts.provision(TENANT_B, "Carol");
        now.set(T0);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unknownSubjectIsNotFoundAndFirstGrantCreatesAccount() throws Exception {
        operatorMvc.perform(get(OperatorPointsEndpoint.PATH)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("subjectId", "no-such-subject"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value(PointsSubjectNotFoundException.REASON));

        operatorMvc.perform(get(OperatorPointsEndpoint.PATH)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("subjectId", alice.subject().subjectId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.entries.length()").value(0));

        operatorMvc.perform(post(OperatorPointsEndpoint.PATH + "/adjustments")
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "tok-grant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody(alice.subject().subjectId(), "GRANT", 100, "welcome", "memo-1", "order", "ORD-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].delta").value(100))
                .andExpect(jsonPath("$.entries[0].balanceAfter").value(100))
                .andExpect(jsonPath("$.entries[0].direction").value("GRANT"))
                .andExpect(jsonPath("$.entries[0].reason").value("welcome"))
                .andExpect(jsonPath("$.entries[0].memo").value("memo-1"))
                .andExpect(jsonPath("$.entries[0].referenceType").value("order"))
                .andExpect(jsonPath("$.entries[0].referenceId").value("ORD-1"))
                .andExpect(jsonPath("$.entries[0].actorKind").value("OPERATOR"));
        assertEquals(1, count("points_account"));
        assertEquals(1, count("points_ledger"));
    }

    @Test
    void historyIsNewestFirstAndDeductRefusesOverdraft() throws Exception {
        now.set(T0);
        operatorMvc.perform(post(OperatorPointsEndpoint.PATH + "/adjustments")
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "tok-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody(alice.subject().subjectId(), "GRANT", 100, "first", null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100));
        now.set(T1);
        operatorMvc.perform(post(OperatorPointsEndpoint.PATH + "/adjustments")
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "tok-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody(alice.subject().subjectId(), "GRANT", 20, "second", null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(120));
        now.set(T2);
        operatorMvc.perform(post(OperatorPointsEndpoint.PATH + "/adjustments")
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "tok-c")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody(alice.subject().subjectId(), "DEDUCT", 30, "third", null, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(90))
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].reason").value("third"))
                .andExpect(jsonPath("$.entries[0].delta").value(-30))
                .andExpect(jsonPath("$.entries[0].balanceAfter").value(90))
                .andExpect(jsonPath("$.entries[1].reason").value("second"))
                .andExpect(jsonPath("$.entries[2].reason").value("first"));

        operatorMvc.perform(post(OperatorPointsEndpoint.PATH + "/adjustments")
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "tok-overdraft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody(alice.subject().subjectId(), "DEDUCT", 91, "too-much", null, null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value(PointsInsufficientException.REASON));
        operatorMvc.perform(get(OperatorPointsEndpoint.PATH)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("subjectId", alice.subject().subjectId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(90))
                .andExpect(jsonPath("$.entries.length()").value(3));

        operatorMvc.perform(post(OperatorPointsEndpoint.PATH + "/adjustments")
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "tok-bob-deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody(bob.subject().subjectId(), "DEDUCT", 1, "no-account", null, null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value(PointsInsufficientException.REASON));
    }

    @Test
    void operatorAdjustIsIdempotentAndConflictsOnFingerprintMismatch() throws Exception {
        String body = adjustBody(alice.subject().subjectId(), "GRANT", 50, "idem", "m", "ref", "R-1");
        operatorMvc.perform(post(OperatorPointsEndpoint.PATH + "/adjustments")
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "tok-same")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(50));
        operatorMvc.perform(post(OperatorPointsEndpoint.PATH + "/adjustments")
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "tok-same")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(50))
                .andExpect(jsonPath("$.entries.length()").value(1));
        operatorMvc.perform(post(OperatorPointsEndpoint.PATH + "/adjustments")
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "tok-same")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody(alice.subject().subjectId(), "GRANT", 80, "idem", "m", "ref", "R-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value(PointsIdempotencyConflictException.REASON));
        assertEquals(1, count("points_ledger"));
        assertEquals(50, jdbc.queryForObject(
                "SELECT balance FROM points_account WHERE tenant_id = ? AND subject_id = ?",
                Long.class,
                TENANT_A,
                alice.subject().subjectId()));
    }

    @Test
    void tenantIsolationAndReservedPlatformAreFailClosed() throws Exception {
        operatorMvc.perform(post(OperatorPointsEndpoint.PATH + "/adjustments")
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "tok-iso")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody(alice.subject().subjectId(), "GRANT", 40, "iso", null, null, null)))
                .andExpect(status().isOk());

        operatorMvc.perform(get(OperatorPointsEndpoint.PATH)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B)
                        .param("subjectId", alice.subject().subjectId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value(PointsSubjectNotFoundException.REASON));

        operatorMvc.perform(get(OperatorPointsEndpoint.PATH)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B)
                        .param("subjectId", carol.subject().subjectId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.entries.length()").value(0));

        Integer otherTenantRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM points_ledger WHERE tenant_id = ?", Integer.class, TENANT_B);
        assertEquals(0, otherTenantRows);

        operatorMvc.perform(get(OperatorPointsEndpoint.PATH)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, JdbcPointsPort.RESERVED_TENANT)
                        .param("subjectId", alice.subject().subjectId()))
                .andExpect(status().isForbidden());
        operatorMvc.perform(get(OperatorPointsEndpoint.PATH)
                        .with(asOperator(writer))
                        .param("subjectId", alice.subject().subjectId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorPermissionIsEnforced() throws Exception {
        operatorMvc.perform(get(OperatorPointsEndpoint.PATH)
                        .with(asOperator(none))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("subjectId", alice.subject().subjectId()))
                .andExpect(status().isForbidden());
        operatorMvc.perform(get(OperatorPointsEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("subjectId", alice.subject().subjectId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0));
        operatorMvc.perform(post(OperatorPointsEndpoint.PATH + "/adjustments")
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "tok-reader")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody(alice.subject().subjectId(), "GRANT", 10, "denied", null, null, null)))
                .andExpect(status().isForbidden());
        assertEquals(0, count("points_ledger"));
    }

    @Test
    void endUserSeesOnlyOwnBalanceAndHistory() throws Exception {
        now.set(T0);
        operatorMvc.perform(post(OperatorPointsEndpoint.PATH + "/adjustments")
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "tok-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody(alice.subject().subjectId(), "GRANT", 15, "for-alice", null, null, null)))
                .andExpect(status().isOk());
        now.set(T1);
        operatorMvc.perform(post(OperatorPointsEndpoint.PATH + "/adjustments")
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .header("X-Idempotency-Token", "tok-bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody(bob.subject().subjectId(), "GRANT", 7, "for-bob", null, null, null)))
                .andExpect(status().isOk());

        endUserMvc.perform(get(EndUserPointsEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectId").value(alice.subject().subjectId()))
                .andExpect(jsonPath("$.balance").value(15))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].reason").value("for-alice"));
        endUserMvc.perform(get(EndUserPointsEndpoint.PATH)
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectId").value(bob.subject().subjectId()))
                .andExpect(jsonPath("$.balance").value(7))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].reason").value("for-bob"));
        endUserMvc.perform(get(EndUserPointsEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isForbidden());
    }

    @Test
    void paymentSuccessDoesNotGrantPoints() {
        PaymentDocument created = payments.createOrder(new CreatePaymentCommand(
                TENANT_A,
                new PaymentAmount(199L, "CNY"),
                "ORD-NO-EARN",
                alice.subject().subjectId(),
                "END_USER",
                null,
                PaymentProviderIds.MOCK));
        payments.applyPaymentResult(new PaymentResultCallback(
                TENANT_A, created.paymentId(), "notify-no-earn", PaymentStatus.SUCCEEDED, "prov-ref-1"));
        PaymentDocument paid = payments.queryOrder(TENANT_A, created.paymentId());
        assertEquals(PaymentStatus.SUCCEEDED, paid.status());
        assertEquals(0, count("points_ledger"));
        assertEquals(0, count("points_account"));
    }

    private int count(String table) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private void grant(OperatorPrincipal operator, String tenantId) {
        jdbc.update(
                "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                operator.subjectId(),
                tenantId);
    }

    private static String adjustBody(
            String subjectId,
            String direction,
            long amount,
            String reason,
            String memo,
            String referenceType,
            String referenceId) {
        StringBuilder json = new StringBuilder();
        json.append("{\"subjectId\":\"")
                .append(subjectId)
                .append("\",\"direction\":\"")
                .append(direction)
                .append("\",\"amount\":")
                .append(amount)
                .append(",\"reason\":\"")
                .append(reason)
                .append("\"");
        if (memo != null) {
            json.append(",\"memo\":\"").append(memo).append("\"");
        }
        if (referenceType != null && referenceId != null) {
            json.append(",\"reference\":{\"type\":\"")
                    .append(referenceType)
                    .append("\",\"id\":\"")
                    .append(referenceId)
                    .append("\"}");
        }
        json.append("}");
        return json.toString();
    }

    private static OperatorPrincipal operator(String slug, String... permissions) {
        return new OperatorPrincipal(
                "op-" + slug, "hash", "id-" + slug, "sub-" + slug, Set.of(permissions), true);
    }

    private static RequestPostProcessor asOperator(OperatorPrincipal operator) {
        return asPrincipal(operator);
    }

    private static RequestPostProcessor asEndUser(EndUserRecord record) {
        return asPrincipal(new EndUserPrincipal(
                record.account().accountId(),
                record.subject().subjectId(),
                record.account().loginName(),
                true));
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
