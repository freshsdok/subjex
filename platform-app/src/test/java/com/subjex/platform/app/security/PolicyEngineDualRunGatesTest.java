package com.subjex.platform.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * AuthZ-1c — golden dual-run: {@link SqlRbacPolicyEngine} vs {@link CedarPolicyEngine}.
 * <p>
 * Compares {@code allowed} and {@code denyReason} on curated cases. Cedar decides permission
 * membership; tenant/org still via {@link AccessChecker} on both paths after Cedar allow.
 * AuthZ-1d: subject id {@code anonymous} is normal (both allow when permission held).
 * 双跑门禁：权限允许/拒绝与拒绝原因对齐。
 */
class PolicyEngineDualRunGatesTest {

    private static PolicyEngine sql;
    private static CedarPolicyEngine cedar;
    private static OperatorTenantAccess tenantAccess;

    @BeforeAll
    static void engines() {
        TenantGuard tenantGuard = new DenyWhenTenantMissing();
        tenantAccess = mock(OperatorTenantAccess.class);
        sql = new SqlRbacPolicyEngine(tenantGuard, tenantAccess);
        cedar = new CedarPolicyEngine(tenantGuard, tenantAccess);
        assumeTrue(
                cedar.isNativeAvailable(),
                () -> "Cedar FFI unavailable: " + cedar.nativeFailureMessage().orElse("unknown"));
    }

    record GoldenCase(
            String name,
            PolicyPrincipal principal,
            String requiredPermission,
            AccessAction action,
            PolicyResource resource,
            PolicyContext context) {}

    static List<GoldenCase> goldenCases() {
        List<GoldenCase> cases = new ArrayList<>();
        PolicyPrincipal reader = PolicyPrincipal.of("subject-1", Set.of("page.read", "registry.write"));
        PolicyPrincipal writer = PolicyPrincipal.of("subject-2", Set.of("org.write", "task.write"));

        cases.add(new GoldenCase(
                "allow_permission_unscoped",
                reader,
                "page.read",
                AccessAction.of("read"),
                PolicyResource.of("page", "service-note"),
                PolicyContext.unscoped()));

        cases.add(new GoldenCase(
                "deny_permission_missing",
                reader,
                "task.write",
                AccessAction.of("write"),
                PolicyResource.of("task", "t1"),
                PolicyContext.unscoped()));

        cases.add(new GoldenCase(
                "deny_permission_blank",
                reader,
                "  ",
                AccessAction.of("check"),
                PolicyResource.of("declaration", "x"),
                PolicyContext.unscoped()));

        cases.add(new GoldenCase(
                "deny_null_principal_missing_permission",
                null,
                "page.read",
                AccessAction.of("read"),
                PolicyResource.of("page", "x"),
                PolicyContext.unscoped()));

        cases.add(new GoldenCase(
                "deny_empty_permission_set",
                PolicyPrincipal.of("subject-3", Set.of()),
                "page.read",
                AccessAction.of("read"),
                PolicyResource.of("page", "x"),
                PolicyContext.unscoped()));

        cases.add(new GoldenCase(
                "deny_tenant_mismatch",
                writer,
                "org.write",
                AccessAction.of("write"),
                PolicyResource.of("organization", "org-a")
                        .withAttribute(PolicyResource.ATTR_ORGANIZATION_ID, "org-a")
                        .withAttribute(PolicyResource.ATTR_TENANT_ID, "other"),
                PolicyContext.of("acme", false, OrganizationScope.unrestricted())));

        cases.add(new GoldenCase(
                "deny_org_scope_missing",
                writer,
                "org.write",
                AccessAction.of("write"),
                PolicyResource.of("organization", "org-a")
                        .withAttribute(PolicyResource.ATTR_ORGANIZATION_ID, "org-a"),
                PolicyContext.of("acme", false, (OrganizationScope) null)));

        cases.add(new GoldenCase(
                "deny_org_out_of_scope",
                writer,
                "org.write",
                AccessAction.of("write"),
                PolicyResource.of("organization", "org-root")
                        .withAttribute(PolicyResource.ATTR_ORGANIZATION_ID, "org-root"),
                PolicyContext.of(
                        "acme",
                        false,
                        OrganizationScope.selfAndDescendants(
                                List.of("org-eng"), List.of("org-eng", "org-team")))));

        cases.add(new GoldenCase(
                "allow_org_in_scope",
                writer,
                "org.write",
                AccessAction.of("write"),
                PolicyResource.of("organization", "org-eng")
                        .withAttribute(PolicyResource.ATTR_ORGANIZATION_ID, "org-eng"),
                PolicyContext.of(
                        "acme",
                        false,
                        OrganizationScope.selfAndDescendants(
                                List.of("org-eng"), List.of("org-eng", "org-team")))));

        cases.add(new GoldenCase(
                "deny_tenant_not_granted",
                writer,
                "task.write",
                AccessAction.of("write"),
                PolicyResource.of("task", "t1"),
                PolicyContext.of("acme", true, (OrganizationScope) null)));

        cases.add(new GoldenCase(
                "allow_tenant_granted",
                writer,
                "task.write",
                AccessAction.of("write"),
                PolicyResource.of("task", "t1"),
                PolicyContext.of("acme", true, (OrganizationScope) null)));

        cases.add(new GoldenCase(
                "allow_second_permission_held",
                reader,
                "registry.write",
                AccessAction.of("submit"),
                PolicyResource.of("form", "endpoint-publication"),
                PolicyContext.unscoped()));

        return cases;
    }

