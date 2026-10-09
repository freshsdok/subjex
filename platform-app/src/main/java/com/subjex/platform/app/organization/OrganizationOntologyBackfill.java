package com.subjex.platform.app.organization;

import com.subjex.platform.app.org.OrgMembership;
import com.subjex.platform.app.org.OrgUnit;
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
 * OrganizationOntologyBackfill — O3 per-tenant 1:1 copy of org_unit / org_membership into
 * organization ontology tables. Idempotent. Never merges same-name orgs across tenants (MIG-03).
 * <p>
 * O3：按租户 1:1 回填；幂等；禁止跨租户同名合并。旧表不删（回滚=停用新读，继续旧表）。
 * O5 also reverse-syncs Organization API writes into legacy tables for dual-read.
 * O5 亦将 Organization API 写入反向同步到旧表以维持双读。
 */
public final class OrganizationOntologyBackfill {

    public record Result(
            int tenantsProcessed,
            int organizationsUpserted,
            int relationsUpserted,
            int membershipsUpserted,
            int mapRows) {}

    private final JdbcTemplate jdbc;
    private final JdbcOrganizationStore store;

    public OrganizationOntologyBackfill(JdbcTemplate jdbc) {
        this(jdbc, new JdbcOrganizationStore(jdbc));
    }

    public OrganizationOntologyBackfill(JdbcTemplate jdbc, JdbcOrganizationStore store) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Backfill every tenant that has at least one org_unit — 回填所有有 org_unit 的租户。 */
    public Result backfillAll() {
        List<String> tenants = jdbc.query(
                "SELECT DISTINCT tenant_id FROM org_unit ORDER BY tenant_id",
                (row, n) -> row.getString("tenant_id"));
        int orgs = 0;
        int rels = 0;
        int mems = 0;
        int maps = 0;
        for (String tenantId : tenants) {
            Result one = backfillTenant(tenantId);
            orgs += one.organizationsUpserted();
            rels += one.relationsUpserted();
            mems += one.membershipsUpserted();
            maps += one.mapRows();
        }
        return new Result(tenants.size(), orgs, rels, mems, maps);
    }

    /**
     * Copy one tenant's units, parent CONTAINS edges, and memberships.
     * 回填单租户：组织、CONTAINS 边、成员关系。
     */
    public Result backfillTenant(String tenantId) {
        String tid = requireNonBlank(tenantId, "tenantId");
        ensureTenantRow(tid);
        Set<String> collidingUnitIds = collidingOrgUnitIds();
        List<OrgUnit> units = listLegacyUnits(tid);
        int orgCount = 0;
        int mapCount = 0;
        Map<String, String> unitToOrg = new HashMap<>();
        for (OrgUnit unit : units) {
            boolean unique = !collidingUnitIds.contains(unit.orgUnitId());
            String organizationId = findOrganizationId(tid, unit.orgUnitId())
                    .orElseGet(() -> OrganizationIdMint.mint(tid, unit.orgUnitId(), unique));
            store.upsertOrganization(organizationId, unit.unitName(), unit.unitState());
            store.upsertTenantOrganization(tid, organizationId, JdbcOrganizationStore.STATE_ACTIVE);
            upsertMap(tid, unit.orgUnitId(), organizationId);
            unitToOrg.put(unit.orgUnitId(), organizationId);
            orgCount++;
            mapCount++;
        }
        int relCount = 0;
        for (OrgUnit unit : units) {
            String parent = unit.parentOrgUnitId();
            if (parent == null || parent.isBlank()) {
                continue;
            }
            String parentOrg = unitToOrg.get(parent);
            String childOrg = unitToOrg.get(unit.orgUnitId());
            if (parentOrg == null || childOrg == null) {
                throw new IllegalStateException(
                        "parent org unit not mapped in tenant " + tid + ": " + parent);
            }
            store.upsertRelation(parentOrg, childOrg, RelationKind.CONTAINS, JdbcOrganizationStore.STATE_ACTIVE);
            relCount++;
        }
        int memCount = 0;
        for (OrgMembership membership : listLegacyMemberships(tid)) {
            String organizationId = unitToOrg.get(membership.orgUnitId());
            if (organizationId == null) {
                throw new IllegalStateException(
                        "membership org unit not mapped in tenant "
                                + tid
                                + ": "
                                + membership.orgUnitId());
            }
            ensureSubjectRow(membership.subjectId());
            store.upsertMembership(membership.subjectId(), organizationId, membership.membershipState());
            memCount++;
        }
        return new Result(1, orgCount, relCount, memCount, mapCount);
    }

