package com.subjex.platform.app.organization;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
 * JdbcOrganizationStore — O2 persistence for Organization ontology.
 * <p>
 * Pure JDBC. Legacy {@code org_unit} / {@code org_membership} remain untouched.
 * Integrity (existence, no self-edge, CONTAINS acyclicity) enforced in app — no SQL FKs (H2 dual-MODE).
 * 纯 JDBC。旧 org_unit / org_membership 不动。完整性在应用层约束。
 */
public final class JdbcOrganizationStore {

    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String STATE_SUSPENDED = "SUSPENDED";
    public static final String STATE_DISABLED = "DISABLED";
    public static final String STATE_ENDED = "ENDED";

    private final JdbcTemplate jdbc;

    public JdbcOrganizationStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    // --- Organization ---

    public Organization upsertOrganization(String organizationId, String organizationName, String organizationState) {
        String id = requireNonBlank(organizationId, "organizationId");
        String name = requireNonBlank(organizationName, "organizationName");
        String state = blankToDefault(organizationState, STATE_ACTIVE);
        int updated = jdbc.update(
                """
                UPDATE organization
                SET organization_name = ?, organization_state = ?
                WHERE organization_id = ?
                """,
                name,
                state,
                id);
        if (updated == 0) {
            try {
                jdbc.update(
                        """
                        INSERT INTO organization (organization_id, organization_name, organization_state)
                        VALUES (?, ?, ?)
                        """,
                        id,
                        name,
                        state);
            } catch (DuplicateKeyException raced) {
                jdbc.update(
                        """
                        UPDATE organization
                        SET organization_name = ?, organization_state = ?
                        WHERE organization_id = ?
                        """,
                        name,
                        state,
                        id);
            }
        }
        return new Organization(id, name, state);
    }

    public Optional<Organization> findOrganization(String organizationId) {
        String id = requireNonBlank(organizationId, "organizationId");
        List<Organization> rows = jdbc.query(
                """
                SELECT organization_id, organization_name, organization_state
                FROM organization
                WHERE organization_id = ?
                """,
                (row, n) -> new Organization(
                        row.getString("organization_id"),
                        row.getString("organization_name"),
                        row.getString("organization_state")),
                id);
        return rows.stream().findFirst();
    }

    public List<Organization> listOrganizations() {
        return jdbc.query(
                """
                SELECT organization_id, organization_name, organization_state
                FROM organization
                ORDER BY organization_id
                """,
                (row, n) -> new Organization(
                        row.getString("organization_id"),
                        row.getString("organization_name"),
                        row.getString("organization_state")));
    }

    public boolean organizationExists(String organizationId) {
        return findOrganization(organizationId).isPresent();
    }

    // --- Membership (no tenant) ---

    public Membership upsertMembership(String subjectId, String organizationId, String membershipState) {
        String sid = requireNonBlank(subjectId, "subjectId");
        String oid = requireNonBlank(organizationId, "organizationId");
        String state = blankToDefault(membershipState, STATE_ACTIVE);
        requireSubjectExists(sid);
        requireOrganizationExists(oid, "organization not found");
        int updated = jdbc.update(
                """
                UPDATE membership
                SET membership_state = ?
                WHERE subject_id = ? AND organization_id = ?
                """,
                state,
                sid,
                oid);
        if (updated == 0) {
            try {
                jdbc.update(
                        """
                        INSERT INTO membership (subject_id, organization_id, membership_state)
                        VALUES (?, ?, ?)
                        """,
                        sid,
                        oid,
                        state);
            } catch (DuplicateKeyException raced) {
                jdbc.update(
                        """
                        UPDATE membership
                        SET membership_state = ?
                        WHERE subject_id = ? AND organization_id = ?
                        """,
                        state,
                        sid,
                        oid);
            }
        }
        return new Membership(sid, oid, state);
    }

    public List<Membership> listMembershipsForSubject(String subjectId) {
        String sid = requireNonBlank(subjectId, "subjectId");
        return jdbc.query(
                """
                SELECT subject_id, organization_id, membership_state
                FROM membership
                WHERE subject_id = ?
                ORDER BY organization_id
                """,
                (row, n) -> new Membership(
                        row.getString("subject_id"),
                        row.getString("organization_id"),
                        row.getString("membership_state")),
                sid);
    }

