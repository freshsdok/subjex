package com.subjex.platform.app.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.org.legacy.JdbcOrgDirectory;
import com.subjex.platform.app.org.legacy.OrgMembership;
import com.subjex.platform.app.org.legacy.OrgUnit;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.org.legacy.OrgScope;
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
 * MIG-01 / MIG-02 / MIG-03 — post-O7: legacy tables dropped; map + ontology preserve cutover
 * invariants (no loss of unit/membership projection; no cross-tenant auto-merge).
 * O7 后旧表已删；门禁改为映射+本体投影与禁跨租户合并。
 */
class OrganizationOntologyBackfillGatesTest {

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("MIG-01 Existing org_unit data backfills without loss")
    void MIG_01_orgUnitBackfillsWithoutLoss(H2PlatformTables.Mode mode) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        assertFalse(tableExists(jdbc, "org_unit"), "O7 must DROP org_unit");
        assertTrue(tableExists(jdbc, "org_unit_organization_map"));

        seedTenant(jdbc, "acme");
        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);
        JdbcOrgDirectory directory = new JdbcOrgDirectory(jdbc, store);
        directory.upsertUnit("acme", "u-root", null, "Acme Root", "ACTIVE");
        directory.upsertUnit("acme", "u-eng", "u-root", "Engineering", "ACTIVE");
        directory.upsertUnit("acme", "u-old", "u-root", "Legacy Desk", "DISABLED");

        assertEquals(3, store.listOrganizations().size());
        assertEquals(3, store.listTenantOrganizationsForTenant("acme").size());

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
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("MIG-02 Existing membership backfills without loss")
    void MIG_02_membershipBackfillsWithoutLoss(H2PlatformTables.Mode mode) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        assertFalse(tableExists(jdbc, "org_membership"), "O7 must DROP org_membership");

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

        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);
        JdbcOrgDirectory directory = new JdbcOrgDirectory(jdbc, store);
        directory.upsertUnit("acme", "u-root", null, "Root", "ACTIVE");
        directory.upsertUnit("acme", "u-eng", "u-root", "Eng", "ACTIVE");
        directory.upsertMembership("acme", "sub-a", "u-root", "ACTIVE");
        directory.upsertMembership("acme", "sub-b", "u-eng", "ACTIVE");

        List<Membership> forA = store.listMembershipsForSubject("sub-a");
        List<Membership> forB = store.listMembershipsForSubject("sub-b");
        assertEquals(1, forA.size());
        assertEquals(1, forB.size());
        assertEquals("ACTIVE", forA.get(0).membershipState());

        List<OrgMembership> dual = directory.listMemberships("acme");
        assertEquals(2, dual.size());
        assertEquals(
                Set.of("sub-a|u-root", "sub-b|u-eng"),
                dual.stream()
                        .map(m -> m.subjectId() + "|" + m.orgUnitId())
                        .collect(Collectors.toSet()));

        OrgScope scope = directory.resolveSelfAndDescendants("acme", "sub-a");
        assertEquals(Set.of("u-root", "u-eng"), new HashSet<>(scope.organizationIds()));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("MIG-03 Same-name orgs across tenants are not auto-merged")
    void MIG_03_sameNameOrgsAcrossTenantsNotAutoMerged(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        seedTenant(jdbc, "tenant-a");
        seedTenant(jdbc, "tenant-b");

        OrganizationOntologyBackfill backfill = new OrganizationOntologyBackfill(jdbc);
        JdbcOrgDirectory directory = new JdbcOrgDirectory(jdbc);
        directory.upsertUnit("tenant-a", "sales", null, "Sales", "ACTIVE");
        directory.upsertUnit("tenant-b", "sales", null, "Sales", "ACTIVE");

        String orgA = backfill.findOrganizationId("tenant-a", "sales").orElseThrow();
        String orgB = backfill.findOrganizationId("tenant-b", "sales").orElseThrow();
        assertNotEquals(orgA, orgB, "same-name / same-unit-id across tenants must not merge");
        assertNotEquals("sales", orgB);
        assertEquals(64, orgB.length());

        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);
        assertEquals(2, store.listOrganizations().size());
        assertEquals(1, store.listTenantOrganizationsForTenant("tenant-a").size());
        assertEquals(1, store.listTenantOrganizationsForTenant("tenant-b").size());

        assertEquals("Sales", directory.listUnits("tenant-a").get(0).unitName());
        assertEquals("Sales", directory.listUnits("tenant-b").get(0).unitName());
        // O8-4 ontology-first: legacy wire id is organization_id (map alias still in table)
        assertEquals(orgA, directory.listUnits("tenant-a").get(0).orgUnitId());
        assertEquals(orgB, directory.listUnits("tenant-b").get(0).orgUnitId());
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
