package com.subjex.platform.app.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.AccessChecker;
import com.subjex.platform.app.security.AccessDecision;
import com.subjex.platform.app.security.AccessResource;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.OrganizationScope;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * O8-1 gates: OrganizationScope from Membership + CONTAINS (no map / JdbcOrgDirectory).
 */
class OrganizationScopeResolverTest {

    private final TenantGuard tenantGuard = new DenyWhenTenantMissing();

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("O8 SELF = direct Membership orgs only")
    void selfIsDirectMembershipOnly(H2PlatformTables.Mode mode) {
        Fixture f = Fixture.seed(mode);
        OrganizationScope self = f.resolver.resolveSelf("acme", "sub-eng");
        assertEquals(OrganizationScope.MODE_SELF, self.mode());
        assertTrue(self.contains("org-eng"));
        assertFalse(self.contains("org-team"));
        assertFalse(self.contains("org-root"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("O8 SELF_AND_DESCENDANTS via CONTAINS only")
    void selfAndDescendantsViaContainsOnly(H2PlatformTables.Mode mode) {
        Fixture f = Fixture.seed(mode);
        OrganizationScope scope = f.resolver.resolveSelfAndDescendants("acme", "sub-eng");
        assertEquals(OrganizationScope.MODE_SELF_AND_DESCENDANTS, scope.mode());
        assertTrue(scope.contains("org-eng"));
        assertTrue(scope.contains("org-team"));
        assertFalse(scope.contains("org-root"));
        assertFalse(scope.contains("org-sales"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("O8 no Membership → Scope NONE (never UNRESTRICTED)")
    void noMembershipIsNone(H2PlatformTables.Mode mode) {
        Fixture f = Fixture.seed(mode);
        OrganizationScope scope = f.resolver.resolveSelfAndDescendants("acme", "sub-none");
        assertTrue(scope.isNone());
        assertFalse(scope.isUnrestricted());
        assertFalse(scope.contains("org-eng"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("O8 CONTAINS cycle rejected at write; scope stays acyclic")
    void containsCycleRejected(H2PlatformTables.Mode mode) {
        Fixture f = Fixture.seed(mode);
        boolean threw = false;
        try {
            f.store.upsertRelation("org-team", "org-eng", RelationKind.CONTAINS, "ACTIVE");
        } catch (IllegalArgumentException ex) {
            threw = true;
            assertTrue(ex.getMessage().toLowerCase().contains("cycle"));
        }
        assertTrue(threw);
        assertTrue(f.store.wouldCreateContainsCycle("org-team", "org-eng"));
        OrganizationScope scope = f.resolver.resolveSelfAndDescendants("acme", "sub-eng");
        assertTrue(scope.contains("org-eng"));
        assertTrue(scope.contains("org-team"));
        assertFalse(scope.contains("org-root"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("O8 fail-closed when scope NONE vs resource organization")
    void failClosedWhenNone(H2PlatformTables.Mode mode) {
        OperatorPrincipal operator = new OperatorPrincipal(
                "login", "{noop}x", "identity-1", "sub-none", Set.of("org.write"), true);
        AccessDecision decision = AccessChecker.evaluate(
                operator,
                "org.write",
                false,
                "acme",
                tenantGuard,
                null,
                AccessResource.of("organization", "org-eng"),
                AccessAction.of("write"),
                OrganizationScope.none(),
                "org-eng");
        assertFalse(decision.allowed());
        assertEquals(AccessDecision.DENY_ORG_OUT_OF_SCOPE, decision.denyReason());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("O8 Relationship ≠ Authorization (membership+scope without permission)")
    void relationshipDoesNotAuthorize(H2PlatformTables.Mode mode) {
        Fixture f = Fixture.seed(mode);
        OrganizationScope scope = f.resolver.resolveSelfAndDescendants("acme", "sub-eng");
        OperatorPrincipal noPerm = new OperatorPrincipal(
                "login", "{noop}x", "identity-1", "sub-eng", Set.of(), true);
        OperatorTenantAccess tenantAccess = mock(OperatorTenantAccess.class);
        when(tenantAccess.isGranted(noPerm, "acme")).thenReturn(true);
        AccessDecision decision = AccessChecker.evaluate(
                noPerm,
                "org.write",
                true,
                "acme",
                tenantGuard,
                tenantAccess,
                AccessResource.of("organization", "org-eng"),
                AccessAction.of("write"),
                scope,
                "org-eng");
        assertFalse(decision.allowed());
        assertEquals(AccessDecision.DENY_PERMISSION_MISSING, decision.denyReason());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("O8 membership in other tenant org does not expand for this tenant")
    void otherTenantMembershipDoesNotExpand(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);
        OrganizationScopeResolver resolver = new OrganizationScopeResolver(store);
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
        store.upsertOrganization("org-shared", "Shared", "ACTIVE");
        store.upsertTenantOrganization("beta", "org-shared", "ACTIVE");
        store.upsertMembership("sub-x", "org-shared", "ACTIVE");
        assertTrue(resolver.resolveSelfAndDescendants("acme", "sub-x").isNone());
        assertFalse(resolver.resolveSelfAndDescendants("beta", "sub-x").isNone());
    }

    private record Fixture(
            JdbcOrganizationStore store, OrganizationScopeResolver resolver) {
        static Fixture seed(H2PlatformTables.Mode mode) {
            JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
            JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);
            OrganizationScopeResolver resolver = new OrganizationScopeResolver(store);
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
            jdbc.update(
                    "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?,?,?)",
                    "sub-none",
                    "None",
                    "PERSON");
            store.upsertOrganization("org-root", "Root", "ACTIVE");
            store.upsertOrganization("org-eng", "Eng", "ACTIVE");
            store.upsertOrganization("org-team", "Team", "ACTIVE");
            store.upsertOrganization("org-sales", "Sales", "ACTIVE");
            store.upsertTenantOrganization("acme", "org-root", "ACTIVE");
            store.upsertTenantOrganization("acme", "org-eng", "ACTIVE");
            store.upsertTenantOrganization("acme", "org-team", "ACTIVE");
            store.upsertTenantOrganization("acme", "org-sales", "ACTIVE");
            store.setContainsParent("org-eng", "org-root");
            store.setContainsParent("org-team", "org-eng");
            store.setContainsParent("org-sales", "org-root");
            store.upsertMembership("sub-eng", "org-eng", "ACTIVE");
            return new Fixture(store, resolver);
        }
    }
}