    public List<Membership> listMembershipsForOrganization(String organizationId) {
        String oid = requireNonBlank(organizationId, "organizationId");
        return jdbc.query(
                """
                SELECT subject_id, organization_id, membership_state
                FROM membership
                WHERE organization_id = ?
                ORDER BY subject_id
                """,
                (row, n) -> new Membership(
                        row.getString("subject_id"),
                        row.getString("organization_id"),
                        row.getString("membership_state")),
                oid);
    }

    // --- OrganizationRelation ---

    /**
     * Upsert a relation. CONTAINS rejects self-edges and cycles among ACTIVE edges.
     * 写入关系。CONTAINS 拒绝自环与 ACTIVE 边成环。
     */
    public OrganizationRelation upsertRelation(
            String fromOrganizationId, String toOrganizationId, String relationKind, String relationState) {
        String from = requireNonBlank(fromOrganizationId, "fromOrganizationId");
        String to = requireNonBlank(toOrganizationId, "toOrganizationId");
        String kind = requireNonBlank(relationKind, "relationKind");
        String state = blankToDefault(relationState, STATE_ACTIVE);
        requireOrganizationExists(from, "from organization not found");
        requireOrganizationExists(to, "to organization not found");
        if (from.equals(to)) {
            throw new IllegalArgumentException("organization relation cannot self-reference");
        }
        if (RelationKind.CONTAINS.equals(kind) && STATE_ACTIVE.equals(state)) {
            if (wouldCreateContainsCycle(from, to)) {
                throw new IllegalArgumentException("CONTAINS relation would form a cycle");
            }
        }
        int updated = jdbc.update(
                """
                UPDATE organization_relation
                SET relation_state = ?
                WHERE from_organization_id = ? AND to_organization_id = ? AND relation_kind = ?
                """,
                state,
                from,
                to,
                kind);
        if (updated == 0) {
            try {
                jdbc.update(
                        """
                        INSERT INTO organization_relation
                            (from_organization_id, to_organization_id, relation_kind, relation_state)
                        VALUES (?, ?, ?, ?)
                        """,
                        from,
                        to,
                        kind,
                        state);
            } catch (DuplicateKeyException raced) {
                jdbc.update(
                        """
                        UPDATE organization_relation
                        SET relation_state = ?
                        WHERE from_organization_id = ? AND to_organization_id = ? AND relation_kind = ?
                        """,
                        state,
                        from,
                        to,
                        kind);
            }
        }
        return new OrganizationRelation(from, to, kind, state);
    }

    public List<OrganizationRelation> listRelations() {
        return jdbc.query(
                """
                SELECT from_organization_id, to_organization_id, relation_kind, relation_state
                FROM organization_relation
                ORDER BY from_organization_id, to_organization_id, relation_kind
                """,
                (row, n) -> new OrganizationRelation(
                        row.getString("from_organization_id"),
                        row.getString("to_organization_id"),
                        row.getString("relation_kind"),
                        row.getString("relation_state")));
    }

