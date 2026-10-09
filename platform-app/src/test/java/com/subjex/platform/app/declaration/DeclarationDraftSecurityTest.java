package com.subjex.platform.app.declaration;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.entity.declare.EntityField;
import com.subjex.entity.declare.EntityFieldKind;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.platform.app.security.LocalOperatorSeeder;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
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
 * DeclarationDraftSecurityTest — 声明草稿接口：401/403/400、declaration.read|write、租户授权、YAML 校验。
 */
@WebMvcTest(controllers = DeclarationDraftEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    DeclarationDraftSecurityTest.GuardConfiguration.class
})
class DeclarationDraftSecurityTest {

    private static final String BARE = "platform-bare";
    private static final String BARE_PASSWORD = "bare-pass";

    private static final String VALID_ENTITY_YAML =
            """
            entityKey: demo-ticket
            tableName: demo_ticket
            version: 2
            permission: page.read
            tenantScoped: false
            fields:
              - name: ticketId
                kind: text
                required: true
                maxLength: 64
              - name: title
                kind: text
                required: true
                maxLength: 80
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private OperatorTenantAccess tenantAccess;

    @MockitoBean
    private JdbcDeclarationStore store;

    @MockitoBean
    private EffectiveDeclarationService effective;

    @MockitoBean
    private DeclarationMigrationAutoEnqueueService migrationAutoEnqueue;

    @BeforeEach
    void bareOperatorWithoutDeclarationPerms() {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account WHERE login_name = ?", Integer.class, BARE);
        if (exists != null && exists == 0) {
            jdbc.update(
                    "INSERT INTO account (account_id, login_name, account_state) VALUES ('account-bare', ?, 'ACTIVE')",
                    BARE);
            jdbc.update(
                    "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES ('subject-bare', 'Bare', 'PERSON')");
            jdbc.update(
                    """
                    INSERT INTO subject_identity (identity_id, account_id, subject_id, tenant_id, identity_state)
                    VALUES ('identity-bare', 'account-bare', 'subject-bare', 'platform', 'ACTIVE')
                    """);
            jdbc.update(
                    "INSERT INTO operator_credential (account_id, password_hash) VALUES ('account-bare', ?)",
                    passwordEncoder.encode(BARE_PASSWORD));
        }
        // Viewer: declaration.read but no tenant grant until a test adds one.
        jdbc.update("DELETE FROM operator_tenant_grant WHERE subject_id = ?", "subject-viewer");
    }

    @Test
    void unauthenticatedGets401() throws Exception {
        mockMvc.perform(get(DeclarationDraftEndpoint.PATH).param("tenantId", "acme").param("kind", "entity"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
    }

    @Test
    void bareWithoutDeclarationReadGets403() throws Exception {
        mockMvc.perform(get(DeclarationDraftEndpoint.PATH)
                        .param("tenantId", "acme")
                        .param("kind", "entity")
                        .with(httpBasic(BARE, BARE_PASSWORD)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }

    @Test
    void viewerWithoutTenantGrantGets403() throws Exception {
        mockMvc.perform(get(DeclarationDraftEndpoint.PATH)
                        .param("tenantId", "acme")
                        .param("kind", "entity")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }

    @Test
    void missingTenantIdIs400() throws Exception {
        mockMvc.perform(get(DeclarationDraftEndpoint.PATH)
                        .param("kind", "entity")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("tenantId required"));
        verifyNoInteractions(store);
    }

    @Test
    void missingKindIs400() throws Exception {
        mockMvc.perform(get(DeclarationDraftEndpoint.PATH)
                        .param("tenantId", "acme")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("kind required"));
        verifyNoInteractions(store);
    }

    @Test
    void readerWithGrantCanListAndGetEffective() throws Exception {
        tenantAccess.ensureGrant("subject-viewer", "acme");
        when(store.listLatest("acme", DeclarationKind.ENTITY))
                .thenReturn(List.of(sampleRevision(1)));
        when(effective.effectiveEntity("acme", "demo-ticket"))
                .thenReturn(Optional.of(sampleEntity(80)));
        when(effective.resolutionSource("acme", DeclarationKind.ENTITY, "demo-ticket"))
                .thenReturn(EffectiveDeclarationService.SOURCE_DRAFT);

        mockMvc.perform(get(DeclarationDraftEndpoint.PATH)
                        .param("tenantId", "acme")
                        .param("kind", "entity")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisions[0].declarationKey").value("demo-ticket"))
                .andExpect(jsonPath("$.revisions[0].revision").value(1));

        mockMvc.perform(get(DeclarationDraftEndpoint.PATH + "/entity/demo-ticket/effective")
                        .param("tenantId", "acme")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("demo-ticket"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.fieldNames[1]").value("title"))
                .andExpect(jsonPath("$.fromDraft").value(true))
                .andExpect(jsonPath("$.source").value("draft"));

        mockMvc.perform(put(DeclarationDraftEndpoint.PATH + "/entity/demo-ticket")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"yamlBody\":" + jsonString(VALID_ENTITY_YAML) + "}")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void writerCanSaveValidYamlAndRejectsInvalid() throws Exception {
        when(store.saveDraft(eq("acme"), eq(DeclarationKind.ENTITY), eq("demo-ticket"), anyString(), anyString()))
                .thenReturn(sampleRevision(1));
        when(migrationAutoEnqueue.planForEntityYaml(eq("acme"), eq("demo-ticket"), anyString()))
                .thenReturn(List.of());
        when(migrationAutoEnqueue.enqueuePlanned(any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(put(DeclarationDraftEndpoint.PATH + "/entity/demo-ticket")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"yamlBody\":" + jsonString(VALID_ENTITY_YAML) + "}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.declarationKind").value("entity"))
                .andExpect(jsonPath("$.draftState").value("DRAFT"));

        verify(store)
                .saveDraft(
                        eq("acme"),
                        eq(DeclarationKind.ENTITY),
                        eq("demo-ticket"),
                        anyString(),
                        eq(LocalOperatorSeeder.SUBJECT_ID));

        mockMvc.perform(put(DeclarationDraftEndpoint.PATH + "/entity/demo-ticket")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"yamlBody\":\"not: valid: entity\\n\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest());
    }


    @Test
    void unknownPermissionOnEntityDraftIs400() throws Exception {
        String yaml =
                """
                entityKey: demo-ticket
                tableName: demo_ticket
                version: 2
                permission: invented.perm
                tenantScoped: false
                fields:
                  - name: ticketId
                    kind: text
                    required: true
                    maxLength: 64
                """;
        mockMvc.perform(put(DeclarationDraftEndpoint.PATH + "/entity/demo-ticket")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"yamlBody\":" + jsonString(yaml) + "}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("platform catalog")));
        verifyNoInteractions(store);
    }

    @Test
    void formEffectExtensionInvokeOnDraftIs400() throws Exception {
        String yaml =
                """
                formKey: demo-ticket
                titleEn: Demo
                titleZh: 演示
                version: 1
                permission: page.read
                tenantScoped: true
                domainAction: entity.record.upsert
                entityKey: demo-ticket
                fields:
                  - name: ticketId
                    kind: text
                    required: true
                    maxLength: 64
                effects:
                  - key: extension.invoke
                    params:
                      extensionName: task-delivery
                """;
        mockMvc.perform(put(DeclarationDraftEndpoint.PATH + "/form/demo-ticket")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"yamlBody\":" + jsonString(yaml) + "}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("whitelist")));
        verifyNoInteractions(store);
    }

    @Test
    void latestMissingIs404() throws Exception {
        when(store.latest("acme", DeclarationKind.ENTITY, "missing")).thenReturn(Optional.empty());
        mockMvc.perform(get(DeclarationDraftEndpoint.PATH + "/entity/missing")
                        .param("tenantId", "acme")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNotFound());
    }

    private static DeclarationRevision sampleRevision(int revision) {
        return new DeclarationRevision(
                "acme",
                DeclarationKind.ENTITY,
                "demo-ticket",
                revision,
                VALID_ENTITY_YAML,
                JdbcDeclarationStore.DRAFT_STATE,
                Instant.parse("2026-10-08T12:00:00Z"),
                LocalOperatorSeeder.SUBJECT_ID);
    }

    private static RenderedEntity sampleEntity(int titleMax) {
        return new RenderedEntity(
                "demo-ticket",
                "demo_ticket",
                2,
                "page.read",
                false,
                "DemoTicket",
                List.of(
                        new EntityField("ticketId", EntityFieldKind.TEXT, true, 64),
                        new EntityField("title", EntityFieldKind.TEXT, true, titleMax)));
    }

    private static String jsonString(String raw) {
        return "\""
                + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                + "\"";
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
