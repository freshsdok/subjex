package com.subjex.platform.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * SqlRbacPolicyEngineTest — purpose: PolicyEngine SQL adapter mirrors AccessChecker decisions.
 * Gates: allow when permission held; unknown permission deny reason stable; tenant mismatch deny.
 * <p>
 * 目的：PolicyEngine SQL 适配与 AccessChecker 同形。门禁：持有权限允许；未知权限拒绝原因稳定；租户不一致拒绝。
 */
class SqlRbacPolicyEngineTest {

    private final TenantGuard tenantGuard = new DenyWhenTenantMissing();
    private final OperatorTenantAccess tenantAccess = mock(OperatorTenantAccess.class);
    private final PolicyEngine engine = new SqlRbacPolicyEngine(tenantGuard, tenantAccess);

    @Test
    void allowsWhenPermissionHeldAndNotTenantScoped() {
        PolicyPrincipal principal = PolicyPrincipal.of("subject-1", Set.of("registry.write"));
        AccessDecision decision = engine.evaluate(
                principal,
                "registry.write",
                AccessAction.of("submit"),
                PolicyResource.of("form", "endpoint-publication"),
                PolicyContext.unscoped());
        assertTrue(decision.allowed());
        assertEquals("subject-1", decision.subjectId());
        assertEquals("registry.write", decision.matchedPermission());
        assertNull(decision.denyReason());
        assertEquals("form", decision.resource().kind());
        assertEquals("submit", decision.action().name());
    }

    @Test
    void deniesUnknownPermissionWithSameReasonAsAccessChecker() {
        PolicyPrincipal principal = PolicyPrincipal.of("subject-1", Set.of("page.read"));
        AccessDecision viaPort = engine.evaluate(
                principal,
                "registry.write",
                AccessAction.of("submit"),
                PolicyResource.of("form", "endpoint-publication"),
                PolicyContext.unscoped());
        AccessDecision viaChecker = AccessChecker.evaluate(
                SqlRbacPolicyEngine.bridge(principal),
                "registry.write",
                false,
                null,
                tenantGuard,
                null,
                AccessResource.of("form", "endpoint-publication"),
                AccessAction.of("submit"));
        assertFalse(viaPort.allowed());
        assertEquals(AccessDecision.DENY_PERMISSION_MISSING, viaPort.denyReason());
        assertEquals(viaChecker.denyReason(), viaPort.denyReason());
        assertNull(viaPort.matchedPermission());
    }

    @Test
    void deniesBlankPermissionWithSameReason() {
        PolicyPrincipal principal = PolicyPrincipal.of("subject-1", Set.of("registry.write"));
        AccessDecision viaPort = engine.evaluate(
                principal,
                "  ",
                AccessAction.of("check"),
                PolicyResource.of("declaration", ""),
                PolicyContext.unscoped());
        assertFalse(viaPort.allowed());
        assertEquals(AccessDecision.DENY_PERMISSION_BLANK, viaPort.denyReason());
    }

    @Test
    void deniesWhenTenantNotGranted() {
        when(tenantAccess.isGranted(
                        org.mockito.ArgumentMatchers.any(OperatorPrincipal.class),
                        org.mockito.ArgumentMatchers.eq("acme")))
                .thenReturn(false);
        PolicyPrincipal principal = PolicyPrincipal.of("subject-1", Set.of("task.write"));
        AccessDecision decision = engine.evaluate(
                principal,
                "task.write",
                AccessAction.of("write"),
                PolicyResource.of("task", "t1"),
                PolicyContext.of("acme", true, (OrganizationScope) null));
        assertFalse(decision.allowed());
        assertEquals(AccessDecision.DENY_TENANT_NOT_GRANTED, decision.denyReason());
        assertEquals("task.write", decision.matchedPermission());
    }

