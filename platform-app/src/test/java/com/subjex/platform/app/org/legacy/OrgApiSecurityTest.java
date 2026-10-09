package com.subjex.platform.app.org.legacy;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.LocalOperatorSeeder;
import com.subjex.platform.app.org.legacy.OrgScope;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.security.SqlRbacPolicyEngine;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OrgApiSecurityTest — 组织只读接口：401 无凭据、403 无 org.read、有权限 200、缺 tenantId 400。
 */
@WebMvcTest(controllers = OrgApiEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    OrgApiSecurityTest.GuardConfiguration.class
})
class OrgApiSecurityTest {

    private static final String BARE = "platform-bare";
    private static final String BARE_PASSWORD = "bare-pass";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private JdbcOrgDirectory directory;

    @BeforeEach
    void bareOperatorWithoutOrgRead() {
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
            // No subject_role → no permissions (including org.read).
        }
        // Default: explicit UNRESTRICTED for actors without membership stubs (AUTH-03).
        when(directory.resolveSelfAndDescendants(anyString(), anyString())).thenReturn(OrgScope.unrestricted());
    }

    @Test
    void unauthenticatedGets401() throws Exception {
        mockMvc.perform(get(OrgApiEndpoint.PATH + "/units").param("tenantId", "acme"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(OrgApiEndpoint.PATH + "/memberships").param("tenantId", "acme"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(directory);
    }

    @Test
    void bareOperatorWithoutOrgReadGets403() throws Exception {
        mockMvc.perform(get(OrgApiEndpoint.PATH + "/units")
                        .param("tenantId", "acme")
                        .with(httpBasic(BARE, BARE_PASSWORD)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(OrgApiEndpoint.PATH + "/memberships")
                        .param("tenantId", "acme")
                        .with(httpBasic(BARE, BARE_PASSWORD)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(directory);
    }

    @Test
    void readerWithOrgReadCanListUnitsAndMemberships() throws Exception {
        when(directory.listUnits("acme"))
                .thenReturn(List.of(new OrgUnit("acme", "u-root", null, "Root", "ACTIVE")));
        when(directory.listMemberships("acme"))
                .thenReturn(List.of(new OrgMembership("acme", "sub-a", "u-root", "ACTIVE")));

        mockMvc.perform(get(OrgApiEndpoint.PATH + "/units")
                        .param("tenantId", "acme")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.units[0].tenantId").value("acme"))
                .andExpect(jsonPath("$.units[0].orgUnitId").value("u-root"))
                .andExpect(jsonPath("$.units[0].unitName").value("Root"));

        mockMvc.perform(get(OrgApiEndpoint.PATH + "/memberships")
                        .param("tenantId", "acme")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberships[0].subjectId").value("sub-a"))
                .andExpect(jsonPath("$.memberships[0].orgUnitId").value("u-root"));
    }

    @Test
    void subjectIdFilterUsesSubjectScopedList() throws Exception {
        when(directory.listMembershipsForSubject("acme", "sub-a"))
                .thenReturn(List.of(new OrgMembership("acme", "sub-a", "u-root", "ACTIVE")));

        mockMvc.perform(get(OrgApiEndpoint.PATH + "/memberships")
                        .param("tenantId", "acme")
                        .param("subjectId", "sub-a")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberships.length()").value(1))
                .andExpect(jsonPath("$.memberships[0].subjectId").value("sub-a"));

        verify(directory).listMembershipsForSubject("acme", "sub-a");
    }

    @Test
    void missingTenantIdIs400() throws Exception {
        when(directory.listUnits(null)).thenThrow(new IllegalArgumentException("tenantId required"));
        when(directory.listMemberships(null)).thenThrow(new IllegalArgumentException("tenantId required"));

        mockMvc.perform(get(OrgApiEndpoint.PATH + "/units").with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("tenantId required"));
        mockMvc.perform(get(OrgApiEndpoint.PATH + "/memberships").with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("tenantId required"));
    }


    @Test
    void scopedActorSeesOnlyUnitsInScope() throws Exception {
        OrgScope scope = OrgScope.selfAndDescendants(List.of("u-eng"), List.of("u-eng", "u-team"));
        when(directory.resolveSelfAndDescendants("acme", LocalOperatorSeeder.SUBJECT_ID)).thenReturn(scope);
        when(directory.listUnits("acme"))
                .thenReturn(List.of(
                        new OrgUnit("acme", "u-root", null, "Root", "ACTIVE"),
                        new OrgUnit("acme", "u-eng", "u-root", "Eng", "ACTIVE"),
                        new OrgUnit("acme", "u-team", "u-eng", "Team", "ACTIVE")));
        when(directory.listMemberships("acme"))
                .thenReturn(List.of(
                        new OrgMembership("acme", "sub-a", "u-root", "ACTIVE"),
                        new OrgMembership("acme", "sub-b", "u-eng", "ACTIVE")));

        mockMvc.perform(get(OrgApiEndpoint.PATH + "/units")
                        .param("tenantId", "acme")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.units.length()").value(2))
                .andExpect(jsonPath("$.units[0].orgUnitId").value("u-eng"))
                .andExpect(jsonPath("$.units[1].orgUnitId").value("u-team"));

        mockMvc.perform(get(OrgApiEndpoint.PATH + "/memberships")
                        .param("tenantId", "acme")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberships.length()").value(1))
                .andExpect(jsonPath("$.memberships[0].orgUnitId").value("u-eng"));
    }

    @Test
    void unrestrictedActorSeesFullTenantList() throws Exception {
        when(directory.resolveSelfAndDescendants("acme", LocalOperatorSeeder.SUBJECT_ID))
                .thenReturn(OrgScope.unrestricted());
        when(directory.listUnits("acme"))
                .thenReturn(List.of(
                        new OrgUnit("acme", "u-root", null, "Root", "ACTIVE"),
                        new OrgUnit("acme", "u-eng", "u-root", "Eng", "ACTIVE")));

        mockMvc.perform(get(OrgApiEndpoint.PATH + "/units")
                        .param("tenantId", "acme")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.units.length()").value(2));
    }

    @Test
    void noneScopedActorSeesEmptyList() throws Exception {
        when(directory.resolveSelfAndDescendants("acme", LocalOperatorSeeder.SUBJECT_ID))
                .thenReturn(OrgScope.none());
        when(directory.listUnits("acme"))
                .thenReturn(List.of(
                        new OrgUnit("acme", "u-root", null, "Root", "ACTIVE"),
                        new OrgUnit("acme", "u-eng", "u-root", "Eng", "ACTIVE")));

        mockMvc.perform(get(OrgApiEndpoint.PATH + "/units")
                        .param("tenantId", "acme")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.units.length()").value(0));
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
