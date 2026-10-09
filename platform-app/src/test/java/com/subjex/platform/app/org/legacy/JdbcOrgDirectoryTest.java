package com.subjex.platform.app.org.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.org.legacy.OrgScope;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcOrgDirectoryTest — 组织目录：列表按 id 排序、租户隔离，以及写路径校验。
 */
class JdbcOrgDirectoryTest {

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void listsUnitsAndMembershipsOrderedWithTenantIsolation(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOrgDirectory directory = new JdbcOrgDirectory(jdbc);

        directory.upsertUnit("acme", "u-root", null, "Acme Root", "ACTIVE");
        directory.upsertUnit("acme", "u-eng", "u-root", "Engineering", "ACTIVE");
        directory.upsertUnit("other", "u-other", null, "Other Co", "ACTIVE");

        directory.upsertMembership("acme", "sub-b", "u-eng", "ACTIVE");
        directory.upsertMembership("acme", "sub-a", "u-root", "ACTIVE");
        directory.upsertMembership("other", "sub-x", "u-other", "ACTIVE");

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

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void upsertsUnitsMembershipsAndValidatesParents(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOrgDirectory directory = new JdbcOrgDirectory(jdbc);

        OrgUnit root = directory.upsertUnit("acme", "u-root", null, "Root", null);
        assertEquals("ACTIVE", root.unitState());
        assertEquals(null, root.parentOrgUnitId());

        OrgUnit eng = directory.upsertUnit("acme", "u-eng", "u-root", "Engineering", "ACTIVE");
        assertEquals("u-root", eng.parentOrgUnitId());

        OrgUnit renamed = directory.upsertUnit("acme", "u-eng", "u-root", "Eng Renamed", "DISABLED");
        assertEquals("Eng Renamed", renamed.unitName());
        assertEquals("DISABLED", renamed.unitState());
        assertEquals(2, directory.listUnits("acme").size());

        OrgUnit reactivated = directory.setUnitState("acme", "u-eng", "ACTIVE");
        assertEquals("ACTIVE", reactivated.unitState());

        assertThrows(
                IllegalArgumentException.class,
                () -> directory.upsertUnit("acme", "u-x", "missing-parent", "X", "ACTIVE"));
        assertThrows(
                IllegalArgumentException.class,
                () -> directory.upsertUnit("acme", "u-cross", "u-other", "Cross", "ACTIVE"));

        directory.upsertUnit("other", "u-other", null, "Other", "ACTIVE");
        assertThrows(
                IllegalArgumentException.class,
                () -> directory.upsertUnit("acme", "u-cross", "u-other", "Cross", "ACTIVE"));

        assertThrows(
                IllegalArgumentException.class,
                () -> directory.upsertUnit("acme", "u-self", "u-self", "Self", "ACTIVE"));
        assertThrows(IllegalArgumentException.class, () -> directory.upsertUnit("acme", "u-blank", null, "  ", null));
        assertThrows(IllegalArgumentException.class, () -> directory.upsertUnit("", "u-a", null, "A", null));

        OrgMembership membership =
                directory.upsertMembership("acme", "sub-a", "u-root", null);
        assertEquals("ACTIVE", membership.membershipState());
        OrgMembership updated =
                directory.upsertMembership("acme", "sub-a", "u-root", "DISABLED");
        assertEquals("DISABLED", updated.membershipState());
        assertEquals(1, directory.listMembershipsForSubject("acme", "sub-a").size());

        assertThrows(
                IllegalArgumentException.class,
                () -> directory.upsertMembership("acme", "sub-a", "missing-unit", "ACTIVE"));

        assertTrue(directory.removeMembership("acme", "sub-a", "u-root"));
        assertFalse(directory.removeMembership("acme", "sub-a", "u-root"));
        assertTrue(directory.listMembershipsForSubject("acme", "sub-a").isEmpty());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void resolvesSelfAndDescendantsForActiveMembership(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcOrgDirectory directory = new JdbcOrgDirectory(jdbc);

        directory.upsertUnit("acme", "u-root", null, "Root", "ACTIVE");
        directory.upsertUnit("acme", "u-eng", "u-root", "Engineering", "ACTIVE");
        directory.upsertUnit("acme", "u-team", "u-eng", "Team", "ACTIVE");
        directory.upsertUnit("acme", "u-sales", "u-root", "Sales", "ACTIVE");
        directory.upsertMembership("acme", "sub-eng", "u-eng", "ACTIVE");
        directory.upsertMembership("acme", "sub-disabled", "u-eng", "DISABLED");

        OrgScope scope = directory.resolveSelfAndDescendants("acme", "sub-eng");
        assertEquals(OrgScope.MODE_SELF_AND_DESCENDANTS, scope.mode());
        assertEquals(List.of("u-eng"), scope.rootOrganizationIds());
        assertEquals(List.of("u-eng", "u-team"), scope.organizationIds());
        assertTrue(scope.contains("u-team"));
        assertFalse(scope.contains("u-root"));
        assertFalse(scope.contains("u-sales"));

        Set<String> fromRoot = directory.descendantUnitIds("acme", "u-root");
        assertTrue(fromRoot.containsAll(Set.of("u-root", "u-eng", "u-team", "u-sales")));

        assertTrue(directory.resolveSelfAndDescendants("acme", "sub-disabled").isNone());
        assertTrue(directory.resolveSelfAndDescendants("acme", "sub-missing").isNone());

        OrgScope selfOnly = directory.resolveSelf("acme", "sub-eng");
        assertEquals(OrgScope.MODE_SELF, selfOnly.mode());
        assertEquals(List.of("u-eng"), selfOnly.organizationIds());
        assertFalse(selfOnly.contains("u-team"));
    }
}
