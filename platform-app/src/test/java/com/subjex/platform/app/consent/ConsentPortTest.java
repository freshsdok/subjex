package com.subjex.platform.app.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.identity.enduser.EndUserAccountStore;
import com.subjex.platform.app.identity.enduser.EndUserConsentEndpoint;
import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.app.identity.enduser.EndUserRecord;
import com.subjex.platform.app.identity.enduser.EndUserTenantAccessDeniedException;
import com.subjex.platform.app.identity.enduser.JdbcEndUserAccountStore;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.consent.ConsentCrossTenantDeniedException;
import com.subjex.platform.contract.consent.ConsentEventKind;
import com.subjex.platform.contract.consent.ConsentPort;
import com.subjex.platform.contract.consent.ConsentRecord;
import com.subjex.platform.contract.consent.ConsentRequiredException;
import com.subjex.platform.contract.identity.Identity;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
 * ConsentPortTest — purpose/gate WP8-1: grant, require, withdraw, re-grant, append-only history, tenant isolation, and end-user routes.
 * 目的/门禁 WP8-1：授予、require、撤销、再授予、追加式历史、租户隔离与终端路由。
 */
class ConsentPortTest {

    private static final String TENANT_A = "tenant-wp81-a";
    private static final String TENANT_B = "tenant-wp81-b";
    private static final Instant T0 = Instant.parse("2026-11-20T08:00:00Z");
    private static final String PURPOSE = "analytics";
    private static final String VERSION_1 = "v1";
    private static final String VERSION_2 = "v2";

