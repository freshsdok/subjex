package com.subjex.platform.app.declaration;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
 * DeclarationMigrationSecurityTest — 迁移队列接口：401/403、declaration.migrate、入队/审阅/执行。
 */
@WebMvcTest(controllers = DeclarationMigrationEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    DeclarationMigrationSecurityTest.GuardConfiguration.class
})
class DeclarationMigrationSecurityTest {

    private static final String BARE = "platform-bare-migrate";
    private static final String BARE_PASSWORD = "bare-pass";

    private static final String PATH = DeclarationMigrationEndpoint.PATH + "/entity/demo-ticket";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private OperatorTenantAccess tenantAccess;

    @MockitoBean
    private JdbcDeclarationMigrationStore migrationStore;

    @MockitoBean
    private JdbcDeclarationStore declarationStore;

    @MockitoBean
    private DeclarationMigrationApplyService applyService;

    @MockitoBean
    private OperatorActionAudit audit;

    @BeforeEach
    void bareOperatorWithoutMigratePerms() {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account WHERE login_name = ?", Integer.class, BARE);
        if (exists != null && exists == 0) {
            jdbc.update(
                    "INSERT INTO account (account_id, login_name, account_state) VALUES ('account-bare-migrate', ?, 'ACTIVE')",
                    BARE);
            jdbc.update(
                    "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES ('subject-bare-migrate', 'Bare', 'PERSON')");
            jdbc.update(
                    """
                    INSERT INTO subject_identity (identity_id, account_id, subject_id, tenant_id, identity_state)
                    VALUES ('identity-bare-migrate', 'account-bare-migrate', 'subject-bare-migrate', 'platform', 'ACTIVE')
                    """);
            jdbc.update(
                    "INSERT INTO operator_credential (account_id, password_hash) VALUES ('account-bare-migrate', ?)",
                    passwordEncoder.encode(BARE_PASSWORD));
        }
        jdbc.update("DELETE FROM operator_tenant_grant WHERE subject_id = ?", "subject-viewer");
    }

