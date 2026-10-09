package com.subjex.platform.app.org;

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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcOrgDirectory — 组织目录：按租户列出并写入组织单元与成员关系。
 * <p>
 * Pure JDBC ({@link JdbcTemplate} only). Writes need {@code org.write} at the HTTP layer.
 * Resolves org scope (self + descendants) for explainable access decisions (AX-2).
 * Spring-free class; bean in {@code PlatformWiring}.
 * 纯 JDBC（只用 {@link JdbcTemplate}）。写操作由 HTTP 层核对 {@code org.write}。
 * 解析组织范围（本部门及下级）供可解释判定（AX-2）。
 * 无 Spring 注解；Bean 在 {@code PlatformWiring}。
 */
public final class JdbcOrgDirectory {

    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String STATE_DISABLED = "DISABLED";

    private final JdbcTemplate jdbc;

    public JdbcOrgDirectory(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Units in one tenant, ordered by org_unit_id — 某租户内组织单元，按 org_unit_id 排序。 */
    public List<OrgUnit> listUnits(String tenantId) {
        String id = requireTenantId(tenantId);
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
                id);
    }

    /** Memberships in one tenant, ordered by subject then unit — 某租户内成员关系，按主体再单元排序。 */
    public List<OrgMembership> listMemberships(String tenantId) {
        String id = requireTenantId(tenantId);
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
                id);
    }

    /** Memberships for one subject in a tenant — 某租户内某一主体的成员关系。 */
    public List<OrgMembership> listMembershipsForSubject(String tenantId, String subjectId) {
        String tid = requireTenantId(tenantId);
        String sid = requireNonBlank(subjectId, "subjectId");
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
                tid,
                sid);
    }

    /**
     * Resolve {@link OrgScope#MODE_SELF_AND_DESCENDANTS} for a subject in a tenant.
     * Roots = ACTIVE membership unit ids; expanded = roots + all descendants via parent links.
     * Returns {@code null} when the subject has no ACTIVE memberships (unspecified / no filter).
     * 解析主体在租户内的「本部门及下级」范围。无 ACTIVE 成员则返回 null（未指定/不过滤）。
     */
    public OrgScope resolveSelfAndDescendants(String tenantId, String subjectId) {
        String tid = requireTenantId(tenantId);
        String sid = requireNonBlank(subjectId, "subjectId");
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
            return null;
        }
        Set<String> expanded = expandSelfAndDescendants(tid, roots);
        return OrgScope.selfAndDescendants(roots, expanded);
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
        Map<String, List<String>> childrenByParent = childrenIndex(tid);
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

    private Map<String, List<String>> childrenIndex(String tenantId) {
        List<OrgUnit> units = listUnits(tenantId);
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
        int updated = jdbc.update(
                """
                UPDATE org_unit
                SET parent_org_unit_id = ?, unit_name = ?, unit_state = ?
                WHERE tenant_id = ? AND org_unit_id = ?
                """,
                parent,
                name,
                state,
                tid,
                uid);
        if (updated == 0) {
            try {
                jdbc.update(
                        """
                        INSERT INTO org_unit (tenant_id, org_unit_id, parent_org_unit_id, unit_name, unit_state)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        tid,
                        uid,
                        parent,
                        name,
                        state);
            } catch (DuplicateKeyException raced) {
                jdbc.update(
                        """
                        UPDATE org_unit
                        SET parent_org_unit_id = ?, unit_name = ?, unit_state = ?
                        WHERE tenant_id = ? AND org_unit_id = ?
                        """,
                        parent,
                        name,
                        state,
                        tid,
                        uid);
            }
        }
        return new OrgUnit(tid, uid, parent, name, state);
    }

    /**
     * Soft-disable or re-activate a unit ({@link #STATE_DISABLED} / {@link #STATE_ACTIVE}).
     * 软停用或重新启用组织单元。
     */
    public OrgUnit setUnitState(String tenantId, String orgUnitId, String state) {
        String tid = requireTenantId(tenantId);
        String uid = requireNonBlank(orgUnitId, "orgUnitId");
        String st = requireNonBlank(state, "unitState");
        int updated = jdbc.update(
                "UPDATE org_unit SET unit_state = ? WHERE tenant_id = ? AND org_unit_id = ?",
                st,
                tid,
                uid);
        if (updated == 0) {
            throw new IllegalArgumentException("org unit not found");
        }
        return findUnit(tid, uid)
                .orElseThrow(() -> new IllegalArgumentException("org unit not found"));
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
        int updated = jdbc.update(
                """
                UPDATE org_membership
                SET membership_state = ?
                WHERE tenant_id = ? AND subject_id = ? AND org_unit_id = ?
                """,
                state,
                tid,
                sid,
                uid);
        if (updated == 0) {
            try {
                jdbc.update(
                        """
                        INSERT INTO org_membership (tenant_id, subject_id, org_unit_id, membership_state)
                        VALUES (?, ?, ?, ?)
                        """,
                        tid,
                        sid,
                        uid,
                        state);
            } catch (DuplicateKeyException raced) {
                jdbc.update(
                        """
                        UPDATE org_membership
                        SET membership_state = ?
                        WHERE tenant_id = ? AND subject_id = ? AND org_unit_id = ?
                        """,
                        state,
                        tid,
                        sid,
                        uid);
            }
        }
        return new OrgMembership(tid, sid, uid, state);
    }

    /**
     * Delete one membership; returns {@code false} when no row matched (HTTP maps to 404).
     * 删除一条成员关系；无匹配行返回 {@code false}（HTTP 映射为 404）。
     */
    public boolean removeMembership(String tenantId, String subjectId, String orgUnitId) {
        String tid = requireTenantId(tenantId);
        String sid = requireNonBlank(subjectId, "subjectId");
        String uid = requireNonBlank(orgUnitId, "orgUnitId");
        int deleted = jdbc.update(
                """
                DELETE FROM org_membership
                WHERE tenant_id = ? AND subject_id = ? AND org_unit_id = ?
                """,
                tid,
                sid,
                uid);
        return deleted > 0;
    }

    /** Whether a unit row exists in the tenant — 租户内是否已有该单元行。 */
    public boolean unitExists(String tenantId, String orgUnitId) {
        String tid = requireTenantId(tenantId);
        String uid = requireNonBlank(orgUnitId, "orgUnitId");
        return findUnit(tid, uid).isPresent();
    }

    private Optional<OrgUnit> findUnit(String tenantId, String orgUnitId) {
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
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM org_unit WHERE tenant_id = ? AND org_unit_id = ?",
                Integer.class,
                tenantId,
                orgUnitId);
        if (count == null || count == 0) {
            throw new IllegalArgumentException(message);
        }
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
