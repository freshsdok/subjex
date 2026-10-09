package com.subjex.platform.app.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.security.H2PlatformTables;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Machine gates MODEL / MEM / ORG / TENANT for O2 JdbcOrganizationStore.
 * O2 机器门禁：MODEL / MEM / ORG / TENANT。
 */
class JdbcOrganizationStoreGatesTest {

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("MODEL-01 Organization exists without Tenant")
    void MODEL_01_organizationExistsWithoutTenant(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);

        Organization org = store.upsertOrganization("org-solo", "Solo Org", null);
        assertEquals("org-solo", org.organizationId());
        assertEquals("ACTIVE", org.organizationState());
        assertTrue(store.listTenantOrganizationsForOrganization("org-solo").isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tenant_organization WHERE organization_id = ?", Integer.class, "org-solo"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("MODEL-02 Tenant exists without Organization")
    void MODEL_02_tenantExistsWithoutOrganization(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);

        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?,?,?)",
                "tenant-solo",
                "Solo Tenant",
                "ACTIVE");
        assertTrue(store.listOrganizations().isEmpty() || store.listTenantOrganizationsForTenant("tenant-solo").isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tenant_organization WHERE tenant_id = ?", Integer.class, "tenant-solo"));
        assertEquals(
                1,
                jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Integer.class, "tenant-solo").intValue());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("MODEL-03 Subject exists without Organization")
    void MODEL_03_subjectExistsWithoutOrganization(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);

        jdbc.update(
                "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?,?,?)",
                "sub-solo",
                "Solo",
                "PERSON");
        assertTrue(store.listMembershipsForSubject("sub-solo").isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM membership WHERE subject_id = ?", Integer.class, "sub-solo"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("MEM-01 Membership contains no tenant semantics")
    void MEM_01_membershipContainsNoTenantSemantics(H2PlatformTables.Mode mode) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        Set<String> columns = new HashSet<>();
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet rs = meta.getColumns(null, null, "MEMBERSHIP", null)) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }
            if (columns.isEmpty()) {
                try (ResultSet rs = meta.getColumns(null, null, "membership", null)) {
                    while (rs.next()) {
                        columns.add(rs.getString("COLUMN_NAME").toLowerCase());
                    }
                }
            }
        }
        assertFalse(columns.isEmpty(), "membership table columns");
        assertFalse(columns.contains("tenant_id"), "membership must not have tenant_id: " + columns);
        assertTrue(columns.contains("subject_id"));
        assertTrue(columns.contains("organization_id"));
        assertTrue(columns.contains("membership_state"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("MEM-02 Membership requires existing Subject")
    void MEM_02_membershipRequiresExistingSubject(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);
        store.upsertOrganization("org-a", "A", null);
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> store.upsertMembership("missing-sub", "org-a", null));
        assertTrue(ex.getMessage().contains("subject not found"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("MEM-03 Membership requires existing Organization")
    void MEM_03_membershipRequiresExistingOrganization(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);
        jdbc.update(
                "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?,?,?)",
                "sub-a",
                "A",
                "PERSON");
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> store.upsertMembership("sub-a", "missing-org", null));
        assertTrue(ex.getMessage().contains("organization not found"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("ORG-01 Organization may contain Organization")
    void ORG_01_organizationMayContainOrganization(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);
        store.upsertOrganization("parent", "Parent", null);
        store.upsertOrganization("child", "Child", null);
        OrganizationRelation edge =
                store.upsertRelation("parent", "child", RelationKind.CONTAINS, null);
        assertEquals(RelationKind.CONTAINS, edge.relationKind());
        assertEquals("ACTIVE", edge.relationState());
        assertTrue(store.containsSelfAndDescendants("parent").containsAll(Set.of("parent", "child")));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("ORG-02 Organization relation cannot self-reference")
    void ORG_02_organizationRelationCannotSelfReference(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);
        store.upsertOrganization("org-x", "X", null);
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> store.upsertRelation("org-x", "org-x", RelationKind.CONTAINS, null));
        assertTrue(ex.getMessage().contains("self-reference"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("ORG-03 CONTAINS relation cannot form cycle")
    void ORG_03_containsRelationCannotFormCycle(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);
        store.upsertOrganization("a", "A", null);
        store.upsertOrganization("b", "B", null);
        store.upsertOrganization("c", "C", null);
        store.upsertRelation("a", "b", RelationKind.CONTAINS, null);
        store.upsertRelation("b", "c", RelationKind.CONTAINS, null);
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> store.upsertRelation("c", "a", RelationKind.CONTAINS, null));
        assertTrue(ex.getMessage().toLowerCase().contains("cycle"));
        // direct reverse also blocked
        IllegalArgumentException reverse = assertThrows(
                IllegalArgumentException.class,
                () -> store.upsertRelation("b", "a", RelationKind.CONTAINS, null));
        assertTrue(reverse.getMessage().toLowerCase().contains("cycle"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("TENANT-01 Organization may join multiple Tenants")
    void TENANT_01_organizationMayJoinMultipleTenants(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);
        store.upsertOrganization("shared-org", "Shared", null);
        jdbc.update("INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?,?,?)", "t1", "T1", "ACTIVE");
        jdbc.update("INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?,?,?)", "t2", "T2", "ACTIVE");
        store.upsertTenantOrganization("t1", "shared-org", null);
        store.upsertTenantOrganization("t2", "shared-org", null);
        List<TenantOrganization> links = store.listTenantOrganizationsForOrganization("shared-org");
        assertEquals(2, links.size());
        assertEquals("t1", links.get(0).tenantId());
        assertEquals("t2", links.get(1).tenantId());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("TENANT-02 Tenant may contain multiple Organizations")
    void TENANT_02_tenantMayContainMultipleOrganizations(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);
        store.upsertOrganization("o1", "O1", null);
        store.upsertOrganization("o2", "O2", null);
        jdbc.update("INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?,?,?)", "t-multi", "TM", "ACTIVE");
        store.upsertTenantOrganization("t-multi", "o1", null);
        store.upsertTenantOrganization("t-multi", "o2", null);
        assertEquals(2, store.listTenantOrganizationsForTenant("t-multi").size());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("TENANT-03 TenantOrganization does not grant Permission")
    void TENANT_03_tenantOrganizationDoesNotGrantPermission(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOrganizationStore store = new JdbcOrganizationStore(jdbc);
        store.upsertOrganization("org-p", "P", null);
        jdbc.update("INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?,?,?)", "t-p", "TP", "ACTIVE");
        jdbc.update(
                "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?,?,?)",
                "sub-p",
                "P",
                "PERSON");
        Integer rolesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM subject_role WHERE subject_id = ?", Integer.class, "sub-p");
        Integer permsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM role_permission", Integer.class);
        store.upsertTenantOrganization("t-p", "org-p", null);
        store.upsertMembership("sub-p", "org-p", null);
        Integer rolesAfter = jdbc.queryForObject("SELECT COUNT(*) FROM subject_role WHERE subject_id = ?", Integer.class, "sub-p");
        Integer permsAfter = jdbc.queryForObject("SELECT COUNT(*) FROM role_permission", Integer.class);
        assertEquals(rolesBefore, rolesAfter, "TenantOrganization/Membership must not assign subject_role");
        assertEquals(permsBefore, permsAfter, "TenantOrganization/Membership must not change role_permission");
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("O7 legacy org_unit tables dropped; ontology + map remain")
    void legacyOrgUnitTablesDroppedOntologyRemains(H2PlatformTables.Mode mode) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        Set<String> tables = new HashSet<>();
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME").toLowerCase());
                }
            }
        }
        assertFalse(tables.contains("org_unit"), tables.toString());
        assertFalse(tables.contains("org_membership"), tables.toString());
        assertTrue(tables.contains("organization"), tables.toString());
        assertTrue(tables.contains("membership"), tables.toString());
        assertTrue(tables.contains("organization_relation"), tables.toString());
        assertTrue(tables.contains("tenant_organization"), tables.toString());
        assertTrue(tables.contains("org_unit_organization_map"), tables.toString());
    }
}