    @Test
    void unauthenticatedGets401() throws Exception {
        mockMvc.perform(get(PATH + "/migrations").param("tenantId", "acme"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH + "/migrations")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sqlText\":\"ALTER TABLE demo_ticket ADD COLUMN x INT\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH + "/migrations/mig-1/apply")
                        .param("tenantId", "acme"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(migrationStore, declarationStore, applyService);
    }

        @Test
    void bareWithoutMigrateGets403OnPost() throws Exception {
        mockMvc.perform(post(PATH + "/migrations")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sqlText\":\"ALTER TABLE demo_ticket ADD COLUMN x INT\"}")
                        .with(httpBasic(BARE, BARE_PASSWORD)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(PATH + "/migrations/mig-1/apply")
                        .param("tenantId", "acme")
                        .with(httpBasic(BARE, BARE_PASSWORD)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(migrationStore, applyService);
    }

    @Test
    void viewerWithReadCanListButNotEnqueueOrReview() throws Exception {
        tenantAccess.ensureGrant("subject-viewer", "acme");
        when(migrationStore.list("acme", DeclarationKind.ENTITY, "demo-ticket"))
                .thenReturn(List.of(sampleMigration(JdbcDeclarationMigrationStore.PENDING)));

        mockMvc.perform(get(PATH + "/migrations")
                        .param("tenantId", "acme")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.migrations[0].status").value("PENDING"))
                .andExpect(jsonPath("$.migrations[0].declarationRevision").value(2));

        mockMvc.perform(post(PATH + "/migrations")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sqlText\":\"ALTER TABLE demo_ticket ADD COLUMN x INT\"}")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(PATH + "/migrations/mig-1/review")
                        .param("tenantId", "acme")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(PATH + "/migrations/mig-1/apply")
                        .param("tenantId", "acme")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
        verify(migrationStore, never()).enqueue(anyString(), any(), anyString(), anyInt(), anyString(), anyString());
        verify(migrationStore, never()).markReviewed(anyString());
        verifyNoInteractions(applyService);
    }

    @Test
    void missingTenantIdIs400() throws Exception {
        mockMvc.perform(post(PATH + "/migrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sqlText\":\"ALTER TABLE demo_ticket ADD COLUMN x INT\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("tenantId required"));
        verifyNoInteractions(migrationStore);
    }

    @Test
    void missingRevisionIs404() throws Exception {
        when(declarationStore.latest("acme", DeclarationKind.ENTITY, "demo-ticket")).thenReturn(Optional.empty());
        mockMvc.perform(post(PATH + "/migrations")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sqlText\":\"ALTER TABLE demo_ticket ADD COLUMN x INT\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNotFound());
        verify(migrationStore, never()).enqueue(anyString(), any(), anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void operatorCanEnqueueLatestAndAudits() throws Exception {
        when(declarationStore.latest("acme", DeclarationKind.ENTITY, "demo-ticket"))
                .thenReturn(Optional.of(sampleRevision(2)));
        when(migrationStore.enqueue(
                        eq("acme"),
                        eq(DeclarationKind.ENTITY),
                        eq("demo-ticket"),
                        eq(2),
                        eq("ALTER TABLE demo_ticket ADD COLUMN x INT"),
                        eq(LocalOperatorSeeder.SUBJECT_ID)))
                .thenReturn(sampleMigration(JdbcDeclarationMigrationStore.PENDING));

        mockMvc.perform(post(PATH + "/migrations")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sqlText\":\"ALTER TABLE demo_ticket ADD COLUMN x INT\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.migrationId").value("mig-1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.declarationRevision").value(2));

        verify(audit)
                .record(
                        any(),
                        eq("declaration.migrate.enqueue"),
                        eq("entity/demo-ticket@2#mig-1"),
                        eq(AuditOutcome.ALLOWED));
    }

    @Test
    void operatorCanReviewPending() throws Exception {
        when(migrationStore.findById("mig-1"))
                .thenReturn(Optional.of(sampleMigration(JdbcDeclarationMigrationStore.PENDING)));
        when(migrationStore.markReviewed("mig-1"))
                .thenReturn(sampleMigration(JdbcDeclarationMigrationStore.REVIEWED));

        mockMvc.perform(post(PATH + "/migrations/mig-1/review")
                        .param("tenantId", "acme")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWED"));

        verify(audit)
                .record(any(), eq("declaration.migrate.review"), eq("entity/demo-ticket#mig-1"), eq(AuditOutcome.ALLOWED));
    }

    @Test
    void operatorCanApplyReviewedAndAudits() throws Exception {
        when(migrationStore.findById("mig-1"))
                .thenReturn(Optional.of(sampleMigration(JdbcDeclarationMigrationStore.REVIEWED)));
        when(applyService.apply("acme", DeclarationKind.ENTITY, "demo-ticket", "mig-1"))
                .thenReturn(sampleMigration(JdbcDeclarationMigrationStore.APPLIED));

        mockMvc.perform(post(PATH + "/migrations/mig-1/apply")
                        .param("tenantId", "acme")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));

        verify(audit)
                .record(any(), eq("declaration.migrate.apply"), eq("entity/demo-ticket#mig-1"), eq(AuditOutcome.ALLOWED));
    }

    @Test
    void applyRejectsDropViaService() throws Exception {
        when(migrationStore.findById("mig-1"))
                .thenReturn(Optional.of(sampleMigration(JdbcDeclarationMigrationStore.REVIEWED)));
        when(applyService.apply("acme", DeclarationKind.ENTITY, "demo-ticket", "mig-1"))
                .thenThrow(new IllegalArgumentException("sqlText must not contain DROP"));

        mockMvc.perform(post(PATH + "/migrations/mig-1/apply")
                        .param("tenantId", "acme")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("sqlText must not contain DROP"));
        verify(audit, never()).record(any(), eq("declaration.migrate.apply"), anyString(), any());
    }

    @Test
    void applyRejectsPendingNotReviewed() throws Exception {
        when(migrationStore.findById("mig-1"))
                .thenReturn(Optional.of(sampleMigration(JdbcDeclarationMigrationStore.PENDING)));
        when(applyService.apply("acme", DeclarationKind.ENTITY, "demo-ticket", "mig-1"))
                .thenThrow(new DeclarationMigrationNotReady(
                        "migration must be REVIEWED to apply (was PENDING): mig-1"));

        mockMvc.perform(post(PATH + "/migrations/mig-1/apply")
                        .param("tenantId", "acme")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value(
                        "migration must be REVIEWED to apply (was PENDING): mig-1"));
        verify(audit, never()).record(any(), eq("declaration.migrate.apply"), anyString(), any());
    }

    private static DeclarationRevision sampleRevision(int revision) {
        return new DeclarationRevision(
                "acme",
                DeclarationKind.ENTITY,
                "demo-ticket",
                revision,
                "entityKey: demo-ticket\n",
                JdbcDeclarationStore.DRAFT_STATE,
                Instant.parse("2026-10-08T12:00:00Z"),
                LocalOperatorSeeder.SUBJECT_ID);
    }

    private static DeclarationMigration sampleMigration(String status) {
        return new DeclarationMigration(
                "mig-1",
                "acme",
                DeclarationKind.ENTITY,
                "demo-ticket",
                2,
                "ALTER TABLE demo_ticket ADD COLUMN x INT",
                status,
                Instant.parse("2026-10-08T14:00:00Z"),
                LocalOperatorSeeder.SUBJECT_ID,
                Instant.parse("2026-10-08T14:00:00Z"),
                null,
                null);
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