    @TestFactory
    @DisplayName("SqlRbac vs Cedar: allowed + denyReason match")
    Stream<DynamicTest> goldenDualRun() {
        when(tenantAccess.isGranted(any(OperatorPrincipal.class), eq("acme"))).thenAnswer(inv -> {
            // default false; allow_tenant_granted overrides via name check in evaluate path —
            // configure per-call in each dynamic test instead
            return false;
        });

        return goldenCases().stream()
                .map(c -> DynamicTest.dynamicTest(c.name(), () -> {
                    // Per-case tenant grant stub
                    if ("allow_tenant_granted".equals(c.name())) {
                        when(tenantAccess.isGranted(any(OperatorPrincipal.class), eq("acme")))
                                .thenReturn(true);
                    } else if ("deny_tenant_not_granted".equals(c.name())) {
                        when(tenantAccess.isGranted(any(OperatorPrincipal.class), eq("acme")))
                                .thenReturn(false);
                    }

                    AccessDecision sqlDecision = sql.evaluate(
                            c.principal(), c.requiredPermission(), c.action(), c.resource(), c.context());
                    AccessDecision cedarDecision = cedar.evaluate(
                            c.principal(), c.requiredPermission(), c.action(), c.resource(), c.context());

                    assertEquals(
                            sqlDecision.allowed(),
                            cedarDecision.allowed(),
                            () -> mismatch("allowed", c, sqlDecision, cedarDecision));
                    assertEquals(
                            sqlDecision.denyReason(),
                            cedarDecision.denyReason(),
                            () -> mismatch("denyReason", c, sqlDecision, cedarDecision));
                    if (sqlDecision.allowed()) {
                        assertEquals(
                                sqlDecision.matchedPermission(),
                                cedarDecision.matchedPermission(),
                                () -> mismatch("matchedPermission", c, sqlDecision, cedarDecision));
                    }
                }));
    }

    @Test
    void subjectIdAnonymousAllowsOnBothEngines() {
        PolicyPrincipal anonymous = PolicyPrincipal.of("anonymous", Set.of("page.read"));
        AccessDecision sqlDecision = sql.evaluate(
                anonymous,
                "page.read",
                AccessAction.of("read"),
                PolicyResource.of("page", "x"),
                PolicyContext.unscoped());
        AccessDecision cedarDecision = cedar.evaluate(
                anonymous,
                "page.read",
                AccessAction.of("read"),
                PolicyResource.of("page", "x"),
                PolicyContext.unscoped());
        assertEquals(sqlDecision.allowed(), cedarDecision.allowed());
        assertEquals(true, sqlDecision.allowed());
        assertEquals(true, cedarDecision.allowed());
    }

    @Test
    void goldenCaseCountIsStable() {
        assertEquals(12, goldenCases().size(), "bump intentionally when adding golden cases");
    }

    private static String mismatch(
            String field, GoldenCase c, AccessDecision sqlDecision, AccessDecision cedarDecision) {
        return "case="
                + c.name()
                + " field="
                + field
                + " sql[allowed="
                + sqlDecision.allowed()
                + ", deny="
                + sqlDecision.denyReason()
                + ", matched="
                + sqlDecision.matchedPermission()
                + "] cedar[allowed="
                + cedarDecision.allowed()
                + ", deny="
                + cedarDecision.denyReason()
                + ", matched="
                + cedarDecision.matchedPermission()
                + "]";
    }
}
