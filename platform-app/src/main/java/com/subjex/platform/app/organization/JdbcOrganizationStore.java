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
 * Pure JDBC against ontology tables only. After O8 FULL PASS, legacy {@code org_unit} /
 * {@code org_membership} are <strong>dropped</strong> (V24); this store does not read or write them.
 * Integrity (existence, no self-edge, CONTAINS acyclicity) is enforced in-app — no SQL FKs (H2 dual-MODE).
 * 纯 JDBC，只写组织本体表。O8 全量通过后旧 {@code org_unit}/{@code org_membership} 已 DROP（V24）；本存储不碰旧表。
 * 完整性在应用层约束（无 SQL 外键，兼容 H2 双模式）。
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

    /** Insert or update an Organization row — 插入或更新组织行。 */
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

    /** Find by id — 按 id 查找组织。 */
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

    /** List all organizations — 列出全部组织。 */
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

    /** Existence check used by API write gates — API 写门禁用的存在性检查。 */
    public boolean organizationExists(String organizationId) {
        return findOrganization(organizationId).isPresent();
    }

    // --- Membership (no tenant) ---

    /** Upsert Membership (no tenant on row) — 写入成员关系（行上无租户）。 */
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

    /** Memberships for one subject — 某主体的成员关系。 */
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

    /** Memberships for one organization — 某组织的成员关系。 */
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
        if (!RelationKind.isKnown(kind)) {
            throw new IllegalArgumentException("unknown relation_kind: " + kind);
        }
        if (RelationKind.CONTAINS.equals(kind) && STATE_ACTIVE.equals(state)) {
            if (wouldCreateContainsCycle(from, to)) {
                throw new IllegalArgumentException("CONTAINS relation would form a cycle");
            }
        }
        // MATRIX_OF: no CONTAINS-style acyclicity; multi-parent matrix allowed.
        // MATRIX_OF：不做 CONTAINS 式无环校验；允许多父矩阵边。
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
                            (from_organization_id, to_organization_id, relation_kind, relation_state, sort_order)
                        VALUES (?, ?, ?, ?, 0)
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

    /** List all organization relations — 列出全部组织关系。 */
    public List<OrganizationRelation> listRelations() {
        return jdbc.query(
                """
                SELECT from_organization_id, to_organization_id, relation_kind, relation_state, sort_order
                FROM organization_relation
                ORDER BY from_organization_id, sort_order, to_organization_id, relation_kind
                """,
                (row, n) -> new OrganizationRelation(
                        row.getString("from_organization_id"),
                        row.getString("to_organization_id"),
                        row.getString("relation_kind"),
                        row.getString("relation_state"),
                        row.getInt("sort_order")));
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

    /** Link tenant <-> organization — 挂接租户与组织。 */
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

    /** TenantOrganization rows for a tenant — 某租户的 TenantOrganization 行。 */
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

    /** TenantOrganization rows for an organization — 某组织的 TenantOrganization 行。 */
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
     * Count non-ENDED memberships on one organization (direct members).
     * 统计某组织上非 ENDED 的直接成员数。
     */
    public int countDirectMemberships(String organizationId) {
        String oid = requireNonBlank(organizationId, "organizationId");
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM membership
                WHERE organization_id = ? AND membership_state <> ?
                """,
                Integer.class,
                oid,
                STATE_ENDED);
        return count == null ? 0 : count;
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


    /**
     * ACTIVE CONTAINS sort_order for child under its parent (0 if none).
     * 子组织在 ACTIVE CONTAINS 下的 sort_order（无边则为 0）。
     */
    public int findActiveContainsSortOrder(String childOrganizationId) {
        String child = requireNonBlank(childOrganizationId, "organizationId");
        List<Integer> rows = jdbc.query(
                """
                SELECT sort_order
                FROM organization_relation
                WHERE to_organization_id = ? AND relation_kind = ? AND relation_state = ?
                ORDER BY from_organization_id
                """,
                (row, n) -> row.getInt("sort_order"),
                child,
                RelationKind.CONTAINS,
                STATE_ACTIVE);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    /**
     * Reorder ACTIVE CONTAINS children under parent (Should S2).
     * orderedChildIds must be exactly the current ACTIVE children set.
     * 重排某父下 ACTIVE CONTAINS 子节点顺序。
     */
    public void reorderContainsChildren(String parentOrganizationId, List<String> orderedChildIds) {
        String parent = requireNonBlank(parentOrganizationId, "parentOrganizationId");
        if (orderedChildIds == null || orderedChildIds.isEmpty()) {
            throw new IllegalArgumentException("orderedChildIds required");
        }
        List<String> current = jdbc.query(
                """
                SELECT to_organization_id
                FROM organization_relation
                WHERE from_organization_id = ? AND relation_kind = ? AND relation_state = ?
                ORDER BY sort_order, to_organization_id
                """,
                (row, n) -> row.getString("to_organization_id"),
                parent,
                RelationKind.CONTAINS,
                STATE_ACTIVE);
        if (current.size() != orderedChildIds.size()
                || !Set.copyOf(current).equals(Set.copyOf(orderedChildIds))) {
            throw new IllegalArgumentException("orderedChildIds must match current ACTIVE children");
        }
        int i = 0;
        for (String child : orderedChildIds) {
            String cid = requireNonBlank(child, "childOrganizationId");
            jdbc.update(
                    """
                    UPDATE organization_relation
                    SET sort_order = ?
                    WHERE from_organization_id = ?
                      AND to_organization_id = ?
                      AND relation_kind = ?
                      AND relation_state = ?
                    """,
                    i++,
                    parent,
                    cid,
                    RelationKind.CONTAINS,
                    STATE_ACTIVE);
        }
    }


    /**
     * Direct ACTIVE CONTAINS children of parent (ordered by sort_order).
     * 某父下 ACTIVE CONTAINS 直接子节点（按 sort_order）。
     */
    public List<String> listActiveContainsChildren(String parentOrganizationId) {
        String parent = requireNonBlank(parentOrganizationId, "parentOrganizationId");
        return jdbc.query(
                """
                SELECT to_organization_id
                FROM organization_relation
                WHERE from_organization_id = ? AND relation_kind = ? AND relation_state = ?
                ORDER BY sort_order, to_organization_id
                """,
                (row, n) -> row.getString("to_organization_id"),
                parent,
                RelationKind.CONTAINS,
                STATE_ACTIVE);
    }

    /**
     * Merge source into target (Should S5): rehang memberships, reparent children, end source.
     * Rejects when target is source or inside source subtree.
     * 合并：成员重挂、子组织改挂、源组织 ENDED。目标不可为源或其子树。
     */
    public MergeResult mergeOrganizations(String sourceOrganizationId, String targetOrganizationId) {
        String source = requireNonBlank(sourceOrganizationId, "sourceOrganizationId");
        String target = requireNonBlank(targetOrganizationId, "targetOrganizationId");
        requireOrganizationExists(source, "source organization not found");
        requireOrganizationExists(target, "target organization not found");
        if (source.equals(target)) {
            throw new IllegalArgumentException("cannot merge organization into itself");
        }
        if (containsSelfAndDescendants(source).contains(target)) {
            throw new IllegalArgumentException("cannot merge into a descendant (would cycle)");
        }
        List<Membership> sourceMembers = listMembershipsForOrganization(source).stream()
                .filter(m -> !STATE_ENDED.equals(m.membershipState()))
                .toList();
        int movedMembers = 0;
        for (Membership m : sourceMembers) {
            upsertMembership(m.subjectId(), target, STATE_ACTIVE);
            endMembership(m.subjectId(), source);
            movedMembers++;
        }
        List<String> children = listActiveContainsChildren(source);
        int reparented = 0;
        for (String child : children) {
            setContainsParent(child, target);
            reparented++;
        }
        setContainsParent(source, null);
        Organization ended = upsertOrganization(
                source,
                findOrganization(source).map(Organization::organizationName).orElse(source),
                STATE_ENDED);
        return new MergeResult(ended, target, movedMembers, reparented);
    }

    /**
     * Split subset of children/members into a new (or existing) organization (Should S5).
     * Children must be direct ACTIVE CONTAINS children of source; members must be on source.
     * 拆分：将选中的直接子组织与成员迁入新组织。
     */
    public SplitResult splitOrganization(
            String sourceOrganizationId,
            String newOrganizationId,
            String newOrganizationName,
            String newParentOrganizationIdOrNull,
            List<String> childOrganizationIds,
            List<String> memberSubjectIds) {
        String source = requireNonBlank(sourceOrganizationId, "sourceOrganizationId");
        String nid = requireNonBlank(newOrganizationId, "newOrganizationId");
        String nname = requireNonBlank(newOrganizationName, "newOrganizationName");
        requireOrganizationExists(source, "source organization not found");
        if (source.equals(nid)) {
            throw new IllegalArgumentException("new organization cannot be the source");
        }
        Set<String> directKids = new HashSet<>(listActiveContainsChildren(source));
        List<String> children = childOrganizationIds == null ? List.of() : childOrganizationIds;
        for (String child : children) {
            String cid = requireNonBlank(child, "childOrganizationId");
            if (!directKids.contains(cid)) {
                throw new IllegalArgumentException("child must be a direct ACTIVE child of source: " + cid);
            }
            if (cid.equals(nid)) {
                throw new IllegalArgumentException("cannot move new organization as its own child");
            }
        }
        Set<String> sourceMemberIds = new HashSet<>();
        for (Membership m : listMembershipsForOrganization(source)) {
            if (!STATE_ENDED.equals(m.membershipState())) {
                sourceMemberIds.add(m.subjectId());
            }
        }
        List<String> members = memberSubjectIds == null ? List.of() : memberSubjectIds;
        for (String sid : members) {
            String subject = requireNonBlank(sid, "memberSubjectId");
            if (!sourceMemberIds.contains(subject)) {
                throw new IllegalArgumentException("member must be an ACTIVE member of source: " + subject);
            }
        }
        String newParent =
                newParentOrganizationIdOrNull == null || newParentOrganizationIdOrNull.isBlank()
                        ? null
                        : newParentOrganizationIdOrNull.trim();
        if (newParent != null) {
            requireOrganizationExists(newParent, "new parent organization not found");
            if (newParent.equals(nid)) {
                throw new IllegalArgumentException("organization relation cannot self-reference");
            }
            // New org must not land under a child being moved into it.
            for (String child : children) {
                if (containsSelfAndDescendants(child).contains(newParent) || child.equals(newParent)) {
                    throw new IllegalArgumentException("new parent cannot be under a moved child");
                }
            }
        }
        Organization created = upsertOrganization(nid, nname, STATE_ACTIVE);
        setContainsParent(nid, newParent);
        int movedChildren = 0;
        for (String child : children) {
            setContainsParent(child.trim(), nid);
            movedChildren++;
        }
        int movedMembers = 0;
        for (String sid : members) {
            String subject = sid.trim();
            upsertMembership(subject, nid, STATE_ACTIVE);
            endMembership(subject, source);
            movedMembers++;
        }
        return new SplitResult(created, source, movedChildren, movedMembers);
    }

    /**
     * Active primary manager subject for organization, if any (Should S6).
     * 组织当前主管主体（若有）。
     */
    public Optional<String> findActiveManagerSubjectId(String organizationId) {
        String oid = requireNonBlank(organizationId, "organizationId");
        List<String> rows = jdbc.query(
                """
                SELECT manager_subject_id
                FROM organization_manager
                WHERE organization_id = ? AND link_state = ?
                """,
                (row, n) -> row.getString("manager_subject_id"),
                oid,
                STATE_ACTIVE);
        return rows.stream().findFirst();
    }

    /**
     * Assign primary manager; ensures ACTIVE Membership so OrgScope can include them.
     * 指定主管；确保 ACTIVE Membership 以便纳入 OrgScope。
     */
    public void setOrganizationManager(String organizationId, String managerSubjectId) {
        String oid = requireNonBlank(organizationId, "organizationId");
        String sid = requireNonBlank(managerSubjectId, "managerSubjectId");
        requireOrganizationExists(oid, "organization not found");
        requireSubjectExists(sid);
        int updated = jdbc.update(
                """
                UPDATE organization_manager
                SET manager_subject_id = ?, link_state = ?
                WHERE organization_id = ?
                """,
                sid,
                STATE_ACTIVE,
                oid);
        if (updated == 0) {
            try {
                jdbc.update(
                        """
                        INSERT INTO organization_manager (organization_id, manager_subject_id, link_state)
                        VALUES (?, ?, ?)
                        """,
                        oid,
                        sid,
                        STATE_ACTIVE);
            } catch (DuplicateKeyException raced) {
                jdbc.update(
                        """
                        UPDATE organization_manager
                        SET manager_subject_id = ?, link_state = ?
                        WHERE organization_id = ?
                        """,
                        sid,
                        STATE_ACTIVE,
                        oid);
            }
        }
        upsertMembership(sid, oid, STATE_ACTIVE);
    }

    /** Clear primary manager (mark ENDED). Does not remove Membership. */
    public boolean clearOrganizationManager(String organizationId) {
        String oid = requireNonBlank(organizationId, "organizationId");
        int updated = jdbc.update(
                """
                UPDATE organization_manager
                SET link_state = ?
                WHERE organization_id = ? AND link_state = ?
                """,
                STATE_ENDED,
                oid,
                STATE_ACTIVE);
        return updated > 0;
    }

    /** Merge outcome — 合并结果。 */
    public record MergeResult(
            Organization endedSource, String targetOrganizationId, int movedMembershipCount, int reparentedChildCount) {}

    /** Split outcome — 拆分结果。 */
    public record SplitResult(
            Organization createdOrganization, String sourceOrganizationId, int movedChildCount, int movedMembershipCount) {}


    /**
     * ACTIVE MATRIX_OF edges among the given organization ids (Later L1).
     * 给定组织集合上的 ACTIVE MATRIX_OF 边。
     */
    public List<OrganizationRelation> listActiveMatrixRelations(java.util.Collection<String> organizationIds) {
        if (organizationIds == null || organizationIds.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new HashSet<>();
        for (String id : organizationIds) {
            if (id != null && !id.isBlank()) {
                ids.add(id.trim());
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        List<OrganizationRelation> all = jdbc.query(
                """
                SELECT from_organization_id, to_organization_id, relation_kind, relation_state, sort_order
                FROM organization_relation
                WHERE relation_kind = ? AND relation_state = ?
                ORDER BY from_organization_id, to_organization_id
                """,
                (row, n) -> new OrganizationRelation(
                        row.getString("from_organization_id"),
                        row.getString("to_organization_id"),
                        row.getString("relation_kind"),
                        row.getString("relation_state"),
                        row.getInt("sort_order")),
                RelationKind.MATRIX_OF,
                STATE_ACTIVE);
        List<OrganizationRelation> out = new ArrayList<>();
        for (OrganizationRelation edge : all) {
            if (ids.contains(edge.fromOrganizationId()) && ids.contains(edge.toOrganizationId())) {
                out.add(edge);
            }
        }
        return out;
    }

    /**
     * Upsert ACTIVE MATRIX_OF (from = matrix supervisor, to = supervised).
     * 写入 ACTIVE MATRIX_OF。
     */
    public OrganizationRelation upsertMatrixOf(String fromOrganizationId, String toOrganizationId) {
        return upsertRelation(fromOrganizationId, toOrganizationId, RelationKind.MATRIX_OF, STATE_ACTIVE);
    }

    /**
     * End ACTIVE MATRIX_OF edge; returns false if none was ACTIVE.
     * 结束 ACTIVE MATRIX_OF；无则 false。
     */
    public boolean endMatrixOf(String fromOrganizationId, String toOrganizationId) {
        String from = requireNonBlank(fromOrganizationId, "fromOrganizationId");
        String to = requireNonBlank(toOrganizationId, "toOrganizationId");
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM organization_relation
                WHERE from_organization_id = ? AND to_organization_id = ?
                  AND relation_kind = ? AND relation_state = ?
                """,
                Integer.class,
                from,
                to,
                RelationKind.MATRIX_OF,
                STATE_ACTIVE);
        if (count == null || count == 0) {
            return false;
        }
        upsertRelation(from, to, RelationKind.MATRIX_OF, STATE_ENDED);
        return true;
    }

    // --- helpers ---

    private Map<String, List<String>> activeContainsChildrenIndex() {
        List<OrganizationRelation> edges = jdbc.query(
                """
                SELECT from_organization_id, to_organization_id, relation_kind, relation_state, sort_order
                FROM organization_relation
                WHERE relation_kind = ? AND relation_state = ?
                ORDER BY from_organization_id, sort_order, to_organization_id
                """,
                (row, n) -> new OrganizationRelation(
                        row.getString("from_organization_id"),
                        row.getString("to_organization_id"),
                        row.getString("relation_kind"),
                        row.getString("relation_state"),
                        row.getInt("sort_order")),
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
