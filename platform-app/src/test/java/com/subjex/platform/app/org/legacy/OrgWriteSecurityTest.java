package com.subjex.platform.app.org.legacy;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.AccessDecision;
import com.subjex.platform.app.security.LocalOperatorSeeder;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.org.legacy.OrgScope;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.security.SqlRbacPolicyEngine;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OrgWriteSecurityTest — 组织写接口：401、无 org.write 403、操作员 200、缺 tenantId 400、跨租户父节点 400。
 */
@WebMvcTest(controllers = OrgApiEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    OrgWriteSecurityTest.GuardConfiguration.class
})
class OrgWriteSecurityTest {

    private static final String BARE = "platform-bare-org-write";
    private static final String BARE_PASSWORD = "bare-pass";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private OperatorTenantAccess tenantAccess;

    @MockitoBean
    private JdbcOrgDirectory directory;

    @MockitoBean
    private OperatorActionAudit audit;

    @BeforeEach
    void bareOperatorWithoutOrgWrite() {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account WHERE login_name = ?", Integer.class, BARE);
        if (exists != null && exists == 0) {
            jdbc.update(
                    "INSERT INTO account (account_id, login_name, account_state) VALUES ('account-bare-org-w', ?, 'ACTIVE')",
                    BARE);
            jdbc.update(
                    "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES ('subject-bare-org-w', 'Bare', 'PERSON')");
            jdbc.update(
                    """
                    INSERT INTO subject_identity (identity_id, account_id, subject_id, tenant_id, identity_state)
                    VALUES ('identity-bare-org-w', 'account-bare-org-w', 'subject-bare-org-w', 'platform', 'ACTIVE')
                    """);
            jdbc.update(
                    "INSERT INTO operator_credential (account_id, password_hash) VALUES ('account-bare-org-w', ?)",
                    passwordEncoder.encode(BARE_PASSWORD));
            // No subject_role → no permissions (including org.write).
        }
        jdbc.update("DELETE FROM operator_tenant_grant WHERE subject_id = ?", "subject-viewer");
        tenantAccess.ensureGrant(LocalOperatorSeeder.SUBJECT_ID, OperatorTenantAccess.ALL_TENANTS);
        // Platform operator without memberships: explicit UNRESTRICTED (AUTH-03 — never implied by null).
        when(directory.resolveSelfAndDescendants(anyString(), eq(LocalOperatorSeeder.SUBJECT_ID)))
                .thenReturn(OrgScope.unrestricted());
    }

