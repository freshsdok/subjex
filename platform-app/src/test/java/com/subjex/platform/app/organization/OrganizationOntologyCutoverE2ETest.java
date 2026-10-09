package com.subjex.platform.app.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.entity.declare.EntityFieldKind;
import com.subjex.entity.declare.EntityRenderer;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.form.render.FieldKind;
import com.subjex.form.render.FormRenderer;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.org.legacy.JdbcOrgDirectory;
import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.AccessChecker;
import com.subjex.platform.app.security.AccessDecision;
import com.subjex.platform.app.security.AccessResource;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.OrganizationScope;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.subjex.platform.app.org.legacy.OrgMembership;
import com.subjex.platform.app.org.legacy.OrgUnit;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.org.legacy.OrgScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * O7/O8-6 E2E: backfill → org surface → auth scope → Relationship≠Authorization →
 * scope without map → declaration refs → console path smoke.
 * <p>
 * Pre-DROP: seeds legacy {@code org_unit}/{@code org_membership} then backfills.
 * Post-DROP: seeds via {@link JdbcOrgDirectory} ontology adapters.
 * O7/O8-6 端到端；DROP 前后均可跑。
 */
class OrganizationOntologyCutoverE2ETest {

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("E2E-01 Ontology cutover + O8-6 Relationship≠Authorization + scope without map")
    void E2E_01_ontologyCutover(H2PlatformTables.Mode mode) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);
        OrganizationOntologyBackfill backfill = new OrganizationOntologyBackfill(jdbc, store);
        JdbcOrgDirectory directory = new JdbcOrgDirectory(jdbc, store);
        boolean legacyPresent = tableExists(jdbc, "org_unit");

        seedTenant(jdbc, "acme");
        jdbc.update(
                "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?,?,?)",
                "sub-eng",
                "Engineer",
                "PERSON");

        if (legacyPresent) {
            jdbc.update(
                    "INSERT INTO org_unit (tenant_id, org_unit_id, parent_org_unit_id, unit_name, unit_state) VALUES (?,?,?,?,?)",
                    "acme",
                    "u-root",
                    null,
                    "Root",
                    "ACTIVE");
            jdbc.update(
                    "INSERT INTO org_unit (tenant_id, org_unit_id, parent_org_unit_id, unit_name, unit_state) VALUES (?,?,?,?,?)",
                    "acme",
                    "u-eng",
                    "u-root",
                    "Engineering",
                    "ACTIVE");
            jdbc.update(
                    "INSERT INTO org_unit (tenant_id, org_unit_id, parent_org_unit_id, unit_name, unit_state) VALUES (?,?,?,?,?)",
                    "acme",
                    "u-team",
                    "u-eng",
                    "Team",
                    "ACTIVE");
            jdbc.update(
                    "INSERT INTO org_membership (tenant_id, subject_id, org_unit_id, membership_state) VALUES (?,?,?,?)",
                    "acme",
                    "sub-eng",
                    "u-eng",
                    "ACTIVE");
            OrganizationOntologyBackfill.Result result = backfill.backfillTenant("acme");
            assertEquals(3, result.organizationsUpserted());
            assertEquals(2, result.relationsUpserted());
            assertEquals(1, result.membershipsUpserted());
            assertTrue(backfill.isTenantFullyBackfilled("acme"));
        } else {
            directory.upsertUnit("acme", "u-root", null, "Root", "ACTIVE");
            directory.upsertUnit("acme", "u-eng", "u-root", "Engineering", "ACTIVE");
            directory.upsertUnit("acme", "u-team", "u-eng", "Team", "ACTIVE");
            directory.upsertMembership("acme", "sub-eng", "u-eng", "ACTIVE");
            assertFalse(tableExists(jdbc, "org_unit"));
            assertFalse(tableExists(jdbc, "org_membership"));
            assertTrue(tableExists(jdbc, "org_unit_organization_map"));
        }

        // --- migration / dual-read projection ---
        List<OrgUnit> units = directory.listUnits("acme");
        assertEquals(3, units.size());
        assertEquals(
                Set.of("u-root", "u-eng", "u-team"),
                units.stream().map(OrgUnit::orgUnitId).collect(Collectors.toSet()));
        OrgUnit eng = units.stream().filter(u -> "u-eng".equals(u.orgUnitId())).findFirst().orElseThrow();
        assertEquals("u-root", eng.parentOrgUnitId());
        assertEquals("Engineering", eng.unitName());

        List<OrgMembership> memberships = directory.listMemberships("acme");
        assertEquals(1, memberships.size());
        assertEquals("sub-eng", memberships.get(0).subjectId());
        assertEquals("u-eng", memberships.get(0).orgUnitId());

        // --- org API surface (store / organization id space) ---
        assertEquals(3, store.listOrganizationsLinkedToTenant("acme").size());
        String engOrgId = backfill.findOrganizationId("acme", "u-eng").orElseThrow();
        assertTrue(store.organizationExists(engOrgId));
        assertEquals(1, store.listMembershipsForTenant("acme", "sub-eng").size());
        assertEquals(engOrgId, store.listMembershipsForTenant("acme", "sub-eng").get(0).organizationId());

        // --- authorization scope from Membership + OrganizationRelation ---
        OrgScope scope = directory.resolveSelfAndDescendants("acme", "sub-eng");
        assertEquals(OrgScope.MODE_SELF_AND_DESCENDANTS, scope.mode());
        assertEquals(List.of("u-eng"), scope.rootOrganizationIds());
        assertEquals(Set.of("u-eng", "u-team"), new HashSet<>(scope.organizationIds()));
        assertFalse(scope.contains("u-root"));
        assertTrue(directory.resolveSelfAndDescendants("acme", "sub-missing").isNone());

        OrgScope orgScope = directory.resolveOrganizationSelfAndDescendants("acme", "sub-eng");
        assertTrue(orgScope.contains(engOrgId));
        String teamOrgId = backfill.findOrganizationId("acme", "u-team").orElseThrow();
        assertTrue(orgScope.contains(teamOrgId));

        // --- declaration refs ---
        EntityRenderer entities = new EntityRenderer();
        RenderedEntity entity = entities.render("""
                entityKey: e2e-cutover
                tableName: e2e_cutover
                version: 1
                permission: page.read
                fields:
                  - name: id
                    kind: text
                    required: true
                    maxLength: 32
                  - name: assignee
                    kind: subjectRef
                    required: false
                  - name: org
                    kind: organizationRef
                    required: false
                """);
        assertEquals(EntityFieldKind.SUBJECT_REF, entity.fields().get(1).kind());
        assertEquals(EntityFieldKind.ORGANIZATION_REF, entity.fields().get(2).kind());

        FormRenderer forms = new FormRenderer();
        RenderedForm form = forms.render("""
                formKey: e2e-cutover
                titleEn: E2E
                titleZh: 端到端
                version: 1
                permission: page.read
                domainAction: entity.record.upsert
                entityKey: e2e-cutover
                fields:
                  - name: id
                    kind: text
                    required: true
                    maxLength: 32
                  - name: assignee
                    kind: subjectRef
                    required: false
                  - name: org
                    kind: organizationRef
                    required: false
                """);
        assertEquals(FieldKind.SUBJECT_REF, form.fields().get(1).kind());
        assertEquals(FieldKind.ORGANIZATION_REF, form.fields().get(2).kind());

        // --- E2E-02 Relationship ≠ Authorization (Membership alone does not grant org.write) ---
        OperatorPrincipal noPerm = new OperatorPrincipal(
                "login-e2e", "hash", "identity-e2e", "sub-eng", java.util.Set.of(), true);
        OperatorTenantAccess tenantAccess = mock(OperatorTenantAccess.class);
        when(tenantAccess.isGranted(noPerm, "acme")).thenReturn(true);
        TenantGuard tenantGuard = new DenyWhenTenantMissing();
        AccessDecision denied = AccessChecker.evaluate(
                noPerm,
                "org.write",
                true,
                "acme",
                tenantGuard,
                tenantAccess,
                AccessResource.of("organization", engOrgId),
                AccessAction.of("write"),
                directory.resolveSelfAndDescendants("acme", "sub-eng").toOrganizationScope(),
                engOrgId);
        assertFalse(denied.allowed());
        assertEquals(AccessDecision.DENY_PERMISSION_MISSING, denied.denyReason());

        // --- E2E-03 Scope without map (OrganizationScopeResolver; no map SQL) ---
        OrganizationScopeResolver scopeResolver = new OrganizationScopeResolver(store);
        OrganizationScope formalScope = scopeResolver.resolveSelfAndDescendants("acme", "sub-eng");
        assertEquals(OrganizationScope.MODE_SELF_AND_DESCENDANTS, formalScope.mode());
        assertTrue(formalScope.contains(engOrgId));
        assertTrue(formalScope.contains(teamOrgId));
        assertFalse(formalScope.contains(
                backfill.findOrganizationId("acme", "u-root").orElse("u-root")));

        // --- console path smoke (source under web/) ---
        Path console = resolveConsolePath();
        assertTrue(Files.isRegularFile(console), "missing org console: " + console);
        String consoleSrc = Files.readString(console);
        assertTrue(
                consoleSrc.contains("/api/platform/organizations"),
                "console must call /api/platform/organizations");
        assertFalse(consoleSrc.contains("/api/platform/org/units"), "console must not use legacy org units path");
    }

    private static Path resolveConsolePath() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path relative = Path.of("web/src/app/(console)/org/org-console.tsx");
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return cwd.resolve(relative);
    }

    private static boolean tableExists(JdbcTemplate jdbc, String tableName) throws Exception {
        Set<String> tables = new HashSet<>();
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        return tables.contains(tableName.toLowerCase(Locale.ROOT));
    }

    private static void seedTenant(JdbcTemplate jdbc, String tenantId) {
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?,?,?)",
                tenantId,
                tenantId,
                "ACTIVE");
    }
}
