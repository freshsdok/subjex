package com.subjex.platform.app.consent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.identity.enduser.EndUserRecord;
import com.subjex.platform.app.identity.enduser.JdbcEndUserAccountStore;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorTenantAccess;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.PolicyEngineFactory;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.consent.ConsentEventKind;
import com.subjex.platform.contract.consent.ConsentPort;
import com.subjex.platform.contract.identity.Identity;
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
 * OperatorConsentQueryTest — operator read-only consent list/history, filters, isolation, no grant/withdraw.
 * 操作员只读同意列表与历史、筛选、租户隔离；不能代为授予或撤回。
 */
class OperatorConsentQueryTest {

    private static final String TENANT_A = "tenant-consent-ui-a";
    private static final String TENANT_B = "tenant-consent-ui-b";
    private static final Instant T0 = Instant.parse("2026-11-22T08:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-22T10:00:00Z");
    private static final String PURPOSE_A = "privacy-notice";
    private static final String PURPOSE_B = "message-notice";
    private static final String VERSION = "v1";

    private JdbcTemplate jdbc;
    private ConsentPort port;
    private MockMvc mvc;
    private EndUserRecord alice;
    private EndUserRecord bob;
    private OperatorPrincipal reader;
    private OperatorPrincipal none;
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
        JdbcEndUserAccountStore accounts = new JdbcEndUserAccountStore(jdbc, tx, clock);
        JdbcOperatorTenantAccess tenantAccess = new JdbcOperatorTenantAccess(jdbc, tx);
        port = new JdbcConsentPort(jdbc, tx, clock, new JdbcAuditPort(jdbc));
        OperatorConsentService service = new OperatorConsentService(
                port, tenantAccess, PolicyEngineFactory.create("sql", new DenyWhenTenantMissing(), tenantAccess));
        mvc = MockMvcBuilders.standaloneSetup(new OperatorConsentEndpoint(service))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();

        alice = accounts.provision(TENANT_A, "Alice");
        bob = accounts.provision(TENANT_A, "Bob");
        accounts.provision(TENANT_B, "Carol");
        Identity aliceIdentity = accounts.findActiveIdentity(alice.account().accountId(), TENANT_A).orElseThrow();
        Identity bobIdentity = accounts.findActiveIdentity(bob.account().accountId(), TENANT_A).orElseThrow();

        reader = operator("reader", "consent.read");
        none = operator("none");
        grant(reader, TENANT_A);
        grant(none, TENANT_A);

        now.set(T0);
        port.grant(
                TENANT_A,
                alice.subject().subjectId(),
                PURPOSE_A,
                VERSION,
                aliceIdentity.identityId());
        port.grant(
                TENANT_A,
                bob.subject().subjectId(),
                PURPOSE_B,
                VERSION,
                bobIdentity.identityId());
        now.set(T1);
        port.withdraw(TENANT_A, bob.subject().subjectId(), PURPOSE_B, bobIdentity.identityId());
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listCurrentFiltersBySubjectAndPurposeAndOmitsWithdrawn() throws Exception {
        mvc.perform(get(OperatorConsentEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("subjectId", alice.subject().subjectId())
                        .param("purposeKey", PURPOSE_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].subjectId").value(alice.subject().subjectId()))
                .andExpect(jsonPath("$[0].purposeKey").value(PURPOSE_A))
                .andExpect(jsonPath("$[0].consentVersionId").value(VERSION));

        mvc.perform(get(OperatorConsentEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("subjectId", bob.subject().subjectId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void historyIncludesWithdrawalAndTimeFilter() throws Exception {
        mvc.perform(get(OperatorConsentEndpoint.PATH + "/history")
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("subjectId", bob.subject().subjectId())
                        .param("purposeKey", PURPOSE_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].eventKind").value(ConsentEventKind.WITHDRAW.name()))
                .andExpect(jsonPath("$[1].eventKind").value(ConsentEventKind.GRANT.name()));

        mvc.perform(get(OperatorConsentEndpoint.PATH + "/history")
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("subjectId", bob.subject().subjectId())
                        .param("from", T1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventKind").value(ConsentEventKind.WITHDRAW.name()));
    }

    @Test
    void missingPermissionIsDeniedAndOtherTenantIsHidden() throws Exception {
        mvc.perform(get(OperatorConsentEndpoint.PATH)
                        .with(asOperator(none))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden());

        mvc.perform(get(OperatorConsentEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isForbidden());
    }

    @Test
    void reservedOrMissingTenantIsRejectedAndGrantRouteDoesNotExist() throws Exception {
        mvc.perform(get(OperatorConsentEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, "platform"))
                .andExpect(status().isForbidden());

        mvc.perform(get(OperatorConsentEndpoint.PATH).with(asOperator(reader)))
                .andExpect(status().isForbidden());

        mvc.perform(post(OperatorConsentEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType("application/json")
                        .content("{\"purposeKey\":\"privacy-notice\",\"consentVersionId\":\"v1\"}"))
                .andExpect(status().isMethodNotAllowed());
    }

    private void grant(OperatorPrincipal operator, String tenantId) {
        jdbc.update(
                "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                operator.subjectId(),
                tenantId);
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
