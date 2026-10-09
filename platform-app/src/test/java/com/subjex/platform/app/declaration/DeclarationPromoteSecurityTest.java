package com.subjex.platform.app.declaration;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.LocalOperatorSeeder;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;

import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * DeclarationPromoteSecurityTest — 晋升接口：401/403、declaration.promote、租户授权、404/409、审计。
 */
@WebMvcTest(controllers = DeclarationPromoteEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    DeclarationPromoteSecurityTest.GuardConfiguration.class
})
class DeclarationPromoteSecurityTest {

    private static final String BARE = "platform-bare-promote";
    private static final String BARE_PASSWORD = "bare-pass";

    private static final String PATH = DeclarationPromoteEndpoint.PATH + "/entity/demo-ticket";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private OperatorTenantAccess tenantAccess;

    @MockitoBean
    private DeclarationPromoteService promoteService;

    @MockitoBean
    private JdbcDeclarationPromoteApprovalStore approvals;

    @MockitoBean
    private JdbcDeclarationStore store;

    @MockitoBean
    private OperatorActionAudit audit;

    @BeforeEach
    void bareOperatorWithoutPromotePerms() {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account WHERE login_name = ?", Integer.class, BARE);
        if (exists != null && exists == 0) {
            jdbc.update(
                    "INSERT INTO account (account_id, login_name, account_state) VALUES ('account-bare-promote', ?, 'ACTIVE')",
                    BARE);
            jdbc.update(
                    "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES ('subject-bare-promote', 'Bare', 'PERSON')");
            jdbc.update(
                    """
                    INSERT INTO subject_identity (identity_id, account_id, subject_id, tenant_id, identity_state)
                    VALUES ('identity-bare-promote', 'account-bare-promote', 'subject-bare-promote', 'platform', 'ACTIVE')
                    """);
            jdbc.update(
                    "INSERT INTO operator_credential (account_id, password_hash) VALUES ('account-bare-promote', ?)",
                    passwordEncoder.encode(BARE_PASSWORD));
        }
        jdbc.update("DELETE FROM operator_tenant_grant WHERE subject_id = ?", "subject-viewer");
    }

