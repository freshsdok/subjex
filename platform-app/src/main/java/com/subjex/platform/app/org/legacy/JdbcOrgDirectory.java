package com.subjex.platform.app.org.legacy;

import com.subjex.platform.app.organization.JdbcOrganizationStore;
import com.subjex.platform.app.organization.OrganizationOntologyBackfill;
import com.subjex.platform.app.organization.RelationKind;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcOrgDirectory — 组织目录：按租户列出并写入组织单元与成员关系（旧 API 形状）。
 * <p>
 * O8-4: ontology-first. Reads project from {@code organization} / {@code tenant_organization} /
 * {@code membership} / CONTAINS using {@code organization_id} as the legacy wire {@code orgUnitId}
 * (synthetic identity when ids were globally unique). No {@code org_unit}/{@code org_membership}
 * SQL (tables dropped in V24). Map is only touched via {@link OrganizationOntologyBackfill}
 * write-through for legacy alias rows — not on formal Organization API / Policy / scope resolver.
 * <p>
 * Deprecated {@code /api/v1/org/**} thin adapter. Pure JDBC; bean in {@code PlatformWiring}.
 */
@Deprecated(since = "O8-3", forRemoval = false)
public final class JdbcOrgDirectory {

    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String STATE_DISABLED = "DISABLED";

    private final JdbcTemplate jdbc;
    private final JdbcOrganizationStore organizationStore;
    private final OrganizationOntologyBackfill backfill;

    public JdbcOrgDirectory(JdbcTemplate jdbc) {
        this(jdbc, new JdbcOrganizationStore(jdbc));
    }

    public JdbcOrgDirectory(JdbcTemplate jdbc, JdbcOrganizationStore organizationStore) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.organizationStore =
                organizationStore != null ? organizationStore : new JdbcOrganizationStore(jdbc);
        this.backfill = new OrganizationOntologyBackfill(jdbc, this.organizationStore);
    }

    /** Units in one tenant, ordered by org_unit_id — 某租户内组织单元，按 org_unit_id 排序。 */
    public List<OrgUnit> listUnits(String tenantId) {
        return listUnitsFromOntology(requireTenantId(tenantId));
    }

    /** Memberships in one tenant, ordered by subject then unit — 某租户内成员关系，按主体再单元排序。 */
    public List<OrgMembership> listMemberships(String tenantId) {
        return listMembershipsFromOntology(requireTenantId(tenantId), null);
    }

    /** Memberships for one subject in a tenant — 某租户内某一主体的成员关系。 */
    public List<OrgMembership> listMembershipsForSubject(String tenantId, String subjectId) {
        String tid = requireTenantId(tenantId);
        String sid = requireNonBlank(subjectId, "subjectId");
        return listMembershipsFromOntology(tid, sid);
    }

    /**
     * Resolve {@link OrgScope#MODE_SELF_AND_DESCENDANTS} for a subject in a tenant.
     * Roots = ACTIVE membership organization ids; expanded via ACTIVE CONTAINS.
     * Empty ACTIVE memberships → {@link OrgScope#none()} (fail-closed).
     * 解析「本组织及下级」。无 ACTIVE 成员返回 NONE（fail-closed）。
     */
    public OrgScope resolveSelfAndDescendants(String tenantId, String subjectId) {
        String tid = requireTenantId(tenantId);
        String sid = requireNonBlank(subjectId, "subjectId");
        List<String> roots = activeMembershipOrganizationIds(tid, sid);
        if (roots.isEmpty()) {
            return OrgScope.none();
        }
        Set<String> expanded = expandFromOntology(new HashSet<>(roots));
        return OrgScope.selfAndDescendants(roots, expanded);
    }

    /**
     * OrgScope in organization_id space. O8-4: same as {@link #resolveSelfAndDescendants}
     * (legacy wire ids are already organization ids).
     */
    public OrgScope resolveOrganizationSelfAndDescendants(String tenantId, String subjectId) {
        return resolveSelfAndDescendants(tenantId, subjectId);
    }

    /**
     * Resolve {@link OrgScope#MODE_SELF} — ACTIVE membership orgs only (no descendants).
     * Empty → {@link OrgScope#none()}.
     */
    public OrgScope resolveSelf(String tenantId, String subjectId) {
        String tid = requireTenantId(tenantId);
        String sid = requireNonBlank(subjectId, "subjectId");
        List<String> roots = activeMembershipOrganizationIds(tid, sid);
        if (roots.isEmpty()) {
            return OrgScope.none();
        }
        return OrgScope.self(roots);
    }

    /**
     * One root plus all descendants in the tenant tree (CONTAINS BFS).
     */
    public Set<String> descendantUnitIds(String tenantId, String rootUnitId) {
        requireTenantId(tenantId);
        String root = requireNonBlank(rootUnitId, "rootUnitId");
        return expandFromOntology(Set.of(root));
    }

    /**
     * Expand many roots to self + descendants — 展开多个根为自身及下级。
     */
    public Set<String> expandSelfAndDescendants(String tenantId, Collection<String> rootOrganizationIds) {
        requireTenantId(tenantId);
        Set<String> roots = new HashSet<>();
        if (rootOrganizationIds != null) {
            for (String id : rootOrganizationIds) {
                if (id != null && !id.isBlank()) {
                    roots.add(id.trim());
                }
            }
        }
        if (roots.isEmpty()) {
            return Set.of();
        }
        return expandFromOntology(roots);
    }

    /**
     * Insert or update one org unit in a tenant. Parent (when set) must exist in the same tenant.
     * Blank {@code unitState} defaults to {@link #STATE_ACTIVE}.
     */
    public OrgUnit upsertUnit(
            String tenantId, String orgUnitId, String parentOrgUnitId, String unitName, String unitState) {
        String tid = requireTenantId(tenantId);
        String uid = requireNonBlank(orgUnitId, "orgUnitId");
        String name = requireNonBlank(unitName, "unitName");
        String state = blankToDefault(unitState, STATE_ACTIVE);
        String parent = blankToNull(parentOrgUnitId);
        if (parent != null) {
            if (parent.equals(uid)) {
                throw new IllegalArgumentException("parent org unit cannot be self");
            }
            requireUnitExists(tid, parent, "parent org unit not found");
        }
        OrgUnit saved = new OrgUnit(tid, uid, parent, name, state);
        backfill.syncUnitWriteThrough(
                tid, saved.orgUnitId(), saved.parentOrgUnitId(), saved.unitName(), saved.unitState());
        return saved;
    }

    /**
     * Soft-disable or re-activate a unit ({@link #STATE_DISABLED} / {@link #STATE_ACTIVE}).
     */
    public OrgUnit setUnitState(String tenantId, String orgUnitId, String state) {
        String tid = requireTenantId(tenantId);
        String uid = requireNonBlank(orgUnitId, "orgUnitId");
        String st = requireNonBlank(state, "unitState");
        OrgUnit current = findUnit(tid, uid)
                .orElseThrow(() -> new IllegalArgumentException("org unit not found"));
        OrgUnit saved = new OrgUnit(tid, uid, current.parentOrgUnitId(), current.unitName(), st);
        backfill.syncUnitWriteThrough(
                tid, saved.orgUnitId(), saved.parentOrgUnitId(), saved.unitName(), saved.unitState());
        return saved;
    }

    /**
     * Insert or update membership. Unit must exist in the tenant. Blank state → ACTIVE.
     */
    public OrgMembership upsertMembership(
            String tenantId, String subjectId, String orgUnitId, String membershipState) {
        String tid = requireTenantId(tenantId);
        String sid = requireNonBlank(subjectId, "subjectId");
        String uid = requireNonBlank(orgUnitId, "orgUnitId");
        String state = blankToDefault(membershipState, STATE_ACTIVE);
        requireUnitExists(tid, uid, "org unit not found");
        OrgMembership saved = new OrgMembership(tid, sid, uid, state);
        backfill.syncMembershipWriteThrough(
                tid, saved.subjectId(), saved.orgUnitId(), saved.membershipState());
        return saved;
    }

    /**
     * Delete one membership; returns {@code false} when no row matched (HTTP maps to 404).
     */
    public boolean removeMembership(String tenantId, String subjectId, String orgUnitId) {
        String tid = requireTenantId(tenantId);
        String sid = requireNonBlank(subjectId, "subjectId");
        String uid = requireNonBlank(orgUnitId, "orgUnitId");
        boolean any = listMembershipsFromOntology(tid, sid).stream()
                .anyMatch(m -> uid.equals(m.orgUnitId()));
        if (!any) {
            return false;
        }
        backfill.endMembershipWriteThrough(tid, sid, uid);
        return true;
    }

    /** Whether a unit row exists in the tenant — 租户内是否已有该单元行。 */
    public boolean unitExists(String tenantId, String orgUnitId) {
        String tid = requireTenantId(tenantId);
        String uid = requireNonBlank(orgUnitId, "orgUnitId");
        return findUnit(tid, uid).isPresent();
    }

    /**
     * Ontology projection: organization_id as legacy orgUnitId; parent from ACTIVE CONTAINS.
     * No map join (O8-4).
     */
    private List<OrgUnit> listUnitsFromOntology(String tenantId) {
        return jdbc.query(
                """
                SELECT torg.tenant_id AS tenant_id,
                       o.organization_id AS org_unit_id,
                       r.from_organization_id AS parent_org_unit_id,
                       o.organization_name AS unit_name,
                       o.organization_state AS unit_state
                FROM tenant_organization torg
                INNER JOIN organization o ON o.organization_id = torg.organization_id
                LEFT JOIN organization_relation r
                    ON r.to_organization_id = o.organization_id
                   AND r.relation_kind = ?
                   AND r.relation_state = ?
                WHERE torg.tenant_id = ?
                  AND torg.link_state = ?
                ORDER BY o.organization_id
                """,
                (row, n) -> new OrgUnit(
                        row.getString("tenant_id"),
                        row.getString("org_unit_id"),
                        row.getString("parent_org_unit_id"),
                        row.getString("unit_name"),
                        row.getString("unit_state")),
                RelationKind.CONTAINS,
                JdbcOrganizationStore.STATE_ACTIVE,
                tenantId,
                JdbcOrganizationStore.STATE_ACTIVE);
    }

    private List<OrgMembership> listMembershipsFromOntology(String tenantId, String subjectIdOrNull) {
        if (subjectIdOrNull == null) {
            return jdbc.query(
                    """
                    SELECT torg.tenant_id AS tenant_id,
                           mem.subject_id AS subject_id,
                           mem.organization_id AS org_unit_id,
                           mem.membership_state AS membership_state
                    FROM membership mem
                    INNER JOIN tenant_organization torg
                        ON torg.organization_id = mem.organization_id
                       AND torg.tenant_id = ?
                       AND torg.link_state = ?
                    WHERE mem.membership_state <> ?
                    ORDER BY mem.subject_id, mem.organization_id
                    """,
                    (row, n) -> new OrgMembership(
                            row.getString("tenant_id"),
                            row.getString("subject_id"),
                            row.getString("org_unit_id"),
                            row.getString("membership_state")),
                    tenantId,
                    JdbcOrganizationStore.STATE_ACTIVE,
                    JdbcOrganizationStore.STATE_ENDED);
        }
        return jdbc.query(
                """
                SELECT torg.tenant_id AS tenant_id,
                       mem.subject_id AS subject_id,
                       mem.organization_id AS org_unit_id,
                       mem.membership_state AS membership_state
                FROM membership mem
                INNER JOIN tenant_organization torg
                    ON torg.organization_id = mem.organization_id
                   AND torg.tenant_id = ?
                   AND torg.link_state = ?
                WHERE mem.subject_id = ?
                  AND mem.membership_state <> ?
                ORDER BY mem.organization_id
                """,
                (row, n) -> new OrgMembership(
                        row.getString("tenant_id"),
                        row.getString("subject_id"),
                        row.getString("org_unit_id"),
                        row.getString("membership_state")),
                tenantId,
                JdbcOrganizationStore.STATE_ACTIVE,
                subjectIdOrNull,
                JdbcOrganizationStore.STATE_ENDED);
    }

    private List<String> activeMembershipOrganizationIds(String tenantId, String subjectId) {
        return jdbc.query(
                """
                SELECT mem.organization_id
                FROM membership mem
                INNER JOIN tenant_organization torg
                    ON torg.organization_id = mem.organization_id
                   AND torg.tenant_id = ?
                   AND torg.link_state = ?
                WHERE mem.subject_id = ? AND mem.membership_state = ?
                ORDER BY mem.organization_id
                """,
                (row, n) -> row.getString("organization_id"),
                tenantId,
                JdbcOrganizationStore.STATE_ACTIVE,
                subjectId,
                JdbcOrganizationStore.STATE_ACTIVE);
    }

    private Set<String> expandFromOntology(Set<String> rootOrganizationIds) {
        Set<String> out = new HashSet<>();
        for (String root : rootOrganizationIds) {
            out.addAll(organizationStore.containsSelfAndDescendants(root));
        }
        return Set.copyOf(out);
    }

    private Optional<OrgUnit> findUnit(String tenantId, String orgUnitId) {
        return listUnitsFromOntology(tenantId).stream()
                .filter(u -> orgUnitId.equals(u.orgUnitId()))
                .findFirst();
    }

    private void requireUnitExists(String tenantId, String orgUnitId, String message) {
        if (findUnit(tenantId, orgUnitId).isPresent()) {
            return;
        }
        // Historical map alias: org_unit_id may differ from organization_id (MIG-03 hash).
        if (backfill.findOrganizationId(tenantId, orgUnitId).isPresent()) {
            return;
        }
        throw new IllegalArgumentException(message);
    }

    private static String requireTenantId(String tenantId) {
        return requireNonBlank(tenantId, "tenantId");
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String blankToDefault(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }
}