    private JdbcTemplate jdbc;
    private ConsentPort port;
    private MockMvc mvc;
    private EndUserRecord alice;
    private EndUserRecord bob;
    private EndUserRecord carol;
    private Identity aliceIdentity;

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
        AtomicInteger ticks = new AtomicInteger();
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
                return T0.plusSeconds(ticks.getAndIncrement());
            }
        };
        JdbcEndUserAccountStore accounts = new JdbcEndUserAccountStore(jdbc, tx, clock);
        port = new JdbcConsentPort(jdbc, tx, clock, new JdbcAuditPort(jdbc));
        mvc = MockMvcBuilders.standaloneSetup(new EndUserConsentEndpoint(port, accounts))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
        alice = accounts.provision(TENANT_A, "Alice");
        bob = accounts.provision(TENANT_A, "Bob");
        carol = accounts.provision(TENANT_B, "Carol");
        aliceIdentity = accounts.findActiveIdentity(alice.account().accountId(), TENANT_A).orElseThrow();
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void grantRequireWithdrawRegrantAndAppendOnlyHistory() {
        ConsentRecord granted = port.grant(
                TENANT_A, alice.subject().subjectId(), PURPOSE, VERSION_1, aliceIdentity.identityId());
        port.require(TENANT_A, alice.subject().subjectId(), PURPOSE, null);
        port.require(TENANT_A, alice.subject().subjectId(), PURPOSE, VERSION_1);
        assertThatThrownBy(
                        () -> port.require(TENANT_A, alice.subject().subjectId(), PURPOSE, VERSION_2))
                .isInstanceOf(ConsentRequiredException.class)
                .hasMessage(ConsentRequiredException.REASON);

        port.withdraw(TENANT_A, alice.subject().subjectId(), PURPOSE, aliceIdentity.identityId());
        assertThat(port.current(TENANT_A, alice.subject().subjectId(), PURPOSE)).isEmpty();
        assertThatThrownBy(() -> port.require(TENANT_A, alice.subject().subjectId(), PURPOSE, null))
                .isInstanceOf(ConsentRequiredException.class);

        ConsentRecord again = port.grant(
                TENANT_A, alice.subject().subjectId(), PURPOSE, VERSION_2, aliceIdentity.identityId());
        port.require(TENANT_A, alice.subject().subjectId(), PURPOSE, VERSION_2);
        assertThat(again.consentVersionId()).isEqualTo(VERSION_2);

        var history = port.history(TENANT_A, alice.subject().subjectId(), PURPOSE);
        assertThat(history).hasSize(3);
        assertThat(history.get(0).eventKind()).isEqualTo(ConsentEventKind.GRANT);
        assertThat(history.get(0).consentVersionId()).isEqualTo(VERSION_1);
        assertThat(history.get(0).eventId()).isEqualTo(granted.eventId());
        assertThat(history.get(1).eventKind()).isEqualTo(ConsentEventKind.WITHDRAW);
        assertThat(history.get(2).eventKind()).isEqualTo(ConsentEventKind.GRANT);
        assertThat(history.get(2).eventId()).isEqualTo(again.eventId());
        assertThat(history.get(0).occurredAt()).isBefore(history.get(1).occurredAt());
        assertThat(history.get(1).occurredAt()).isBefore(history.get(2).occurredAt());
        // No updates or deletes of past events — 不改、不删既有事件.
        Integer eventRows = jdbc.queryForObject("SELECT COUNT(*) FROM consent_event", Integer.class);
        assertThat(eventRows).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_entry WHERE action_name = ?",
                        Integer.class,
                        JdbcConsentPort.AUDIT_GRANT))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_entry WHERE action_name = ?",
                        Integer.class,
                        JdbcConsentPort.AUDIT_WITHDRAW))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isZero();
    }

    @Test
    void crossTenantOtherSubjectPlatformAndMissingTenantAreRejected() {
        port.grant(TENANT_A, alice.subject().subjectId(), PURPOSE, VERSION_1, aliceIdentity.identityId());
        assertThat(port.listCurrent(TENANT_A, bob.subject().subjectId())).isEmpty();
        assertThat(port.history(TENANT_A, bob.subject().subjectId(), PURPOSE)).isEmpty();

        assertThatThrownBy(
                        () -> port.current(TENANT_B, alice.subject().subjectId(), PURPOSE))
                .isInstanceOf(ConsentCrossTenantDeniedException.class);
        assertThatThrownBy(
                        () -> port.current(TENANT_A, carol.subject().subjectId(), PURPOSE))
                .isInstanceOf(ConsentCrossTenantDeniedException.class);
        assertThatThrownBy(() -> port.require(TENANT_B, alice.subject().subjectId(), PURPOSE, null))
                .isInstanceOf(ConsentCrossTenantDeniedException.class);

        assertThatThrownBy(
                        () -> port.grant(
                                EndUserAccountStore.RESERVED_TENANT,
                                alice.subject().subjectId(),
                                PURPOSE,
                                VERSION_1,
                                aliceIdentity.identityId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform");
        assertThatThrownBy(
                        () -> port.grant(
                                "  ",
                                alice.subject().subjectId(),
                                PURPOSE,
                                VERSION_1,
                                aliceIdentity.identityId()))
                .isInstanceOf(TenantMissingException.class);
        assertThatThrownBy(
                        () -> port.grant(
                                "missing-tenant",
                                alice.subject().subjectId(),
                                PURPOSE,
                                VERSION_1,
                                aliceIdentity.identityId()))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void endUserRoutesOwnSubjectOnlyAndRejectOperator() throws Exception {
        mvc.perform(post(EndUserConsentEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purposeKey\":\"analytics\",\"consentVersionId\":\"v1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposeKey").value(PURPOSE))
                .andExpect(jsonPath("$.consentVersionId").value(VERSION_1))
                .andExpect(jsonPath("$.subjectId").value(alice.subject().subjectId()));

        mvc.perform(get(EndUserConsentEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].purposeKey").value(PURPOSE));

        mvc.perform(get(EndUserConsentEndpoint.PATH)
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(get(EndUserConsentEndpoint.PATH + "/history")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("purposeKey", PURPOSE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventKind").value("GRANT"));

        mvc.perform(post(EndUserConsentEndpoint.PATH + "/" + PURPOSE + "/withdraw")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventKind").value("WITHDRAW"));

        mvc.perform(get(EndUserConsentEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        OperatorPrincipal operator =
                new OperatorPrincipal("op-alice", "hash", "op-id", "op-sub", Set.of(), true);
        mvc.perform(get(EndUserConsentEndpoint.PATH)
                        .with(asPrincipal(operator))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("end-user-authentication-required"));

        mvc.perform(get(EndUserConsentEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, EndUserAccountStore.RESERVED_TENANT))
                .andExpect(status().isBadRequest());
        mvc.perform(get(EndUserConsentEndpoint.PATH).with(asEndUser(alice)))
                .andExpect(status().isForbidden());
        mvc.perform(get(EndUserConsentEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(EndUserTenantAccessDeniedException.REASON));
    }

    private RequestPostProcessor asEndUser(EndUserRecord record) {
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