    @Test
    void unauthenticatedGets401() throws Exception {
        mockMvc.perform(post(PATH + "/promote").param("tenantId", "acme"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH + "/promotes").param("tenantId", "acme"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(promoteService, store);
    }

    @Test
    void bareWithoutPromoteGets403OnPost() throws Exception {
        mockMvc.perform(post(PATH + "/promote")
                        .param("tenantId", "acme")
                        .with(httpBasic(BARE, BARE_PASSWORD)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(promoteService);
    }

    @Test
    void viewerWithReadCanListPromotesButNotPromote() throws Exception {
        tenantAccess.ensureGrant("subject-viewer", "acme");
        when(store.listPromotes("acme", DeclarationKind.ENTITY, "demo-ticket"))
                .thenReturn(List.of(samplePromote(1)));

        mockMvc.perform(get(PATH + "/promotes")
                        .param("tenantId", "acme")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promotes[0].revision").value(1))
                .andExpect(jsonPath("$.promotes[0].gitCommitSha").value("abc1234"));

        mockMvc.perform(post(PATH + "/promote")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvalId\":\"apr-1\"}")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
        verify(promoteService, never()).promoteLatest(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void missingTenantIdIs400() throws Exception {
        mockMvc.perform(post(PATH + "/promote").with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("tenantId required"));
        verifyNoInteractions(promoteService);
    }

    @Test
    void missingRevisionIs404() throws Exception {
        when(store.latest("acme", DeclarationKind.ENTITY, "demo-ticket")).thenReturn(Optional.empty());
        mockMvc.perform(post(PATH + "/promote")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvalId\":\"apr-1\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNotFound());
        verify(promoteService, never()).promoteLatest(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void alreadyPromotedIs409() throws Exception {
        when(store.latest("acme", DeclarationKind.ENTITY, "demo-ticket"))
                .thenReturn(Optional.of(sampleRevision(1, JdbcDeclarationStore.PROMOTED_STATE)));
        when(promoteService.promoteLatest(eq("acme"), eq(DeclarationKind.ENTITY), eq("demo-ticket"), eq(LocalOperatorSeeder.SUBJECT_ID), anyString()))
                .thenThrow(new DeclarationAlreadyPromoted("declaration already promoted: entity/demo-ticket@r1"));

        mockMvc.perform(post(PATH + "/promote")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvalId\":\"apr-1\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("declaration already promoted: entity/demo-ticket@r1"));
        verify(audit, never()).record(any(), anyString(), anyString(), any());
    }

    @Test
    void promoterCanPromoteLatestAndAudits() throws Exception {
        when(store.latest("acme", DeclarationKind.ENTITY, "demo-ticket"))
                .thenReturn(Optional.of(sampleRevision(2, JdbcDeclarationStore.DRAFT_STATE)));
        when(promoteService.promoteLatest(eq("acme"), eq(DeclarationKind.ENTITY), eq("demo-ticket"), eq(LocalOperatorSeeder.SUBJECT_ID), anyString()))
                .thenReturn(new DeclarationPromoteService.DeclarationPromoteResult(
                        "acme", DeclarationKind.ENTITY, "demo-ticket", 2, "deadbeef"));

        mockMvc.perform(post(PATH + "/promote")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvalId\":\"apr-1\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("acme"))
                .andExpect(jsonPath("$.declarationKind").value("entity"))
                .andExpect(jsonPath("$.declarationKey").value("demo-ticket"))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.gitCommitSha").value("deadbeef"))
                .andExpect(jsonPath("$.draftState").value("PROMOTED"));

        verify(audit)
                .record(
                        any(),
                        eq("declaration.promote"),
                        eq("entity/demo-ticket@2"),
                        eq(AuditOutcome.ALLOWED));
    }

    @Test
    void promoterCanPromoteExplicitRevision() throws Exception {
        when(store.findRevision("acme", DeclarationKind.ENTITY, "demo-ticket", 1))
                .thenReturn(Optional.of(sampleRevision(1, JdbcDeclarationStore.DRAFT_STATE)));
        when(promoteService.promoteRevision(
                        eq("acme"),
                        eq(DeclarationKind.ENTITY),
                        eq("demo-ticket"),
                        eq(1),
                        eq(LocalOperatorSeeder.SUBJECT_ID),
                        anyString()))
                .thenReturn(new DeclarationPromoteService.DeclarationPromoteResult(
                        "acme", DeclarationKind.ENTITY, "demo-ticket", 1, "cafebabe"));

        mockMvc.perform(post(PATH + "/promote")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":1,\"approvalId\":\"apr-1\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.gitCommitSha").value("cafebabe"));

        verify(promoteService)
                .promoteRevision(eq("acme"), eq(DeclarationKind.ENTITY), eq("demo-ticket"), eq(1), eq(LocalOperatorSeeder.SUBJECT_ID), anyString());
        verify(promoteService, never()).promoteLatest(anyString(), any(), anyString(), anyString(), anyString());
        verify(audit)
                .record(any(), eq("declaration.promote"), eq("entity/demo-ticket@1"), eq(AuditOutcome.ALLOWED));
    }

    private static DeclarationRevision sampleRevision(int revision, String state) {
        return new DeclarationRevision(
                "acme",
                DeclarationKind.ENTITY,
                "demo-ticket",
                revision,
                "entityKey: demo-ticket\n",
                state,
                Instant.parse("2026-10-08T12:00:00Z"),
                LocalOperatorSeeder.SUBJECT_ID);
    }

    private static DeclarationPromote samplePromote(int revision) {
        return new DeclarationPromote(
                "acme",
                DeclarationKind.ENTITY,
                "demo-ticket",
                revision,
                "abc1234",
                Instant.parse("2026-10-08T13:00:00Z"),
                LocalOperatorSeeder.SUBJECT_ID);
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