    /** Whether remap covers every legacy unit for the tenant (dual-read gate). */
    public boolean isTenantFullyBackfilled(String tenantId) {
        String tid = requireNonBlank(tenantId, "tenantId");
        Integer units = jdbc.queryForObject(
                "SELECT COUNT(*) FROM org_unit WHERE tenant_id = ?", Integer.class, tid);
        Integer maps = jdbc.queryForObject(
                "SELECT COUNT(*) FROM org_unit_organization_map WHERE tenant_id = ?", Integer.class, tid);
        int u = units == null ? 0 : units;
        int m = maps == null ? 0 : maps;
        return u > 0 && u == m;
    }

    public Optional<String> findOrganizationId(String tenantId, String orgUnitId) {
        String tid = requireNonBlank(tenantId, "tenantId");
        String uid = requireNonBlank(orgUnitId, "orgUnitId");
        List<String> rows = jdbc.query(
                """
                SELECT organization_id
                FROM org_unit_organization_map
                WHERE tenant_id = ? AND org_unit_id = ?
                """,
                (row, n) -> row.getString("organization_id"),
                tid,
                uid);
        return rows.stream().findFirst();
    }

    public Optional<String> findOrgUnitId(String tenantId, String organizationId) {
        String tid = requireNonBlank(tenantId, "tenantId");
        String oid = requireNonBlank(organizationId, "organizationId");
        List<String> rows = jdbc.query(
                """
                SELECT org_unit_id
                FROM org_unit_organization_map
                WHERE tenant_id = ? AND organization_id = ?
                """,
                (row, n) -> row.getString("org_unit_id"),
                tid,
                oid);
        return rows.stream().findFirst();
    }

    /**
     * Write-through one legacy unit into ontology tables (after legacy write).
     * 写透：在旧表写入后同步到新表。
     */
    public void syncUnitWriteThrough(String tenantId, OrgUnit unit) {
        String tid = requireNonBlank(tenantId, "tenantId");
        ensureTenantRow(tid);
        Set<String> colliding = collidingOrgUnitIds();
        boolean unique = !colliding.contains(unit.orgUnitId());
        String organizationId = findOrganizationId(tid, unit.orgUnitId())
                .orElseGet(() -> OrganizationIdMint.mint(tid, unit.orgUnitId(), unique));
        store.upsertOrganization(organizationId, unit.unitName(), unit.unitState());
        store.upsertTenantOrganization(tid, organizationId, JdbcOrganizationStore.STATE_ACTIVE);
        upsertMap(tid, unit.orgUnitId(), organizationId);
        syncContainsParent(tid, unit.parentOrgUnitId(), organizationId);
    }

    public void syncMembershipWriteThrough(String tenantId, OrgMembership membership) {
        String tid = requireNonBlank(tenantId, "tenantId");
        String organizationId = findOrganizationId(tid, membership.orgUnitId())
                .orElseThrow(() -> new IllegalStateException(
                        "org unit not mapped; backfill or upsert unit first: " + membership.orgUnitId()));
        ensureSubjectRow(membership.subjectId());
        store.upsertMembership(membership.subjectId(), organizationId, membership.membershipState());
    }

    public void endMembershipWriteThrough(String tenantId, String subjectId, String orgUnitId) {
        String tid = requireNonBlank(tenantId, "tenantId");
        Optional<String> organizationId = findOrganizationId(tid, orgUnitId);
        if (organizationId.isEmpty()) {
            return;
        }
        store.upsertMembership(subjectId, organizationId.get(), JdbcOrganizationStore.STATE_ENDED);
    }