    @Test
    void allowsWhenTenantGranted() {
        when(tenantAccess.isGranted(
                        org.mockito.ArgumentMatchers.any(OperatorPrincipal.class),
                        org.mockito.ArgumentMatchers.eq("acme")))
                .thenReturn(true);
        PolicyPrincipal principal = PolicyPrincipal.of("subject-1", Set.of("task.write"));
        AccessDecision decision = engine.require(
                principal,
                "task.write",
                AccessAction.of("write"),
                PolicyResource.of("task", "t1"),
                PolicyContext.of("acme", true, (OrganizationScope) null));
        assertTrue(decision.allowed());
        assertEquals("acme", decision.tenantId());
    }

    @Test
    void deniesWhenResourceOrgUnitOutsideScope() {
        OrganizationScope scope = OrganizationScope.selfAndDescendants(List.of("u-eng"), List.of("u-eng", "u-team"));
        PolicyPrincipal principal = PolicyPrincipal.of("subject-1", Set.of("org.write"));
        AccessDecision decision = engine.evaluate(
                principal,
                "org.write",
                AccessAction.of("write"),
                PolicyResource.of("org_membership", "u-root")
                        .withAttribute(PolicyResource.ATTR_ORGANIZATION_ID, "u-root"),
                PolicyContext.of("acme", false, scope));
        assertFalse(decision.allowed());
        assertEquals(AccessDecision.DENY_ORG_OUT_OF_SCOPE, decision.denyReason());
        assertEquals(scope, decision.orgScope());
    }

    @Test
    void requireThrowsWithDecisionOnDeny() {
        PolicyPrincipal principal = PolicyPrincipal.of("subject-1", Set.of("page.read"));
        AccessDecisionDeniedException ex = assertThrows(
                AccessDecisionDeniedException.class,
                () -> engine.require(
                        principal,
                        "registry.write",
                        AccessAction.of("submit"),
                        PolicyResource.of("form", "x"),
                        PolicyContext.unscoped()));
        assertEquals(AccessDecision.DENY_PERMISSION_MISSING, ex.decision().denyReason());
        assertEquals("registry.write", ex.requiredPermission());
    }

    @Test
    void fromOperatorPrincipalBridgesPermissions() {
        OperatorPrincipal op =
                new OperatorPrincipal("login", "{noop}x", "id-1", "sub-9", Set.of("org.write"), true);
        PolicyPrincipal principal = PolicyPrincipal.from(op);
        assertEquals("sub-9", principal.subjectId());
        assertTrue(principal.permissionNames().contains("org.write"));
    }

    @Test
    void deniesWhenContextTenantMismatchesResourceTenant() {
        PolicyPrincipal principal = PolicyPrincipal.of("subject-1", Set.of("org.write"));
        AccessDecision decision = engine.evaluate(
                principal,
                "org.write",
                AccessAction.of("write"),
                PolicyResource.of("org_unit", "u-eng")
                        .withAttribute(PolicyResource.ATTR_ORGANIZATION_ID, "u-eng")
                        .withAttribute(PolicyResource.ATTR_TENANT_ID, "other"),
                PolicyContext.of("acme", false, OrganizationScope.unrestricted()));
        assertFalse(decision.allowed());
        assertEquals(AccessDecision.DENY_TENANT_MISMATCH, decision.denyReason());
    }

    @Test
    void deniesWhenOrgScopeMissingOnResourceOrg() {
        PolicyPrincipal principal = PolicyPrincipal.of("subject-1", Set.of("org.write"));
        AccessDecision decision = engine.evaluate(
                principal,
                "org.write",
                AccessAction.of("write"),
                PolicyResource.of("org_membership", "u-root")
                        .withAttribute(PolicyResource.ATTR_ORGANIZATION_ID, "u-root"),
                PolicyContext.of("acme", false, (OrganizationScope) null));
        assertFalse(decision.allowed());
        assertEquals(AccessDecision.DENY_ORG_SCOPE_MISSING, decision.denyReason());
    }
}
