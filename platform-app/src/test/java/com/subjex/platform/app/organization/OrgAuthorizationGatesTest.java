package com.subjex.platform.app.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.subjex.platform.app.org.legacy.JdbcOrgDirectory;
import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.AccessChecker;
import com.subjex.platform.app.security.AccessDecision;
import com.subjex.platform.app.security.AccessResource;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.org.legacy.OrgScope;
import com.subjex.platform.app.security.OrganizationScope;
import com.subjex.platform.app.security.PolicyContext;
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.security.PolicyPrincipal;
import com.subjex.platform.app.security.PolicyResource;
import com.subjex.platform.app.security.SqlRbacPolicyEngine;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Machine gates AUTH-01..05 for O4 org scope from Membership + OrganizationRelation.
 * O4 机器门禁 AUTH-01..05。
 */
class OrgAuthorizationGatesTest {

    private final TenantGuard tenantGuard = new DenyWhenTenantMissing();
    private final AccessResource resource = AccessResource.of("org_unit", "u-eng");
    private final AccessAction action = AccessAction.of("write");

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("AUTH-01 Relationship does not imply authorization")
    void AUTH_01_relationshipDoesNotImplyAuthorization(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);
        JdbcOrgDirectory directory = new JdbcOrgDirectory(jdbc, store);

        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?,?,?)",
                "acme",
                "Acme",
                "ACTIVE");
        jdbc.update(
                "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?,?,?)",
                "sub-rel",
                "Rel",
                "PERSON");
        directory.upsertUnit("acme", "u-eng", null, "Eng", "ACTIVE");
        directory.upsertMembership("acme", "sub-rel", "u-eng", "ACTIVE");
        // Force ontology prefer path (fully backfilled after write-through).
        assertTrue(new OrganizationOntologyBackfill(jdbc, store).isTenantFullyBackfilled("acme"));

        OrgScope derived = directory.resolveSelfAndDescendants("acme", "sub-rel");
        assertEquals(OrgScope.MODE_SELF_AND_DESCENDANTS, derived.mode());
        assertTrue(derived.contains("u-eng"));

        OperatorPrincipal noPerm = operator("sub-rel", Set.of());
        OperatorTenantAccess tenantAccess = mock(OperatorTenantAccess.class);
        when(tenantAccess.isGranted(noPerm, "acme")).thenReturn(true);

        AccessDecision decision = AccessChecker.evaluate(
                noPerm,
                "org.write",
                true,
                "acme",
                tenantGuard,
                tenantAccess,
                resource,
                action,
                derived.toOrganizationScope(),
                "u-eng");
        assertFalse(decision.allowed());
        assertEquals(AccessDecision.DENY_PERMISSION_MISSING, decision.denyReason());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("AUTH-02 Missing scope is fail-closed")
    void AUTH_02_missingScopeIsFailClosed(H2PlatformTables.Mode mode) {
        OperatorPrincipal operator = operator("sub-1", Set.of("org.write"));
        AccessDecision decision = AccessChecker.evaluate(
                operator,
                "org.write",
                false,
                "acme",
                tenantGuard,
                null,
                resource,
                action,
                null,
                "u-eng");
        assertFalse(decision.allowed());
        assertEquals(AccessDecision.DENY_ORG_SCOPE_MISSING, decision.denyReason());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("AUTH-03 NONE != UNRESTRICTED")
    void AUTH_03_noneNotUnrestricted(H2PlatformTables.Mode mode) {
        assertFalse(OrganizationScope.none().contains("u-any"));
        assertTrue(OrganizationScope.unrestricted().contains("u-any"));
        assertFalse(OrganizationScope.none().isUnrestricted());
        assertFalse(OrganizationScope.unrestricted().isNone());

        OperatorPrincipal operator = operator("sub-1", Set.of("org.write"));
        AccessDecision noneDeny = AccessChecker.evaluate(
                operator,
                "org.write",
                false,
                "acme",
                tenantGuard,
                null,
                resource,
                action,
                OrganizationScope.none(),
                "u-eng");
        AccessDecision unrestrictedAllow = AccessChecker.evaluate(
                operator,
                "org.write",
                false,
                "acme",
                tenantGuard,
                null,
                resource,
                action,
                OrganizationScope.unrestricted(),
                "u-eng");
        assertFalse(noneDeny.allowed());
        assertEquals(AccessDecision.DENY_ORG_OUT_OF_SCOPE, noneDeny.denyReason());
        assertTrue(unrestrictedAllow.allowed());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("AUTH-04 Tenant mismatch denies")
    void AUTH_04_tenantMismatchDenies(H2PlatformTables.Mode mode) {
        OperatorTenantAccess tenantAccess = mock(OperatorTenantAccess.class);
        PolicyEngine engine = new SqlRbacPolicyEngine(tenantGuard, tenantAccess);
        PolicyPrincipal principal = PolicyPrincipal.of("sub-1", Set.of("org.write"));

        AccessDecision mismatch = engine.evaluate(
                principal,
                "org.write",
                AccessAction.of("write"),
                PolicyResource.of("org_unit", "u-eng")
                        .withAttribute(PolicyResource.ATTR_ORGANIZATION_ID, "u-eng")
                        .withAttribute(PolicyResource.ATTR_TENANT_ID, "other"),
                PolicyContext.of("acme", false, OrganizationScope.unrestricted()));
        assertFalse(mismatch.allowed());
        assertEquals(AccessDecision.DENY_TENANT_MISMATCH, mismatch.denyReason());

        // Membership in org linked only to tenant beta must not expand for tenant acme.
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);
        JdbcOrgDirectory directory = new JdbcOrgDirectory(jdbc, store);
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?,?,?)",
                "acme",
                "Acme",
                "ACTIVE");
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?,?,?)",
                "beta",
                "Beta",
                "ACTIVE");
        jdbc.update(
                "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?,?,?)",
                "sub-x",
                "X",
                "PERSON");
        directory.upsertUnit("beta", "u-shared", null, "Shared", "ACTIVE");
        directory.upsertMembership("beta", "sub-x", "u-shared", "ACTIVE");
        // Fully backfill beta; acme has no units → scope for acme is NONE even if membership exists globally.
        OrgScope acmeScope = directory.resolveSelfAndDescendants("acme", "sub-x");
        assertTrue(acmeScope.isNone());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("AUTH-05 Organization descendant resolution cannot escape relation graph")
    void AUTH_05_descendantResolutionCannotEscapeRelationGraph(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);
        JdbcOrgDirectory directory = new JdbcOrgDirectory(jdbc, store);

        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?,?,?)",
                "acme",
                "Acme",
                "ACTIVE");
        jdbc.update(
                "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?,?,?)",
                "sub-eng",
                "Eng",
                "PERSON");
        directory.upsertUnit("acme", "u-root", null, "Root", "ACTIVE");
        directory.upsertUnit("acme", "u-eng", "u-root", "Eng", "ACTIVE");
        directory.upsertUnit("acme", "u-team", "u-eng", "Team", "ACTIVE");
        directory.upsertUnit("acme", "u-sales", "u-root", "Sales", "ACTIVE");
        directory.upsertMembership("acme", "sub-eng", "u-eng", "ACTIVE");

        // Ensure ontology path (write-through maps all units).
        new OrganizationOntologyBackfill(jdbc, store).backfillTenant("acme");
        assertTrue(new OrganizationOntologyBackfill(jdbc, store).isTenantFullyBackfilled("acme"));

        OrgScope scope = directory.resolveSelfAndDescendants("acme", "sub-eng");
        assertEquals(OrgScope.MODE_SELF_AND_DESCENDANTS, scope.mode());
        assertTrue(scope.contains("u-eng"));
        assertTrue(scope.contains("u-team"));
        assertFalse(scope.contains("u-root"));
        assertFalse(scope.contains("u-sales"));

        OrgScope selfOnly = directory.resolveSelf("acme", "sub-eng");
        assertEquals(OrgScope.MODE_SELF, selfOnly.mode());
        assertTrue(selfOnly.contains("u-eng"));
        assertFalse(selfOnly.contains("u-team"));

        // Sibling under root (CONTAINS from root, not from eng) must not enter eng closure.
        directory.upsertUnit("acme", "u-orphan", "u-root", "Orphan", "ACTIVE");
        new OrganizationOntologyBackfill(jdbc, store).backfillTenant("acme");
        OrgScope afterSibling = directory.resolveSelfAndDescendants("acme", "sub-eng");
        assertTrue(afterSibling.contains("u-eng"));
        assertTrue(afterSibling.contains("u-team"));
        assertFalse(afterSibling.contains("u-orphan"));
        assertFalse(afterSibling.contains("u-sales"));
        assertFalse(store.containsSelfAndDescendants("u-eng").contains("u-orphan"));
    }

    private static OperatorPrincipal operator(String subjectId, Set<String> permissions) {
        return new OperatorPrincipal("login", "{noop}x", "identity-1", subjectId, permissions, true);
    }
}
