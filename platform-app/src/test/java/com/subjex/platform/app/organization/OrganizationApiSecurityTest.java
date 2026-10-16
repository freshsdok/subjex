package com.subjex.platform.app.organization;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.org.legacy.JdbcOrgDirectory;
import com.subjex.platform.app.org.legacy.OrgScope;
import com.subjex.platform.app.org.legacy.OrgApiEndpoint;
import com.subjex.platform.app.security.LocalOperatorSeeder;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.OrganizationScope;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.security.SqlRbacPolicyEngine;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * OrganizationApiSecurityTest — purpose: {@code /api/v1/organizations} AuthZ + scope filtering.
 * Gates: org.read/org.write; out-of-scope deny fail-closed; tenant grant required on writes.
 * Also covers legacy Deprecation headers on {@code /api/v1/org/**}.
 * <p>
 * 目的：组织本体 API 鉴权与范围过滤。门禁：org.read/org.write；越范围失败关闭；写需租户授权。
 */
@WebMvcTest(controllers = {OrganizationApiEndpoint.class, OrgApiEndpoint.class})
@Import({
    PlatformSecurityConfiguration.class,
    com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    OrganizationApiSecurityTest.GuardConfiguration.class
})
class OrganizationApiSecurityTest {

    private static final String BARE = "platform-bare-org-o5";
    private static final String BARE_PASSWORD = "bare-pass";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private JdbcOrganizationStore store;

    @MockitoBean
    private JdbcOrgDirectory directory;

    @MockitoBean
    private OrganizationScopeResolver scopeResolver;

    @MockitoBean
    private OperatorActionAudit audit;

    @Autowired
    private OperatorTenantAccess tenantAccess;

    @BeforeEach
    void seedBareAndUnrestrictedOperator() {
        tenantAccess.ensureGrant(LocalOperatorSeeder.SUBJECT_ID, OperatorTenantAccess.ALL_TENANTS);
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account WHERE login_name = ?", Integer.class, BARE);
        if (exists != null && exists == 0) {
            jdbc.update(
                    "INSERT INTO account (account_id, login_name, account_state) VALUES ('account-bare-o5', ?, 'ACTIVE')",
                    BARE);
            jdbc.update(
                    "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES ('subject-bare-o5', 'Bare', 'PERSON')");
            jdbc.update(
                    """
                    INSERT INTO subject_identity (identity_id, account_id, subject_id, tenant_id, identity_state)
                    VALUES ('identity-bare-o5', 'account-bare-o5', 'subject-bare-o5', 'platform', 'ACTIVE')
                    """);
            jdbc.update(
                    "INSERT INTO operator_credential (account_id, password_hash) VALUES ('account-bare-o5', ?)",
                    passwordEncoder.encode(BARE_PASSWORD));
        }
        when(scopeResolver.resolveSelfAndDescendants(anyString(), anyString()))
                .thenReturn(OrganizationScope.unrestricted());
        when(directory.resolveSelfAndDescendants(anyString(), anyString()))
                .thenReturn(com.subjex.platform.app.org.legacy.OrgScope.unrestricted());
        when(store.findActiveManagerSubjectId(anyString())).thenReturn(Optional.empty());
        when(store.findActiveContainsSortOrder(anyString())).thenReturn(0);
        when(store.listActiveContainsChildren(anyString())).thenReturn(List.of());
        when(store.findActiveContainsParent(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void unauthenticatedGets401() throws Exception {
        mockMvc.perform(get(OrganizationApiEndpoint.PATH).param("tenantId", "acme"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
    }

    @Test
    void bareWithoutOrgReadGets403() throws Exception {
        mockMvc.perform(get(OrganizationApiEndpoint.PATH)
                        .param("tenantId", "acme")
                        .with(httpBasic(BARE, BARE_PASSWORD)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }

    @Test
    void readerCanListOrganizationsAndMemberships() throws Exception {
        when(store.listOrganizationsLinkedToTenant("acme"))
                .thenReturn(List.of(new Organization("org-root", "Root", "ACTIVE")));
        when(store.findActiveContainsParent("org-root")).thenReturn(Optional.empty());
        when(store.listMembershipsForTenant("acme", null))
                .thenReturn(List.of(new Membership("sub-a", "org-root", "ACTIVE")));

        mockMvc.perform(get(OrganizationApiEndpoint.PATH)
                        .param("tenantId", "acme")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizations[0].organizationId").value("org-root"))
                .andExpect(jsonPath("$.organizations[0].organizationName").value("Root"));

        mockMvc.perform(get(OrganizationApiEndpoint.PATH + "/memberships")
                        .param("tenantId", "acme")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberships[0].organizationId").value("org-root"))
                .andExpect(jsonPath("$.memberships[0].subjectId").value("sub-a"));
    }

    @Test
    void missingTenantIdIs400() throws Exception {
        mockMvc.perform(get(OrganizationApiEndpoint.PATH).with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("tenantId required"));
    }

    @Test
    void operatorCanUpsertOrganizationAndMembership() throws Exception {
        when(store.upsertOrganization(eq("org-root"), eq("Root"), isNull()))
                .thenReturn(new Organization("org-root", "Root", "ACTIVE"));
        when(store.findActiveContainsParent("org-root")).thenReturn(Optional.empty());
        when(store.organizationExists("org-root")).thenReturn(true);
        when(store.listOrganizationsLinkedToTenant("acme"))
                .thenReturn(List.of(new Organization("org-root", "Root", "ACTIVE")));
        when(store.upsertMembership(eq("sub-a"), eq("org-root"), isNull()))
                .thenReturn(new Membership("sub-a", "org-root", "ACTIVE"));
        when(store.endMembership("sub-a", "org-root")).thenReturn(true);

        mockMvc.perform(put(OrganizationApiEndpoint.PATH + "/org-root")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationName\":\"Root\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value("org-root"))
                .andExpect(jsonPath("$.organizationName").value("Root"));
        verify(audit).record(
                org.mockito.ArgumentMatchers.any(),
                eq("organization.upsert"),
                eq("acme/org-root"),
                eq(AuditOutcome.ALLOWED));

        mockMvc.perform(put(OrganizationApiEndpoint.PATH + "/memberships")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\"sub-a\",\"organizationId\":\"org-root\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value("org-root"));

        mockMvc.perform(delete(OrganizationApiEndpoint.PATH + "/memberships")
                        .param("tenantId", "acme")
                        .param("subjectId", "sub-a")
                        .param("organizationId", "org-root")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void readerCannotWrite() throws Exception {
        mockMvc.perform(put(OrganizationApiEndpoint.PATH + "/org-root")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationName\":\"Root\"}")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }

    @Test
    void scopedActorSeesOnlyOrganizationsInScope() throws Exception {
        OrganizationScope scope = OrganizationScope.selfAndDescendants(
                List.of("org-eng"), List.of("org-eng", "org-team"));
        when(scopeResolver.resolveSelfAndDescendants("acme", LocalOperatorSeeder.SUBJECT_ID))
                .thenReturn(scope);
        when(store.listOrganizationsLinkedToTenant("acme"))
                .thenReturn(List.of(
                        new Organization("org-root", "Root", "ACTIVE"),
                        new Organization("org-eng", "Eng", "ACTIVE"),
                        new Organization("org-team", "Team", "ACTIVE")));
        when(store.findActiveContainsParent(anyString())).thenReturn(Optional.empty());
        when(store.findActiveContainsParent("org-eng")).thenReturn(Optional.of("org-root"));
        when(store.findActiveContainsParent("org-team")).thenReturn(Optional.of("org-eng"));

        mockMvc.perform(get(OrganizationApiEndpoint.PATH)
                        .param("tenantId", "acme")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizations.length()").value(2))
                .andExpect(jsonPath("$.organizations[0].organizationId").value("org-eng"))
                .andExpect(jsonPath("$.organizations[1].organizationId").value("org-team"));
    }



    @Test
    void operatorCanBatchPreviewAndMove() throws Exception {
        when(store.listOrganizationsLinkedToTenant("acme"))
                .thenReturn(List.of(
                        new Organization("org-root", "Root", "ACTIVE"),
                        new Organization("org-a", "A", "ACTIVE"),
                        new Organization("org-b", "B", "ACTIVE")));
        when(store.findActiveContainsParent("org-a")).thenReturn(Optional.of("org-root"));
        when(store.findActiveContainsParent("org-b")).thenReturn(Optional.of("org-root"));
        when(store.containsSelfAndDescendants("org-a")).thenReturn(Set.of("org-a"));
        when(store.containsSelfAndDescendants("org-b")).thenReturn(Set.of("org-b"));
        when(store.countDirectMemberships("org-a")).thenReturn(1);
        when(store.countDirectMemberships("org-b")).thenReturn(0);
        mockMvc.perform(post(OrganizationApiEndpoint.PATH + "/moves/preview")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationIds\":[\"org-a\",\"org-b\"],\"newParentOrganizationId\":null}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.anyCycle").value(false))
                .andExpect(jsonPath("$.allInScope").value(true))
                .andExpect(jsonPath("$.totalSubtreeOrganizationCount").value(2));

        mockMvc.perform(post(OrganizationApiEndpoint.PATH + "/moves")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationIds\":[\"org-a\",\"org-b\"],\"newParentOrganizationId\":null}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moved.length()").value(2));
        verify(store).setContainsParent(eq("org-a"), isNull());
        verify(store).setContainsParent(eq("org-b"), isNull());
        verify(audit).record(
                org.mockito.ArgumentMatchers.any(),
                eq("org.organization.batch_move"),
                eq("acme/2->-"),
                eq(AuditOutcome.ALLOWED));
    }

    @Test
    void operatorCanPreviewAndMoveOrganization() throws Exception {
        when(store.listOrganizationsLinkedToTenant("acme"))
                .thenReturn(List.of(
                        new Organization("org-root", "Root", "ACTIVE"),
                        new Organization("org-eng", "Eng", "ACTIVE"),
                        new Organization("org-team", "Team", "ACTIVE")));
        when(store.findActiveContainsParent("org-team")).thenReturn(Optional.of("org-eng"));
        when(store.containsSelfAndDescendants("org-team")).thenReturn(Set.of("org-team"));
        when(store.countDirectMemberships("org-team")).thenReturn(2);
        when(store.wouldCreateContainsCycle("org-root", "org-team")).thenReturn(false);

        mockMvc.perform(post(OrganizationApiEndpoint.PATH + "/org-team/move/preview")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newParentOrganizationId\":\"org-root\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value("org-team"))
                .andExpect(jsonPath("$.fromParentOrganizationId").value("org-eng"))
                .andExpect(jsonPath("$.toParentOrganizationId").value("org-root"))
                .andExpect(jsonPath("$.subtreeOrganizationCount").value(1))
                .andExpect(jsonPath("$.directMembershipCount").value(2))
                .andExpect(jsonPath("$.wouldCycle").value(false))
                .andExpect(jsonPath("$.inScope").value(true));

        mockMvc.perform(post(OrganizationApiEndpoint.PATH + "/org-team/move")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newParentOrganizationId\":\"org-root\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value("org-team"));
        verify(store).setContainsParent("org-team", "org-root");
        verify(audit).record(
                org.mockito.ArgumentMatchers.any(),
                eq("org.organization.move"),
                eq("acme/org-team:org-eng->org-root"),
                eq(AuditOutcome.ALLOWED));
    }

    @Test
    void moveRejectsCycleWith400() throws Exception {
        when(store.listOrganizationsLinkedToTenant("acme"))
                .thenReturn(List.of(
                        new Organization("org-eng", "Eng", "ACTIVE"),
                        new Organization("org-team", "Team", "ACTIVE")));
        when(store.findActiveContainsParent("org-eng")).thenReturn(Optional.empty());
        when(store.wouldCreateContainsCycle("org-team", "org-eng")).thenReturn(true);

        mockMvc.perform(post(OrganizationApiEndpoint.PATH + "/org-eng/move")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newParentOrganizationId\":\"org-team\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("CONTAINS relation would form a cycle"));
        verify(store, org.mockito.Mockito.never()).setContainsParent(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void moveOutOfScopeIs403() throws Exception {
        OrganizationScope scope = OrganizationScope.selfAndDescendants(
                List.of("org-eng"), List.of("org-eng", "org-team"));
        when(scopeResolver.resolveSelfAndDescendants("acme", LocalOperatorSeeder.SUBJECT_ID))
                .thenReturn(scope);
        when(store.listOrganizationsLinkedToTenant("acme"))
                .thenReturn(List.of(
                        new Organization("org-root", "Root", "ACTIVE"),
                        new Organization("org-eng", "Eng", "ACTIVE")));

        mockMvc.perform(post(OrganizationApiEndpoint.PATH + "/org-root/move")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newParentOrganizationId\":\"org-eng\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isForbidden());
        verify(store, org.mockito.Mockito.never()).setContainsParent(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void readerCannotPreviewMove() throws Exception {
        mockMvc.perform(post(OrganizationApiEndpoint.PATH + "/org-team/move/preview")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newParentOrganizationId\":\"org-root\"}")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }


    @Test
    void operatorCanPreviewAndMerge() throws Exception {
        when(store.listOrganizationsLinkedToTenant("acme"))
                .thenReturn(List.of(
                        new Organization("org-a", "A", "ACTIVE"),
                        new Organization("org-b", "B", "ACTIVE")));
        when(store.containsSelfAndDescendants("org-a")).thenReturn(Set.of("org-a"));
        when(store.countDirectMemberships("org-a")).thenReturn(2);
        when(store.listActiveContainsChildren("org-a")).thenReturn(List.of("org-child"));
        when(store.mergeOrganizations("org-a", "org-b"))
                .thenReturn(new JdbcOrganizationStore.MergeResult(
                        new Organization("org-a", "A", "ENDED"), "org-b", 2, 1));

        mockMvc.perform(post(OrganizationApiEndpoint.PATH + "/org-a/merge/preview")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetOrganizationId\":\"org-b\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wouldCycle").value(false))
                .andExpect(jsonPath("$.movedMembershipCount").value(2))
                .andExpect(jsonPath("$.reparentedChildCount").value(1));

        mockMvc.perform(post(OrganizationApiEndpoint.PATH + "/org-a/merge")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetOrganizationId\":\"org-b\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movedMembershipCount").value(2));
        verify(audit).record(
                org.mockito.ArgumentMatchers.any(),
                eq("org.organization.merge"),
                eq("acme/org-a->org-b"),
                eq(AuditOutcome.ALLOWED));
    }

    @Test
    void mergeIntoDescendantRejected() throws Exception {
        when(store.listOrganizationsLinkedToTenant("acme"))
                .thenReturn(List.of(
                        new Organization("org-a", "A", "ACTIVE"),
                        new Organization("org-b", "B", "ACTIVE")));
        when(store.containsSelfAndDescendants("org-a")).thenReturn(Set.of("org-a", "org-b"));

        mockMvc.perform(post(OrganizationApiEndpoint.PATH + "/org-a/merge")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetOrganizationId\":\"org-b\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest());
        verify(store, org.mockito.Mockito.never()).mergeOrganizations(anyString(), anyString());
    }

    @Test
    void operatorCanSplitAndSetManager() throws Exception {
        when(store.listOrganizationsLinkedToTenant("acme"))
                .thenReturn(List.of(
                        new Organization("org-root", "Root", "ACTIVE"),
                        new Organization("org-eng", "Eng", "ACTIVE"),
                        new Organization("org-new", "New", "ACTIVE")));
        when(store.findActiveContainsParent("org-eng")).thenReturn(Optional.of("org-root"));
        when(store.listActiveContainsChildren("org-eng")).thenReturn(List.of("org-team"));
        when(store.organizationExists("org-eng")).thenReturn(true);
        when(store.splitOrganization(
                        eq("org-eng"),
                        eq("org-new"),
                        eq("New"),
                        eq("org-root"),
                        eq(List.of("org-team")),
                        eq(List.of("sub-a"))))
                .thenReturn(new JdbcOrganizationStore.SplitResult(
                        new Organization("org-new", "New", "ACTIVE"), "org-eng", 1, 1));

        mockMvc.perform(post(OrganizationApiEndpoint.PATH + "/org-eng/split/preview")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"newOrganizationId\":\"org-new\",\"newOrganizationName\":\"New\","
                                        + "\"childOrganizationIds\":[\"org-team\"],\"memberSubjectIds\":[\"sub-a\"]}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.childrenValid").value(true))
                .andExpect(jsonPath("$.childCount").value(1));

        mockMvc.perform(post(OrganizationApiEndpoint.PATH + "/org-eng/split")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"newOrganizationId\":\"org-new\",\"newOrganizationName\":\"New\","
                                        + "\"childOrganizationIds\":[\"org-team\"],\"memberSubjectIds\":[\"sub-a\"]}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movedChildCount").value(1));

        when(store.findActiveManagerSubjectId("org-eng")).thenReturn(Optional.of("sub-mgr"));
        mockMvc.perform(put(OrganizationApiEndpoint.PATH + "/org-eng/manager")
                        .param("tenantId", "acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"managerSubjectId\":\"sub-mgr\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managerSubjectId").value("sub-mgr"));
        verify(store).setOrganizationManager("org-eng", "sub-mgr");

        when(store.findActiveManagerSubjectId("org-eng")).thenReturn(Optional.empty());
        mockMvc.perform(delete(OrganizationApiEndpoint.PATH + "/org-eng/manager")
                        .param("tenantId", "acme")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk());
        verify(store).clearOrganizationManager("org-eng");
    }

        @Test
    void legacyOrgApiCarriesDeprecationHeaders() throws Exception {
        when(directory.listUnits("acme")).thenReturn(List.of());
        mockMvc.perform(get(OrgApiEndpoint.PATH + "/units")
                        .param("tenantId", "acme")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().string("Deprecation", "true"))
                .andExpect(header().string("Link", "<" + OrganizationApiEndpoint.PATH + ">; rel=\"successor-version\""));
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
