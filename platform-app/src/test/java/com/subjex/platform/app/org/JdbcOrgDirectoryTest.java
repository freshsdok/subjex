package com.subjex.platform.app.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.security.H2PlatformTables;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcOrgDirectoryTest — 组织目录：列表按 id 排序，且租户隔离。
 */
class JdbcOrgDirectoryTest {

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void listsUnitsAndMembershipsOrderedWithTenantIsolation(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOrgDirectory directory = new JdbcOrgDirectory(jdbc);

        jdbc.update(
                "INSERT INTO org_unit (tenant_id, org_unit_id, parent_org_unit_id, unit_name, unit_state) VALUES (?,?,?,?,?)",
                "acme", "u-root", null, "Acme Root", "ACTIVE");
        jdbc.update(
                "INSERT INTO org_unit (tenant_id, org_unit_id, parent_org_unit_id, unit_name, unit_state) VALUES (?,?,?,?,?)",
                "acme", "u-eng", "u-root", "Engineering", "ACTIVE");
        jdbc.update(
                "INSERT INTO org_unit (tenant_id, org_unit_id, parent_org_unit_id, unit_name, unit_state) VALUES (?,?,?,?,?)",
                "other", "u-other", null, "Other Co", "ACTIVE");

        jdbc.update(
                "INSERT INTO org_membership (tenant_id, subject_id, org_unit_id, membership_state) VALUES (?,?,?,?)",
                "acme", "sub-b", "u-eng", "ACTIVE");
        jdbc.update(
                "INSERT INTO org_membership (tenant_id, subject_id, org_unit_id, membership_state) VALUES (?,?,?,?)",
                "acme", "sub-a", "u-root", "ACTIVE");
        jdbc.update(
                "INSERT INTO org_membership (tenant_id, subject_id, org_unit_id, membership_state) VALUES (?,?,?,?)",
                "other", "sub-x", "u-other", "ACTIVE");

        List<OrgUnit> units = directory.listUnits("acme");
        assertEquals(2, units.size());
        assertEquals("u-eng", units.get(0).orgUnitId());
        assertEquals("u-root", units.get(1).orgUnitId());
        assertEquals("u-root", units.get(0).parentOrgUnitId());
        assertEquals(null, units.get(1).parentOrgUnitId());
        assertTrue(directory.listUnits("other").stream().noneMatch(u -> "acme".equals(u.tenantId())));
        assertEquals(1, directory.listUnits("other").size());
        assertEquals("u-other", directory.listUnits("other").get(0).orgUnitId());

        List<OrgMembership> memberships = directory.listMemberships("acme");
        assertEquals(2, memberships.size());
        assertEquals("sub-a", memberships.get(0).subjectId());
        assertEquals("sub-b", memberships.get(1).subjectId());
        assertEquals(1, directory.listMemberships("other").size());

        List<OrgMembership> forSubject = directory.listMembershipsForSubject("acme", "sub-a");
        assertEquals(1, forSubject.size());
        assertEquals("u-root", forSubject.get(0).orgUnitId());
        assertTrue(directory.listMembershipsForSubject("acme", "sub-missing").isEmpty());
    }
}