    /**
     * O5 reverse write-through: ontology organization → legacy org_unit + map (dual-read keep-alive).
     * Uses existing map org_unit_id when present; else organizationId as org_unit_id.
     * O5 反向写透：新表组织 → 旧 org_unit + 映射（维持双读）。
     */
    public void syncOrganizationToLegacy(
            String tenantId, Organization organization, String parentOrganizationIdOrNull) {
        String tid = requireNonBlank(tenantId, "tenantId");
        Objects.requireNonNull(organization, "organization");
        String oid = requireNonBlank(organization.organizationId(), "organizationId");
        String name = requireNonBlank(organization.organizationName(), "organizationName");
        String state = organization.organizationState() == null || organization.organizationState().isBlank()
                ? JdbcOrganizationStore.STATE_ACTIVE
                : organization.organizationState().trim();
        ensureTenantRow(tid);
        String orgUnitId = findOrgUnitId(tid, oid).orElse(oid);
        String parentUnitId = null;
        if (parentOrganizationIdOrNull != null && !parentOrganizationIdOrNull.isBlank()) {
            String parentOrg = parentOrganizationIdOrNull.trim();
            parentUnitId = findOrgUnitId(tid, parentOrg)
                    .orElseThrow(() -> new IllegalArgumentException("parent organization not mapped in tenant"));
        }
        upsertLegacyUnit(tid, orgUnitId, parentUnitId, name, state);
        upsertMap(tid, orgUnitId, oid);
    }

    /**
     * O5 reverse write-through: ontology membership → legacy org_membership.
     * O5 反向写透：新成员 → 旧 org_membership。
     */
    public void syncMembershipToLegacy(String tenantId, Membership membership) {
        String tid = requireNonBlank(tenantId, "tenantId");
        Objects.requireNonNull(membership, "membership");
        String oid = requireNonBlank(membership.organizationId(), "organizationId");
        String orgUnitId = findOrgUnitId(tid, oid)
                .orElseThrow(() -> new IllegalStateException(
                        "organization not mapped; upsert organization first: " + oid));
        String state = membership.membershipState() == null || membership.membershipState().isBlank()
                ? JdbcOrganizationStore.STATE_ACTIVE
                : membership.membershipState().trim();
        if (JdbcOrganizationStore.STATE_ENDED.equals(state)) {
            jdbc.update(
                    """
                    DELETE FROM org_membership
                    WHERE tenant_id = ? AND subject_id = ? AND org_unit_id = ?
                    """,
                    tid,
                    membership.subjectId(),
                    orgUnitId);
            return;
        }
        upsertLegacyMembership(tid, membership.subjectId(), orgUnitId, state);
    }

    /** O5: end ontology membership and delete legacy row when mapped. */
    public void endMembershipToLegacy(String tenantId, String subjectId, String organizationId) {
        String tid = requireNonBlank(tenantId, "tenantId");
        String sid = requireNonBlank(subjectId, "subjectId");
        String oid = requireNonBlank(organizationId, "organizationId");
        Optional<String> orgUnitId = findOrgUnitId(tid, oid);
        if (orgUnitId.isPresent()) {
            jdbc.update(
                    """
                    DELETE FROM org_membership
                    WHERE tenant_id = ? AND subject_id = ? AND org_unit_id = ?
                    """,
                    tid,
                    sid,
                    orgUnitId.get());
        }
    }