    @Test
    void unauthenticatedGets401() throws Exception {
        mockMvc.perform(put(OrgApiEndpoint.PATH + "/units/u-root")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitName\":\"Root\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(OrgApiEndpoint.PATH + "/memberships")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\"sub-a\",\"orgUnitId\":\"u-root\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(OrgApiEndpoint.PATH + "/memberships")
                        .param("tenantId", "acme")
                        .param("subjectId", "sub-a")
                        .param("orgUnitId", "u-root"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(directory);
    }

    @Test
    void bareWithoutOrgWriteGets403() throws Exception {
        mockMvc.perform(put(OrgApiEndpoint.PATH + "/units/u-root")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitName\":\"Root\"}")
                        .with(httpBasic(BARE, BARE_PASSWORD)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(OrgApiEndpoint.PATH + "/memberships")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\"sub-a\",\"orgUnitId\":\"u-root\"}")
                        .with(httpBasic(BARE, BARE_PASSWORD)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(directory);
    }

    @Test
    void readerWithOrgReadOnlyGets403OnWrite() throws Exception {
        mockMvc.perform(put(OrgApiEndpoint.PATH + "/units/u-root")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitName\":\"Root\"}")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(directory);
    }

    @Test
    void operatorCanUpsertUnitAndMembership() throws Exception {
        when(directory.upsertUnit(eq("acme"), eq("u-root"), isNull(), eq("Root"), isNull()))
                .thenReturn(new OrgUnit("acme", "u-root", null, "Root", "ACTIVE"));
        when(directory.upsertMembership(eq("acme"), eq("sub-a"), eq("u-root"), isNull()))
                .thenReturn(new OrgMembership("acme", "sub-a", "u-root", "ACTIVE"));
        when(directory.removeMembership("acme", "sub-a", "u-root")).thenReturn(true);

        mockMvc.perform(put(OrgApiEndpoint.PATH + "/units/u-root")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitName\":\"Root\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("acme"))
                .andExpect(jsonPath("$.orgUnitId").value("u-root"))
                .andExpect(jsonPath("$.unitName").value("Root"))
                .andExpect(jsonPath("$.unitState").value("ACTIVE"));
        verify(audit).record(any(), eq("org.unit.upsert"), eq("acme/u-root"), eq(AuditOutcome.ALLOWED));

        mockMvc.perform(put(OrgApiEndpoint.PATH + "/memberships")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\"sub-a\",\"orgUnitId\":\"u-root\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectId").value("sub-a"))
                .andExpect(jsonPath("$.orgUnitId").value("u-root"));
        verify(audit).record(any(), eq("org.membership.upsert"), eq("acme/sub-a/u-root"), eq(AuditOutcome.ALLOWED));

        mockMvc.perform(delete(OrgApiEndpoint.PATH + "/memberships")
                        .param("tenantId", "acme")
                        .param("subjectId", "sub-a")
                        .param("orgUnitId", "u-root")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk());
        verify(audit).record(any(), eq("org.membership.remove"), eq("acme/sub-a/u-root"), eq(AuditOutcome.ALLOWED));
    }

    @Test
    void missingTenantIdIs400() throws Exception {
        mockMvc.perform(put(OrgApiEndpoint.PATH + "/units/u-root")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitName\":\"Root\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("tenantId required"));
        verify(directory, never()).upsertUnit(anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void crossTenantParentRejectedAs400() throws Exception {
        when(directory.upsertUnit(eq("acme"), eq("u-cross"), eq("u-other"), eq("Cross"), eq("ACTIVE")))
                .thenThrow(new IllegalArgumentException("parent org unit not found"));

        mockMvc.perform(put(OrgApiEndpoint.PATH + "/units/u-cross")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentOrgUnitId\":\"u-other\",\"unitName\":\"Cross\",\"unitState\":\"ACTIVE\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("parent org unit not found"));
    }

    @Test
    void missingMembershipDeleteIs404() throws Exception {
        when(directory.removeMembership("acme", "sub-a", "u-root")).thenReturn(false);

        mockMvc.perform(delete(OrgApiEndpoint.PATH + "/memberships")
                        .param("tenantId", "acme")
                        .param("subjectId", "sub-a")
                        .param("orgUnitId", "u-root")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNotFound());
        verify(audit, never()).record(any(), eq("org.membership.remove"), anyString(), any());
    }


    @Test
    void scopedWriteOutsideScopeIs403() throws Exception {
        OrgScope scope = OrgScope.selfAndDescendants(List.of("u-eng"), List.of("u-eng", "u-team"));
        when(directory.resolveSelfAndDescendants("acme", LocalOperatorSeeder.SUBJECT_ID)).thenReturn(scope);
        when(directory.unitExists("acme", "u-root")).thenReturn(true);

        mockMvc.perform(put(OrgApiEndpoint.PATH + "/units/u-root")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitName\":\"Root\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.kind").value("permission_denied"))
                .andExpect(jsonPath("$.denyReason").value(AccessDecision.DENY_ORG_OUT_OF_SCOPE))
                .andExpect(jsonPath("$.orgScope.mode").value(OrgScope.MODE_SELF_AND_DESCENDANTS))
                .andExpect(jsonPath("$.orgScope.rootOrganizationIds[0]").value("u-eng"));
        verify(directory, never()).upsertUnit(anyString(), anyString(), any(), anyString(), any());

        mockMvc.perform(put(OrgApiEndpoint.PATH + "/memberships")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\"sub-a\",\"orgUnitId\":\"u-root\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.denyReason").value(AccessDecision.DENY_ORG_OUT_OF_SCOPE));
        verify(directory, never()).upsertMembership(anyString(), anyString(), anyString(), any());
    }

    @Test
    void scopedWriteInsideScopeSucceeds() throws Exception {
        OrgScope scope = OrgScope.selfAndDescendants(List.of("u-eng"), List.of("u-eng", "u-team"));
        when(directory.resolveSelfAndDescendants("acme", LocalOperatorSeeder.SUBJECT_ID)).thenReturn(scope);
        when(directory.unitExists("acme", "u-eng")).thenReturn(true);
        when(directory.upsertUnit(eq("acme"), eq("u-eng"), eq("u-root"), eq("Eng"), isNull()))
                .thenReturn(new OrgUnit("acme", "u-eng", "u-root", "Eng", "ACTIVE"));
        when(directory.upsertMembership(eq("acme"), eq("sub-b"), eq("u-team"), isNull()))
                .thenReturn(new OrgMembership("acme", "sub-b", "u-team", "ACTIVE"));

        mockMvc.perform(put(OrgApiEndpoint.PATH + "/units/u-eng")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentOrgUnitId\":\"u-root\",\"unitName\":\"Eng\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orgUnitId").value("u-eng"));

        mockMvc.perform(put(OrgApiEndpoint.PATH + "/memberships")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\"sub-b\",\"orgUnitId\":\"u-team\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orgUnitId").value("u-team"));
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }

        @Bean
        PolicyEngine policyEngine(TenantGuard tenantGuard, OperatorTenantAccess tenantAccess) {
            return new SqlRbacPolicyEngine(tenantGuard, tenantAccess);
        }
    }
}
