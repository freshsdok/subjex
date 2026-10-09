package com.subjex.platform.app.org;

import com.subjex.platform.app.organization.JdbcOrganizationStore;
import com.subjex.platform.app.organization.OrganizationOntologyBackfill;
import com.subjex.platform.app.organization.RelationKind;
import com.subjex.platform.app.security.OrgScope;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcOrgDirectory — 组织目录：按租户列出并写入组织单元与成员关系（旧 API 形状）。
 * <p>
 * O7: reads/writes use Organization ontology + {@code org_unit_organization_map}.
 * Legacy {@code org_unit}/{@code org_membership} are dropped (V24). Deprecated
 * {@code /api/v1/org/**} stays as a thin adapter projecting map aliases.
 * <p>
 * Pre-O7 dual-read remains if legacy tables are still present (upgrade window).
 * Pure JDBC; bean in {@code PlatformWiring}.
 */
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
        String id = requireTenantId(tenantId);
        if (preferOntology(id)) {
            return listUnitsFromOntology(id);
        }
        return listUnitsFromLegacy(id);
    }

    /** Memberships in one tenant, ordered by subject then unit — 某租户内成员关系，按主体再单元排序。 */
    public List<OrgMembership> listMemberships(String tenantId) {
        String id = requireTenantId(tenantId);
        if (preferOntology(id)) {
            return listMembershipsFromOntology(id, null);
        }
        return listMembershipsFromLegacy(id);
    }

    /** Memberships for one subject in a tenant — 某租户内某一主体的成员关系。 */
    public List<OrgMembership> listMembershipsForSubject(String tenantId, String subjectId) {
        String tid = requireTenantId(tenantId);
        String sid = requireNonBlank(subjectId, "subjectId");
        if (preferOntology(tid)) {
            return listMembershipsFromOntology(tid, sid);
        }
        return listMembershipsFromLegacyForSubject(tid, sid);
    }

    /**
     * Resolve {@link OrgScope#MODE_SELF_AND_DESCENDANTS} for a subject in a tenant.
     * Roots = ACTIVE membership unit / organization ids; expanded via parent links (legacy) or
     * ACTIVE CONTAINS (ontology when fully backfilled). Empty ACTIVE memberships → {@link OrgScope#none()}
     * (fail-closed; never silently UNRESTRICTED).
     * 解析「本部门及下级」。无 ACTIVE 成员返回 NONE（fail-closed）。
     */
    public OrgScope resolveSelfAndDescendants(String tenantId, String subjectId) {
        String tid = requireTenantId(tenantId);
        String sid = requireNonBlank(subjectId, "subjectId");
        if (preferOntology(tid)) {
            return resolveSelfAndDescendantsFromOntology(tid, sid);
        }
        List<String> roots = jdbc.query(
                """
                SELECT org_unit_id
                FROM org_membership
                WHERE tenant_id = ? AND subject_id = ? AND membership_state = ?
                ORDER BY org_unit_id
                """,
                (row, n) -> row.getString("org_unit_id"),
                tid,
                sid,
                STATE_ACTIVE);
        if (roots.isEmpty()) {
            return OrgScope.none();
        }
        Set<String> expanded = expandSelfAndDescendants(tid, roots);
        return OrgScope.selfAndDescendants(roots, expanded);
    }

    /**
     * OrgScope in <em>organization_id</em> space for O5 Organization API.
     * Maps {@link #resolveSelfAndDescendants} unit ids via {@code org_unit_organization_map}
     * (falls back to the unit id when unmapped). NONE / UNRESTRICTED pass through.
     * O5：组织 id 空间的范围；由单元范围经映射得到。
     */
    public OrgScope resolveOrganizationSelfAndDescendants(String tenantId, String subjectId) {
        OrgScope unitScope = resolveSelfAndDescendants(tenantId, subjectId);
        if (unitScope == null || unitScope.isNone() || unitScope.isUnrestricted()) {
            return unitScope == null ? OrgScope.none() : unitScope;
        }
        List<String> mappedRoots = new ArrayList<>();
        for (String root : unitScope.rootUnitIds()) {
            mappedRoots.add(backfill.findOrganizationId(tenantId, root).orElse(root));
        }
        List<String> mappedUnits = new ArrayList<>();
        for (String unit : unitScope.unitIds()) {
            mappedUnits.add(backfill.findOrganizationId(tenantId, unit).orElse(unit));
        }
        return OrgScope.selfAndDescendants(mappedRoots, mappedUnits);
    }

    /**
     * Resolve {@link OrgScope#MODE_SELF} — ACTIVE membership orgs only (no descendants).
     * Empty → {@link OrgScope#none()}.
     * 仅 ACTIVE 成员组织（不含下级）。空则 NONE。
     */
    public OrgScope resolveSelf(String tenantId, String subjectId) {
        String tid = requireTenantId(tenantId);
        String sid = requireNonBlank(subjectId, "subjectId");
        if (preferOntology(tid)) {
            List<String> roots = activeMembershipUnitIdsFromOntology(tid, sid);
            if (roots.isEmpty()) {
                return OrgScope.none();
            }
            return OrgScope.self(roots);
        }
        List<String> roots = jdbc.query(
                """
                SELECT org_unit_id
                FROM org_membership
                WHERE tenant_id = ? AND subject_id = ? AND membership_state = ?
                ORDER BY org_unit_id
                """,
                (row, n) -> row.getString("org_unit_id"),
                tid,
                sid,
                STATE_ACTIVE);
        if (roots.isEmpty()) {
            return OrgScope.none();
        }
        return OrgScope.self(roots);
    }

    /**
     * One root plus all descendants in the tenant tree (in-memory BFS; H2-safe).
     * 一个根及其全部下级（内存 BFS；兼容 H2）。
     */
    public Set<String> descendantUnitIds(String tenantId, String rootUnitId) {
        String tid = requireTenantId(tenantId);
        String root = requireNonBlank(rootUnitId, "rootUnitId");
        return expandSelfAndDescendants(tid, List.of(root));
    }

    /**
     * Expand many roots to self + descendants — 展开多个根为自身及下级。
     */
    public Set<String> expandSelfAndDescendants(String tenantId, Collection<String> rootUnitIds) {
        String tid = requireTenantId(tenantId);
        Set<String> roots = new HashSet<>();
        if (rootUnitIds != null) {
            for (String id : rootUnitIds) {
                if (id != null && !id.isBlank()) {
                    roots.add(id.trim());
                }
            }
        }
        if (roots.isEmpty()) {
            return Set.of();
        }
        if (preferOntology(tid)) {
            return expandFromOntology(tid, roots);
        }
        Map<String, List<String>> childrenByParent = childrenIndexFromUnits(listUnitsFromLegacy(tid));
        return bfsExpand(roots, childrenByParent);
    }

    /**
     * Insert or update one org unit in a tenant. Parent (when set) must exist in the same tenant.
     * Blank {@code unitState} defaults to {@link #STATE_ACTIVE}.
     * 在租户内插入或更新一个组织单元。父节点（若有）须同租户存在。空 {@code unitState} 默认为 ACTIVE。
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
        backfill.syncUnitWriteThrough(tid, saved);
        return saved;
    }

    /**
     * Soft-disable or re-activate a unit ({@link #STATE_DISABLED} / {@link #STATE_ACTIVE}).
     * 软停用或重新启用组织单元。
     */
    public OrgUnit setUnitState(String tenantId, String orgUnitId, String state) {
        String tid = requireTenantId(tenantId);
        String uid = requireNonBlank(orgUnitId, "orgUnitId");
        String st = requireNonBlank(state, "unitState");
        OrgUnit current = findUnit(tid, uid)
                .orElseThrow(() -> new IllegalArgumentException("org unit not found"));
        OrgUnit saved = new OrgUnit(tid, uid, current.parentOrgUnitId(), current.unitName(), st);
        backfill.syncUnitWriteThrough(tid, saved);
        return saved;
    }

    /**
     * Insert or update membership. Unit must exist in the tenant. Blank state → ACTIVE.
     * 插入或更新成员关系。单元须在租户内存在。空状态 → ACTIVE。
     */
    public OrgMembership upsertMembership(
            String tenantId, String subjectId, String orgUnitId, String membershipState) {
        String tid = requireTenantId(tenantId);
        String sid = requireNonBlank(subjectId, "subjectId");
        String uid = requireNonBlank(orgUnitId, "orgUnitId");
        String state = blankToDefault(membershipState, STATE_ACTIVE);
        requireUnitExists(tid, uid, "org unit not found");
        OrgMembership saved = new OrgMembership(tid, sid, uid, state);
        backfill.syncMembershipWriteThrough(tid, saved);
        return saved;
    }

    /**
     * Delete one membership; returns {@code false} when no row matched (HTTP maps to 404).
     * 删除一条成员关系；无匹配行返回 {@code false}（HTTP 映射为 404）。
     */
    public boolean removeMembership(String tenantId, String subjectId, String orgUnitId) {
        String tid = requireTenantId(tenantId);
        String sid = requireNonBlank(subjectId, "subjectId");
        String uid = requireNonBlank(orgUnitId, "orgUnitId");
        if (preferOntology(tid) || !backfill.legacyOrgTablesPresent()) {
            boolean any = listMembershipsFromOntology(tid, sid).stream()
                    .anyMatch(m -> uid.equals(m.orgUnitId()));
            if (!any) {
                return false;
            }
            backfill.endMembershipWriteThrough(tid, sid, uid);
            return true;
        }
        int deleted = jdbc.update(
                """
                DELETE FROM org_membership
                WHERE tenant_id = ? AND subject_id = ? AND org_unit_id = ?
                """,
                tid,
                sid,
                uid);
        if (deleted > 0) {
            backfill.endMembershipWriteThrough(tid, sid, uid);
        }
        return deleted > 0;
    }

    /** Whether a unit row exists in the tenant — 租户内是否已有该单元行。 */
    public boolean unitExists(String tenantId, String orgUnitId) {
        String tid = requireTenantId(tenantId);
        String uid = requireNonBlank(orgUnitId, "orgUnitId");
        return findUnit(tid, uid).isPresent();
    }

    /** Exposed for backfill/tests: always read legacy org_unit — 供回填/测例：始终读旧表。 */
    List<OrgUnit> listUnitsFromLegacy(String tenantId) {
        return jdbc.query(
                """
                SELECT tenant_id, org_unit_id, parent_org_unit_id, unit_name, unit_state
                FROM org_unit
                WHERE tenant_id = ?
                ORDER BY org_unit_id
                """,
                (row, n) -> new OrgUnit(
                        row.getString("tenant_id"),
                        row.getString("org_unit_id"),
                        row.getString("parent_org_unit_id"),
                        row.getString("unit_name"),
                        row.getString("unit_state")),
                tenantId);
    }

    List<OrgMembership> listMembershipsFromLegacy(String tenantId) {
        return jdbc.query(
                """
                SELECT tenant_id, subject_id, org_unit_id, membership_state
                FROM org_membership
                WHERE tenant_id = ?
                ORDER BY subject_id, org_unit_id
                """,
                (row, n) -> new OrgMembership(
                        row.getString("tenant_id"),
                        row.getString("subject_id"),
                        row.getString("org_unit_id"),
                        row.getString("membership_state")),
                tenantId);
    }

    private List<OrgMembership> listMembershipsFromLegacyForSubject(String tenantId, String subjectId) {
        return jdbc.query(
                """
                SELECT tenant_id, subject_id, org_unit_id, membership_state
                FROM org_membership
                WHERE tenant_id = ? AND subject_id = ?
                ORDER BY org_unit_id
                """,
                (row, n) -> new OrgMembership(
                        row.getString("tenant_id"),
                        row.getString("subject_id"),
                        row.getString("org_unit_id"),
                        row.getString("membership_state")),
                tenantId,
                subjectId);
    }

    private List<OrgUnit> listUnitsFromOntology(String tenantId) {
        return jdbc.query(
                """
                SELECT m.tenant_id AS tenant_id,
                       m.org_unit_id AS org_unit_id,
                       pm.org_unit_id AS parent_org_unit_id,
                       o.organization_name AS unit_name,
                       o.organization_state AS unit_state
                FROM org_unit_organization_map m
                INNER JOIN organization o ON o.organization_id = m.organization_id
                LEFT JOIN organization_relation r
                    ON r.to_organization_id = m.organization_id
                   AND r.relation_kind = ?
                   AND r.relation_state = ?
                LEFT JOIN org_unit_organization_map pm
                    ON pm.organization_id = r.from_organization_id
                   AND pm.tenant_id = m.tenant_id
                WHERE m.tenant_id = ?
                ORDER BY m.org_unit_id
                """,
                (row, n) -> new OrgUnit(
                        row.getString("tenant_id"),
                        row.getString("org_unit_id"),
                        row.getString("parent_org_unit_id"),
                        row.getString("unit_name"),
                        row.getString("unit_state")),
                RelationKind.CONTAINS,
                JdbcOrganizationStore.STATE_ACTIVE,
                tenantId);
    }

    private List<OrgMembership> listMembershipsFromOntology(String tenantId, String subjectIdOrNull) {
        if (subjectIdOrNull == null) {
            return jdbc.query(
                    """
                    SELECT m.tenant_id AS tenant_id,
                           mem.subject_id AS subject_id,
                           m.org_unit_id AS org_unit_id,
                           mem.membership_state AS membership_state
                    FROM membership mem
                    INNER JOIN org_unit_organization_map m
                        ON m.organization_id = mem.organization_id
                       AND m.tenant_id = ?
                    WHERE mem.membership_state <> ?
                    ORDER BY mem.subject_id, m.org_unit_id
                    """,
                    (row, n) -> new OrgMembership(
                            row.getString("tenant_id"),
                            row.getString("subject_id"),
                            row.getString("org_unit_id"),
                            row.getString("membership_state")),
                    tenantId,
                    JdbcOrganizationStore.STATE_ENDED);
        }
        return jdbc.query(
                """
                SELECT m.tenant_id AS tenant_id,
                       mem.subject_id AS subject_id,
                       m.org_unit_id AS org_unit_id,
                       mem.membership_state AS membership_state
                FROM membership mem
                INNER JOIN org_unit_organization_map m
                    ON m.organization_id = mem.organization_id
                   AND m.tenant_id = ?
                WHERE mem.subject_id = ?
                  AND mem.membership_state <> ?
                ORDER BY m.org_unit_id
                """,
                (row, n) -> new OrgMembership(
                        row.getString("tenant_id"),
                        row.getString("subject_id"),
                        row.getString("org_unit_id"),
                        row.getString("membership_state")),
                tenantId,
                subjectIdOrNull,
                JdbcOrganizationStore.STATE_ENDED);
    }

    private OrgScope resolveSelfAndDescendantsFromOntology(String tenantId, String subjectId) {
        List<String> roots = activeMembershipUnitIdsFromOntology(tenantId, subjectId);
        if (roots.isEmpty()) {
            return OrgScope.none();
        }
        Set<String> expanded = expandFromOntology(tenantId, new HashSet<>(roots));
        return OrgScope.selfAndDescendants(roots, expanded);
    }

    private List<String> activeMembershipUnitIdsFromOntology(String tenantId, String subjectId) {
        return jdbc.query(
                """
                SELECT m.org_unit_id
                FROM membership mem
                INNER JOIN org_unit_organization_map m
                    ON m.organization_id = mem.organization_id
                   AND m.tenant_id = ?
                INNER JOIN tenant_organization torg
                    ON torg.organization_id = mem.organization_id
                   AND torg.tenant_id = ?
                   AND torg.link_state = ?
                WHERE mem.subject_id = ? AND mem.membership_state = ?
                ORDER BY m.org_unit_id
                """,
                (row, n) -> row.getString("org_unit_id"),
                tenantId,
                tenantId,
                JdbcOrganizationStore.STATE_ACTIVE,
                subjectId,
                JdbcOrganizationStore.STATE_ACTIVE);
    }

    private Set<String> expandFromOntology(String tenantId, Set<String> rootUnitIds) {
        Set<String> out = new HashSet<>();
        for (String rootUnit : rootUnitIds) {
            Optional<String> rootOrg = backfill.findOrganizationId(tenantId, rootUnit);
            if (rootOrg.isEmpty()) {
                out.add(rootUnit);
                continue;
            }
            for (String orgId : organizationStore.containsSelfAndDescendants(rootOrg.get())) {
                backfill.findOrgUnitId(tenantId, orgId).ifPresentOrElse(out::add, () -> {});
            }
        }
        return Set.copyOf(out);
    }

    private static Map<String, List<String>> childrenIndexFromUnits(List<OrgUnit> units) {
        Map<String, List<String>> children = new HashMap<>();
        for (OrgUnit unit : units) {
            String parent = unit.parentOrgUnitId();
            if (parent == null || parent.isBlank()) {
                continue;
            }
            children.computeIfAbsent(parent, k -> new ArrayList<>()).add(unit.orgUnitId());
        }
        return children;
    }

    private static Set<String> bfsExpand(Set<String> roots, Map<String, List<String>> childrenByParent) {
        Set<String> out = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!out.add(current)) {
                continue;
            }
            List<String> children = childrenByParent.get(current);
            if (children != null) {
                for (String child : children) {
                    if (!out.contains(child)) {
                        queue.addLast(child);
                    }
                }
            }
        }
        return Set.copyOf(out);
    }

    private Optional<OrgUnit> findUnit(String tenantId, String orgUnitId) {
        if (preferOntology(tenantId) || !backfill.legacyOrgTablesPresent()) {
            return listUnitsFromOntology(tenantId).stream()
                    .filter(u -> orgUnitId.equals(u.orgUnitId()))
                    .findFirst();
        }
        List<OrgUnit> rows = jdbc.query(
                """
                SELECT tenant_id, org_unit_id, parent_org_unit_id, unit_name, unit_state
                FROM org_unit
                WHERE tenant_id = ? AND org_unit_id = ?
                """,
                (row, n) -> new OrgUnit(
                        row.getString("tenant_id"),
                        row.getString("org_unit_id"),
                        row.getString("parent_org_unit_id"),
                        row.getString("unit_name"),
                        row.getString("unit_state")),
                tenantId,
                orgUnitId);
        return rows.stream().findFirst();
    }

    private void requireUnitExists(String tenantId, String orgUnitId, String message) {
        if (backfill.findOrganizationId(tenantId, orgUnitId).isPresent()) {
            return;
        }
        if (backfill.legacyOrgTablesPresent()) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM org_unit WHERE tenant_id = ? AND org_unit_id = ?",
                    Integer.class,
                    tenantId,
                    orgUnitId);
            if (count != null && count > 0) {
                return;
            }
        }
        throw new IllegalArgumentException(message);
    }

    /** Prefer ontology reads when legacy is gone or tenant is fully backfilled. */
    private boolean preferOntology(String tenantId) {
        if (!backfill.legacyOrgTablesPresent()) {
            return true;
        }
        return backfill.isTenantFullyBackfilled(tenantId);
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