    private List<OrgUnit> listLegacyUnits(String tenantId) {
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

    private List<OrgMembership> listLegacyMemberships(String tenantId) {
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

    private void syncContainsParent(String tenantId, String parentOrgUnitId, String childOrganizationId) {
        String newParentOrg = null;
        if (parentOrgUnitId != null && !parentOrgUnitId.isBlank()) {
            newParentOrg = findOrganizationId(tenantId, parentOrgUnitId)
                    .orElseThrow(() -> new IllegalStateException(
                            "parent org unit not mapped: " + parentOrgUnitId));
        }
        if (newParentOrg == null) {
            jdbc.update(
                    """
                    UPDATE organization_relation
                    SET relation_state = ?
                    WHERE to_organization_id = ? AND relation_kind = ? AND relation_state = ?
                    """,
                    JdbcOrganizationStore.STATE_ENDED,
                    childOrganizationId,
                    RelationKind.CONTAINS,
                    JdbcOrganizationStore.STATE_ACTIVE);
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
                JdbcOrganizationStore.STATE_ENDED,
                childOrganizationId,
                RelationKind.CONTAINS,
                JdbcOrganizationStore.STATE_ACTIVE,
                newParentOrg);
        store.upsertRelation(
                newParentOrg, childOrganizationId, RelationKind.CONTAINS, JdbcOrganizationStore.STATE_ACTIVE);
    }

    private void upsertLegacyUnit(
            String tenantId, String orgUnitId, String parentOrgUnitId, String unitName, String unitState) {
        int updated = jdbc.update(
                """
                UPDATE org_unit
                SET parent_org_unit_id = ?, unit_name = ?, unit_state = ?
                WHERE tenant_id = ? AND org_unit_id = ?
                """,
                parentOrgUnitId,
                unitName,
                unitState,
                tenantId,
                orgUnitId);
        if (updated == 0) {
            try {
                jdbc.update(
                        """
                        INSERT INTO org_unit (tenant_id, org_unit_id, parent_org_unit_id, unit_name, unit_state)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        tenantId,
                        orgUnitId,
                        parentOrgUnitId,
                        unitName,
                        unitState);
            } catch (DuplicateKeyException raced) {
                jdbc.update(
                        """
                        UPDATE org_unit
                        SET parent_org_unit_id = ?, unit_name = ?, unit_state = ?
                        WHERE tenant_id = ? AND org_unit_id = ?
                        """,
                        parentOrgUnitId,
                        unitName,
                        unitState,
                        tenantId,
                        orgUnitId);
            }
        }
    }

    private void upsertLegacyMembership(
            String tenantId, String subjectId, String orgUnitId, String membershipState) {
        int updated = jdbc.update(
                """
                UPDATE org_membership
                SET membership_state = ?
                WHERE tenant_id = ? AND subject_id = ? AND org_unit_id = ?
                """,
                membershipState,
                tenantId,
                subjectId,
                orgUnitId);
        if (updated == 0) {
            try {
                jdbc.update(
                        """
                        INSERT INTO org_membership (tenant_id, subject_id, org_unit_id, membership_state)
                        VALUES (?, ?, ?, ?)
                        """,
                        tenantId,
                        subjectId,
                        orgUnitId,
                        membershipState);
            } catch (DuplicateKeyException raced) {
                jdbc.update(
                        """
                        UPDATE org_membership
                        SET membership_state = ?
                        WHERE tenant_id = ? AND subject_id = ? AND org_unit_id = ?
                        """,
                        membershipState,
                        tenantId,
                        subjectId,
                        orgUnitId);
            }
        }
    }

    private void upsertMap(String tenantId, String orgUnitId, String organizationId) {
        int updated = jdbc.update(
                """
                UPDATE org_unit_organization_map
                SET organization_id = ?
                WHERE tenant_id = ? AND org_unit_id = ?
                """,
                organizationId,
                tenantId,
                orgUnitId);
        if (updated == 0) {
            try {
                jdbc.update(
                        """
                        INSERT INTO org_unit_organization_map (tenant_id, org_unit_id, organization_id)
                        VALUES (?, ?, ?)
                        """,
                        tenantId,
                        orgUnitId,
                        organizationId);
            } catch (DuplicateKeyException raced) {
                jdbc.update(
                        """
                        UPDATE org_unit_organization_map
                        SET organization_id = ?
                        WHERE tenant_id = ? AND org_unit_id = ?
                        """,
                        organizationId,
                        tenantId,
                        orgUnitId);
            }
        }
    }

    private Set<String> collidingOrgUnitIds() {
        List<String> ids = jdbc.query(
                """
                SELECT org_unit_id
                FROM org_unit
                GROUP BY org_unit_id
                HAVING COUNT(DISTINCT tenant_id) > 1
                """,
                (row, n) -> row.getString("org_unit_id"));
        return new HashSet<>(ids);
    }

    private void ensureTenantRow(String tenantId) {
        Integer count =
                jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Integer.class, tenantId);
        if (count != null && count > 0) {
            return;
        }
        try {
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?,?,?)",
                    tenantId,
                    tenantId,
                    "ACTIVE");
        } catch (DuplicateKeyException ignored) {
            // raced
        }
    }

    private void ensureSubjectRow(String subjectId) {
        Integer count =
                jdbc.queryForObject("SELECT COUNT(*) FROM subject WHERE subject_id = ?", Integer.class, subjectId);
        if (count != null && count > 0) {
            return;
        }
        try {
            jdbc.update(
                    "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?,?,?)",
                    subjectId,
                    subjectId,
                    "PERSON");
        } catch (DuplicateKeyException ignored) {
            // raced
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return value.trim();
    }
}
