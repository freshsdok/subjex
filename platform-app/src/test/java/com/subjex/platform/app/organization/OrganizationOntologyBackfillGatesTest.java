package com.subjex.platform.app.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.org.JdbcOrgDirectory;
import com.subjex.platform.app.org.OrgMembership;
import com.subjex.platform.app.org.OrgUnit;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.OrgScope;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MIG-01 / MIG-02 / MIG-03 machine gates for O3 backfill + dual-read.
 * O3 回填与双读机器门禁。
 */
class OrganizationOntologyBackfillGatesTest {

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("MIG-01 Existing org_unit data backfills without loss")
    void MIG_01_orgUnitBackfillsWithoutLoss(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        seedTenant(jdbc, "acme");
        jdbc.update(
                "INSERT INTO org_unit (tenant_id, org_unit_id, parent_org_unit_id, unit_name, unit_state) VALUES (?,?,?,?,?)",
                "acme",
                "u-root",
                null,
                "Acme Root",
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
                "u-old",
                "u-root",
                "Legacy Desk",
                "DISABLED");

        OrganizationOntologyBackfill backfill = new OrganizationOntologyBackfill(jdbc);
        OrganizationOntologyBackfill.Result result = backfill.backfillTenant("acme");
        assertEquals(3, result.organizationsUpserted());
        assertEquals(2, result.relationsUpserted());
        assertEquals(3, result.mapRows());
        assertTrue(backfill.isTenantFullyBackfilled("acme"));

        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);
        assertEquals(3, store.listOrganizations().size());
        assertEquals(3, store.listTenantOrganizationsForTenant("acme").size());

        // Dual-read projects the same units (no loss of id/name/state/parent).
        JdbcOrgDirectory directory = new JdbcOrgDirectory(jdbc, store);
        List<OrgUnit> units = directory.listUnits("acme");
        assertEquals(3, units.size());
        assertEquals(
                Set.of("u-root", "u-eng", "u-old"),
                units.stream().map(OrgUnit::orgUnitId).collect(Collectors.toSet()));
        OrgUnit eng = units.stream().filter(u -> "u-eng".equals(u.orgUnitId())).findFirst().orElseThrow();
        assertEquals("u-root", eng.parentOrgUnitId());
        assertEquals("Engineering", eng.unitName());
        OrgUnit old = units.stream().filter(u -> "u-old".equals(u.orgUnitId())).findFirst().orElseThrow();
        assertEquals("DISABLED", old.unitState());

        // Legacy tables still present (rollback path).
        assertEquals(
                3,
                jdbc.queryForObject("SELECT COUNT(*) FROM org_unit WHERE tenant_id = ?", Integer.class, "acme")
                        .intValue());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("MIG-02 Existing membership backfills without loss")
    void MIG_02_membershipBackfillsWithoutLoss(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        seedTenant(jdbc, "acme");
        jdbc.update(
                "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?,?,?)",
                "sub-a",
                "Alice",
                "PERSON");
        jdbc.update(
                "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?,?,?)",
                "sub-b",
                "Bob",
                "PERSON");
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
                "Eng",
                "ACTIVE");
        jdbc.update(
                "INSERT INTO org_membership (tenant_id, subject_id, org_unit_id, membership_state) VALUES (?,?,?,?)",
                "acme",
                "sub-a",
                "u-root",
                "ACTIVE");
        jdbc.update(
                "INSERT INTO org_membership (tenant_id, subject_id, org_unit_id, membership_state) VALUES (?,?,?,?)",
                "acme",
                "sub-b",
                "u-eng",
                "ACTIVE");

        OrganizationOntologyBackfill backfill = new OrganizationOntologyBackfill(jdbc);
        OrganizationOntologyBackfill.Result result = backfill.backfillTenant("acme");
        assertEquals(2, result.membershipsUpserted());

        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);
        List<Membership> forA = store.listMembershipsForSubject("sub-a");
        List<Membership> forB = store.listMembershipsForSubject("sub-b");
        assertEquals(1, forA.size());
        assertEquals(1, forB.size());
        // Membership has no tenant_id column semantics in new store.
        assertEquals("ACTIVE", forA.get(0).membershipState());

        JdbcOrgDirectory directory = new JdbcOrgDirectory(jdbc, store);
        List<OrgMembership> dual = directory.listMemberships("acme");
        assertEquals(2, dual.size());
        assertEquals(
                Set.of("sub-a|u-root", "sub-b|u-eng"),
                dual.stream()
                        .map(m -> m.subjectId() + "|" + m.orgUnitId())
                        .collect(Collectors.toSet()));

        OrgScope scope = directory.resolveSelfAndDescendants("acme", "sub-a");
        assertEquals(Set.of("u-root", "u-eng"), new HashSet<>(scope.unitIds()));

        assertEquals(
                2,
                jdbc.queryForObject("SELECT COUNT(*) FROM org_membership WHERE tenant_id = ?", Integer.class, "acme")
                        .intValue());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("MIG-03 Same-name orgs across tenants are not auto-merged")
    void MIG_03_sameNameOrgsAcrossTenantsNotAutoMerged(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        seedTenant(jdbc, "tenant-a");
        seedTenant(jdbc, "tenant-b");
        // Same org_unit_id AND same unit_name across tenants — must become two Organizations.
        jdbc.update(
                "INSERT INTO org_unit (tenant_id, org_unit_id, parent_org_unit_id, unit_name, unit_state) VALUES (?,?,?,?,?)",
                "tenant-a",
                "sales",
                null,
                "Sales",
                "ACTIVE");
        jdbc.update(
                "INSERT INTO org_unit (tenant_id, org_unit_id, parent_org_unit_id, unit_name, unit_state) VALUES (?,?,?,?,?)",
                "tenant-b",
                "sales",
                null,
                "Sales",
                "ACTIVE");

        OrganizationOntologyBackfill backfill = new OrganizationOntologyBackfill(jdbc);
        backfill.backfillAll();

        String orgA = backfill.findOrganizationId("tenant-a", "sales").orElseThrow();
        String orgB = backfill.findOrganizationId("tenant-b", "sales").orElseThrow();
        assertNotEquals(orgA, orgB, "same-name / same-unit-id across tenants must not merge");
        // Collision forces hashed ids (not reuse of bare sales).
        assertNotEquals("sales", orgA);
        assertNotEquals("sales", orgB);
        assertEquals(64, orgA.length());
        assertEquals(64, orgB.length());

        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);
        assertEquals(2, store.listOrganizations().size());
        assertEquals(1, store.listTenantOrganizationsForTenant("tenant-a").size());
        assertEquals(1, store.listTenantOrganizationsForTenant("tenant-b").size());
        assertEquals(2, store.listTenantOrganizationsForOrganization(orgA).size()
                + store.listTenantOrganizationsForOrganization(orgB).size());

        // Dual-read still exposes legacy org_unit_id "sales" per tenant.
        JdbcOrgDirectory directory = new JdbcOrgDirectory(jdbc, store);
        assertEquals("Sales", directory.listUnits("tenant-a").get(0).unitName());
        assertEquals("Sales", directory.listUnits("tenant-b").get(0).unitName());
        assertEquals("sales", directory.listUnits("tenant-a").get(0).orgUnitId());
        assertFalse(orgA.equals(orgB));
    }

    private static void seedTenant(JdbcTemplate jdbc, String tenantId) {
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?,?,?)",
                tenantId,
                tenantId,
                "ACTIVE");
    }
}
