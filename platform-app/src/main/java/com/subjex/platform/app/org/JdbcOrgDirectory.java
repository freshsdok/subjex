package com.subjex.platform.app.org;

import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcOrgDirectory — 组织目录只读：按租户列出组织单元与成员关系。
 * <p>
 * Pure JDBC ({@link JdbcTemplate} only); no write/mutate APIs yet. Spring-free class; bean in {@code PlatformWiring}.
 * 纯 JDBC（只用 {@link JdbcTemplate}）；尚无写接口。无 Spring 注解；Bean 在 {@code PlatformWiring}。
 */
public final class JdbcOrgDirectory {

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
        String sid = requireSubjectId(subjectId);
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

    private static String requireTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId required");
        }
        return tenantId.trim();
    }

    private static String requireSubjectId(String subjectId) {
        if (subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("subjectId required");
        }
        return subjectId.trim();
    }
}
