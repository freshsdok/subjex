package com.subjex.platform.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * CedarPolicyEngineTest — purpose: AuthZ-1b Cedar allow/deny via baseline policies.
 * Gates: assumeTrue skips if FFI unavailable; held permission allows; missing denies (fail-closed).
 * <p>
 * 目的：AuthZ-1b Cedar 基线策略允许/拒绝。门禁：FFI 不可用则跳过；持有权限允许；缺失则失败关闭拒绝。
 */
class CedarPolicyEngineTest {

    private static CedarPolicyEngine engine;

    @BeforeAll
    static void loadEngine() {
        TenantGuard tenantGuard = new DenyWhenTenantMissing();
        OperatorTenantAccess tenantAccess = mock(OperatorTenantAccess.class);
        engine = new CedarPolicyEngine(tenantGuard, tenantAccess);
        assumeTrue(
                engine.isNativeAvailable(),
                () -> "Cedar FFI unavailable: " + engine.nativeFailureMessage().orElse("unknown"));
    }

    @Test
    void allowsWhenPermissionHeld() {
        PolicyPrincipal principal = PolicyPrincipal.of("subject-1", Set.of("page.read", "registry.write"));
        AccessDecision decision = engine.evaluate(
                principal,
                "page.read",
                AccessAction.of("read"),
                PolicyResource.of("page", "service-note"),
                PolicyContext.unscoped());
        assertTrue(decision.allowed(), () -> "denyReason=" + decision.denyReason());
        assertEquals("page.read", decision.matchedPermission());
        assertEquals("subject-1", decision.subjectId());
    }

    @Test
    void deniesWhenPermissionMissing() {
        PolicyPrincipal principal = PolicyPrincipal.of("subject-1", Set.of("page.read"));
        AccessDecision decision = engine.evaluate(
                principal,
                "registry.write",
                AccessAction.of("submit"),
                PolicyResource.of("form", "endpoint-publication"),
                PolicyContext.unscoped());
        assertFalse(decision.allowed());
        assertEquals(AccessDecision.DENY_PERMISSION_MISSING, decision.denyReason());
    }

    @Test
    void deniesBlankPermissionWithoutCedar() {
        PolicyPrincipal principal = PolicyPrincipal.of("subject-1", Set.of("page.read"));
        AccessDecision decision = engine.evaluate(
                principal,
                "  ",
                AccessAction.of("check"),
                PolicyResource.of("declaration", ""),
                PolicyContext.unscoped());
        assertFalse(decision.allowed());
        assertEquals(AccessDecision.DENY_PERMISSION_BLANK, decision.denyReason());
    }

    @Test
    void allowsSubjectIdAnonymousWhenPermissionHeld() {
        // AuthZ-1d: "anonymous" is a normal subject id (SQL parity); null/blank → __unauthenticated__
        PolicyPrincipal principal = PolicyPrincipal.of("anonymous", Set.of("page.read"));
        AccessDecision decision = engine.evaluate(
                principal,
                "page.read",
                AccessAction.of("read"),
                PolicyResource.of("page", "x"),
                PolicyContext.unscoped());
        assertTrue(decision.allowed(), () -> "denyReason=" + decision.denyReason());
    }
}