    /**
     * ACTIVE CONTAINS descendants of {@code root} (self included). Inactive edges excluded.
     * ACTIVE CONTAINS 后代（含自身）。不计 ENDED。
     */
    public Set<String> containsSelfAndDescendants(String rootOrganizationId) {
        String root = requireNonBlank(rootOrganizationId, "rootOrganizationId");
        Map<String, List<String>> children = activeContainsChildrenIndex();
        Set<String> out = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!out.add(current)) {
                continue;
            }
            List<String> kids = children.get(current);
            if (kids != null) {
                for (String child : kids) {
                    if (!out.contains(child)) {
                        queue.addLast(child);
                    }
                }
            }
        }
        return Set.copyOf(out);
    }

    /**
     * True if adding ACTIVE CONTAINS(from → to) would create a cycle
     * (i.e. {@code from} is already reachable from {@code to} via ACTIVE CONTAINS).
     * 若增加 ACTIVE CONTAINS(from→to) 会成环则为 true（from 已可从 to 经 ACTIVE CONTAINS 到达）。
     */
    public boolean wouldCreateContainsCycle(String fromOrganizationId, String toOrganizationId) {
        String from = requireNonBlank(fromOrganizationId, "fromOrganizationId");
        String to = requireNonBlank(toOrganizationId, "toOrganizationId");
        return containsSelfAndDescendants(to).contains(from);
    }

    // --- TenantOrganization ---

    public TenantOrganization upsertTenantOrganization(String tenantId, String organizationId, String linkState) {
        String tid = requireNonBlank(tenantId, "tenantId");
        String oid = requireNonBlank(organizationId, "organizationId");
        String state = blankToDefault(linkState, STATE_ACTIVE);
        requireTenantExists(tid);
        requireOrganizationExists(oid, "organization not found");
        int updated = jdbc.update(
                """
                UPDATE tenant_organization
                SET link_state = ?
                WHERE tenant_id = ? AND organization_id = ?
                """,
                state,
                tid,
                oid);
        if (updated == 0) {
            try {
                jdbc.update(
                        """
                        INSERT INTO tenant_organization (tenant_id, organization_id, link_state)
                        VALUES (?, ?, ?)
                        """,
                        tid,
                        oid,
                        state);
            } catch (DuplicateKeyException raced) {
                jdbc.update(
                        """
                        UPDATE tenant_organization
                        SET link_state = ?
                        WHERE tenant_id = ? AND organization_id = ?
                        """,
                        state,
                        tid,
                        oid);
            }
        }
        return new TenantOrganization(tid, oid, state);
    }

    public List<TenantOrganization> listTenantOrganizationsForTenant(String tenantId) {
        String tid = requireNonBlank(tenantId, "tenantId");
        return jdbc.query(
                """
                SELECT tenant_id, organization_id, link_state
                FROM tenant_organization
                WHERE tenant_id = ?
                ORDER BY organization_id
                """,
                (row, n) -> new TenantOrganization(
                        row.getString("tenant_id"),
                        row.getString("organization_id"),
                        row.getString("link_state")),
                tid);
    }

    public List<TenantOrganization> listTenantOrganizationsForOrganization(String organizationId) {
        String oid = requireNonBlank(organizationId, "organizationId");
        return jdbc.query(
                """
                SELECT tenant_id, organization_id, link_state
                FROM tenant_organization
                WHERE organization_id = ?
                ORDER BY tenant_id
                """,
                (row, n) -> new TenantOrganization(
                        row.getString("tenant_id"),
                        row.getString("organization_id"),
                        row.getString("link_state")),
                oid);
    }

    /**
     * Organizations linked to a tenant (ACTIVE TenantOrganization), ordered by id.
     * 租户关联组织（ACTIVE），按 id 排序。
     */
    public List<Organization> listOrganizationsLinkedToTenant(String tenantId) {
        String tid = requireNonBlank(tenantId, "tenantId");
        return jdbc.query(
                """
                SELECT o.organization_id, o.organization_name, o.organization_state
                FROM organization o
                INNER JOIN tenant_organization t
                    ON t.organization_id = o.organization_id
                   AND t.tenant_id = ?
                   AND t.link_state = ?
                ORDER BY o.organization_id
                """,
                (row, n) -> new Organization(
                        row.getString("organization_id"),
                        row.getString("organization_name"),
                        row.getString("organization_state")),
                tid,
                STATE_ACTIVE);
    }

    /**
     * ACTIVE CONTAINS parent of child, if any (first by id when multiple — should be at most one).
     * 子组织的 ACTIVE CONTAINS 父（若有）。
     */
    public Optional<String> findActiveContainsParent(String childOrganizationId) {
        String child = requireNonBlank(childOrganizationId, "organizationId");
        List<String> rows = jdbc.query(
                """
                SELECT from_organization_id
                FROM organization_relation
                WHERE to_organization_id = ? AND relation_kind = ? AND relation_state = ?
                ORDER BY from_organization_id
                """,
                (row, n) -> row.getString("from_organization_id"),
                child,
                RelationKind.CONTAINS,
                STATE_ACTIVE);
        return rows.stream().findFirst();
    }

    /**
     * Memberships for orgs linked to tenant (excludes ENDED). Optional subject filter.
     * 租户关联组织上的成员（排除 ENDED）；可按主体过滤。
     */
    public List<Membership> listMembershipsForTenant(String tenantId, String subjectIdOrNull) {
        String tid = requireNonBlank(tenantId, "tenantId");
        if (subjectIdOrNull == null || subjectIdOrNull.isBlank()) {
            return jdbc.query(
                    """
                    SELECT mem.subject_id, mem.organization_id, mem.membership_state
                    FROM membership mem
                    INNER JOIN tenant_organization t
                        ON t.organization_id = mem.organization_id
                       AND t.tenant_id = ?
                       AND t.link_state = ?
                    WHERE mem.membership_state <> ?
                    ORDER BY mem.subject_id, mem.organization_id
                    """,
                    (row, n) -> new Membership(
                            row.getString("subject_id"),
                            row.getString("organization_id"),
                            row.getString("membership_state")),
                    tid,
                    STATE_ACTIVE,
                    STATE_ENDED);
        }
        String sid = subjectIdOrNull.trim();
        return jdbc.query(
                """
                SELECT mem.subject_id, mem.organization_id, mem.membership_state
                FROM membership mem
                INNER JOIN tenant_organization t
                    ON t.organization_id = mem.organization_id
                   AND t.tenant_id = ?
                   AND t.link_state = ?
                WHERE mem.subject_id = ?
                  AND mem.membership_state <> ?
                ORDER BY mem.organization_id
                """,
                (row, n) -> new Membership(
                        row.getString("subject_id"),
                        row.getString("organization_id"),
                        row.getString("membership_state")),
                tid,
                STATE_ACTIVE,
                sid,
                STATE_ENDED);
    }

    /**
     * Mark membership ENDED; returns false when no non-ENDED row existed.
     * 将成员标为 ENDED；无行返回 false。
     */
    public boolean endMembership(String subjectId, String organizationId) {
        String sid = requireNonBlank(subjectId, "subjectId");
        String oid = requireNonBlank(organizationId, "organizationId");
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM membership
                WHERE subject_id = ? AND organization_id = ? AND membership_state <> ?
                """,
                Integer.class,
                sid,
                oid,
                STATE_ENDED);
        if (count == null || count == 0) {
            return false;
        }
        upsertMembership(sid, oid, STATE_ENDED);
        return true;
    }

    /**
     * Set ACTIVE CONTAINS parent for child (ends other ACTIVE CONTAINS parents).
     * Blank parent ends all ACTIVE CONTAINS edges into child.
     * 设置子组织的 ACTIVE CONTAINS 父；空父则结束所有指向子的 ACTIVE CONTAINS。
     */
    public void setContainsParent(String childOrganizationId, String parentOrganizationIdOrNull) {
        String child = requireNonBlank(childOrganizationId, "organizationId");
        String parent =
                parentOrganizationIdOrNull == null || parentOrganizationIdOrNull.isBlank()
                        ? null
                        : parentOrganizationIdOrNull.trim();
        if (parent != null) {
            requireOrganizationExists(parent, "parent organization not found");
            if (parent.equals(child)) {
                throw new IllegalArgumentException("organization relation cannot self-reference");
            }
        }
        if (parent == null) {
            jdbc.update(
                    """
                    UPDATE organization_relation
                    SET relation_state = ?
                    WHERE to_organization_id = ? AND relation_kind = ? AND relation_state = ?
                    """,
                    STATE_ENDED,
                    child,
                    RelationKind.CONTAINS,
                    STATE_ACTIVE);
            return;
        }
        jdbc.update(
                """
                UPDATE organization_relation
                SET relation_state = ?
                WHERE to_organization_id = ?
                  AND relation_kind = ?
                  AND relation_state = ?
                  AND from_organization_id <> ?
                """,
                STATE_ENDED,
                child,
                RelationKind.CONTAINS,
                STATE_ACTIVE,
                parent);
        upsertRelation(parent, child, RelationKind.CONTAINS, STATE_ACTIVE);
    }

    // --- helpers ---

    private Map<String, List<String>> activeContainsChildrenIndex() {
        List<OrganizationRelation> edges = jdbc.query(
                """
                SELECT from_organization_id, to_organization_id, relation_kind, relation_state
                FROM organization_relation
                WHERE relation_kind = ? AND relation_state = ?
                """,
                (row, n) -> new OrganizationRelation(
                        row.getString("from_organization_id"),
                        row.getString("to_organization_id"),
                        row.getString("relation_kind"),
                        row.getString("relation_state")),
                RelationKind.CONTAINS,
                STATE_ACTIVE);
        Map<String, List<String>> children = new HashMap<>();
        for (OrganizationRelation edge : edges) {
            children.computeIfAbsent(edge.fromOrganizationId(), k -> new ArrayList<>()).add(edge.toOrganizationId());
        }
        return children;
    }

    private void requireSubjectExists(String subjectId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM subject WHERE subject_id = ?", Integer.class, subjectId);
        if (count == null || count == 0) {
            throw new IllegalArgumentException("subject not found");
        }
    }

    private void requireOrganizationExists(String organizationId, String message) {
        if (!organizationExists(organizationId)) {
            throw new IllegalArgumentException(message);
        }
    }

    private void requireTenantExists(String tenantId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Integer.class, tenantId);
        if (count == null || count == 0) {
            throw new IllegalArgumentException("tenant not found");
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " required");
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
