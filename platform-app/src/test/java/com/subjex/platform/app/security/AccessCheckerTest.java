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
 * AccessCheckerTest — 可解释判定：权限允许/拒绝、租户缺失/未授权。
 */
class AccessCheckerTest {

    private final TenantGuard tenantGuard = new DenyWhenTenantMissing();
    private final AccessResource resource = AccessResource.of("form", "endpoint-publication");
    private final AccessAction action = AccessAction.of("submit");

    @Test
    void allowsWhenPermissionHeldAndNotTenantScoped() {
        OperatorPrincipal operator = operator("subject-1", Set.of("registry.write"));
        AccessDecision decision = AccessChecker.evaluate(
                operator, "registry.write", false, null, tenantGuard, null, resource, action);
        assertTrue(decision.allowed());
        assertEquals("subject-1", decision.subjectId());
        assertEquals("registry.write", decision.matchedPermission());
        assertNull(decision.denyReason());
        assertNull(decision.orgScope());
        assertEquals("form", decision.resource().kind());
        assertEquals("submit", decision.action().name());
    }

    @Test
    void deniesWhenPermissionMissing() {
        OperatorPrincipal operator = operator("subject-1", Set.of("page.read"));
        AccessDecision decision = AccessChecker.evaluate(
                operator, "registry.write", false, null, tenantGuard, null, resource, action);
        assertFalse(decision.allowed());
        assertNull(decision.matchedPermission());
        assertEquals(AccessDecision.DENY_PERMISSION_MISSING, decision.denyReason());
    }

    @Test
    void deniesWhenPermissionBlank() {
        OperatorPrincipal operator = operator("subject-1", Set.of("registry.write"));
        AccessDecision decision = AccessChecker.evaluate(
                operator, "  ", false, null, tenantGuard, null, resource, action);
        assertFalse(decision.allowed());
        assertEquals(AccessDecision.DENY_PERMISSION_BLANK, decision.denyReason());
    }

    @Test
    void deniesWhenTenantMissingWhileScoped() {
        OperatorPrincipal operator = operator("subject-1", Set.of("task.write"));
        OperatorTenantAccess tenantAccess = mock(OperatorTenantAccess.class);
        AccessDecision decision = AccessChecker.evaluate(
                operator, "task.write", true, null, tenantGuard, tenantAccess, resource, action);
        assertFalse(decision.allowed());
        assertEquals("task.write", decision.matchedPermission());
        assertEquals(AccessDecision.DENY_TENANT_MISSING, decision.denyReason());
    }

    @Test
    void deniesWhenTenantNotGranted() {
        OperatorPrincipal operator = operator("subject-1", Set.of("task.write"));
        OperatorTenantAccess tenantAccess = mock(OperatorTenantAccess.class);
        when(tenantAccess.isGranted(operator, "acme")).thenReturn(false);
        AccessDecision decision = AccessChecker.evaluate(
                operator, "task.write", true, "acme", tenantGuard, tenantAccess, resource, action);
        assertFalse(decision.allowed());
        assertEquals("acme", decision.tenantId());
        assertEquals("task.write", decision.matchedPermission());
        assertEquals(AccessDecision.DENY_TENANT_NOT_GRANTED, decision.denyReason());
    }

    @Test
    void allowsWhenTenantGranted() {
        OperatorPrincipal operator = operator("subject-1", Set.of("task.write"));
        OperatorTenantAccess tenantAccess = mock(OperatorTenantAccess.class);
        when(tenantAccess.isGranted(operator, "acme")).thenReturn(true);
        AccessDecision decision = AccessChecker.require(
                operator, "task.write", true, "acme", tenantGuard, tenantAccess, resource, action);
        assertTrue(decision.allowed());
        assertEquals("task.write", decision.matchedPermission());
        assertNull(decision.denyReason());
    }

    @Test
    void requireThrowsWithDecisionOnDeny() {
        OperatorPrincipal operator = operator("subject-1", Set.of("page.read"));
        AccessDecisionDeniedException ex = assertThrows(
                AccessDecisionDeniedException.class,
                () -> AccessChecker.require(
                        operator, "registry.write", false, null, tenantGuard, null, resource, action));
        assertEquals(AccessDecision.DENY_PERMISSION_MISSING, ex.decision().denyReason());
        assertEquals("registry.write", ex.requiredPermission());
    }


    @Test
    void deniesWhenResourceOrgUnitOutsideScope() {
        OperatorPrincipal operator = operator("subject-1", Set.of("org.write"));
        OrgScope scope = OrgScope.selfAndDescendants(List.of("u-eng"), List.of("u-eng", "u-team"));
        AccessDecision decision = AccessChecker.evaluate(
                operator,
                "org.write",
                false,
                "acme",
                tenantGuard,
                null,
                AccessResource.of("org_unit", "u-root"),
                AccessAction.of("write"),
                scope,
                "u-root");
        assertFalse(decision.allowed());
        assertEquals(AccessDecision.DENY_ORG_OUT_OF_SCOPE, decision.denyReason());
        assertEquals(scope, decision.orgScope());
        assertEquals("org.write", decision.matchedPermission());
    }

    @Test
    void allowsWhenResourceOrgUnitInsideScope() {
        OperatorPrincipal operator = operator("subject-1", Set.of("org.write"));
        OrgScope scope = OrgScope.selfAndDescendants(List.of("u-eng"), List.of("u-eng", "u-team"));
        AccessDecision decision = AccessChecker.require(
                operator,
                "org.write",
                false,
                "acme",
                tenantGuard,
                null,
                AccessResource.of("org_unit", "u-team"),
                AccessAction.of("write"),
                scope,
                "u-team");
        assertTrue(decision.allowed());
        assertEquals(scope, decision.orgScope());
    }

    @Test
    void skipsOrgFilterWhenScopeUnspecified() {
        OperatorPrincipal operator = operator("subject-1", Set.of("org.write"));
        AccessDecision decision = AccessChecker.evaluate(
                operator,
                "org.write",
                false,
                "acme",
                tenantGuard,
                null,
                AccessResource.of("org_unit", "u-root"),
                AccessAction.of("write"),
                null,
                "u-root");
        assertTrue(decision.allowed());
        assertNull(decision.orgScope());
    }

    @Test
    void requireOrgScopeThrowsWithDecision() {
        OrgScope scope = OrgScope.selfAndDescendants(List.of("u-eng"), List.of("u-eng"));
        AccessDecision base = AccessDecision.allow(
                "subject-1", "acme", scope, resource, action, "org.write");
        AccessDecisionDeniedException ex = assertThrows(
                AccessDecisionDeniedException.class,
                () -> AccessChecker.requireOrgScope(base, scope, "u-other", "org.write"));
        assertEquals(AccessDecision.DENY_ORG_OUT_OF_SCOPE, ex.decision().denyReason());
        assertEquals(scope, ex.decision().orgScope());
    }

    private static OperatorPrincipal operator(String subjectId, Set<String> permissions) {
        return new OperatorPrincipal("login", "{noop}x", "identity-1", subjectId, permissions, true);
    }
}
