package com.subjex.platform.app.organization;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * OrganizationOntologyBackfill — O3/O7/O8-4 migration helper: per-tenant 1:1 copy of org_unit /
 * org_membership into organization ontology (+ map). Idempotent. Never merges same-name orgs
 * across tenants (MIG-03).
 * <p>
 * <b>O8-4:</b> Not on the formal Organization API / Policy / zero-code happy path. Call sites:
 * <ul>
 *   <li>{@link #backfillAll()} / {@link #backfillTenant(String)} — Flyway {@code V24} upgrade and
 *       explicit migration/CLI callers only</li>
 *   <li>{@link #syncUnitWriteThrough} / {@link #syncMembershipWriteThrough} /
 *       {@link #endMembershipWriteThrough} — legacy {@code org.legacy.JdbcOrgDirectory} adapter only</li>
 * </ul>
 * After V24, {@link #backfillAll()} is a no-op when legacy tables are absent. Map table is kept
 * for legacy alias projection / write-through; formal runtime must not query it.
 * <p>
 * O8-4：正式 API/策略/零代码不经此类；仅迁移与旧目录适配器。
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

    /** Whether legacy {@code org_unit} still exists (pre-O7 / mid-V24). */
    public boolean legacyOrgTablesPresent() {
        return tableExists("org_unit");
    }

    /** Backfill every tenant that has at least one org_unit — 回填所有有 org_unit 的租户。 */
    public Result backfillAll() {
        if (!legacyOrgTablesPresent()) {
            return new Result(0, 0, 0, 0, 0);
        }
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
     * No-op when legacy tables are already dropped (O7).
     */
    public Result backfillTenant(String tenantId) {
        String tid = requireNonBlank(tenantId, "tenantId");
        if (!legacyOrgTablesPresent()) {
            return new Result(0, 0, 0, 0, 0);
        }
        ensureTenantRow(tid);
        List<LegacyUnitRow> units = listLegacyUnits(tid);
        int orgCount = 0;
        int mapCount = 0;
        Map<String, String> unitToOrg = new HashMap<>();
        for (LegacyUnitRow unit : units) {
            boolean unique = orgUnitIdGloballyUniqueIncluding(tid, unit.orgUnitId());
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
        for (LegacyUnitRow unit : units) {
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
        for (LegacyMembershipRow membership : listLegacyMemberships(tid)) {
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

    /**
     * Dual-read gate (pre-O7): remap covers every legacy unit.
     * Post-O7 (legacy gone): always true — ontology is source of truth.
     */
    public boolean isTenantFullyBackfilled(String tenantId) {
        String tid = requireNonBlank(tenantId, "tenantId");
        if (!legacyOrgTablesPresent()) {
            return true;
        }
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
     * Write-through one unit into ontology tables + map (legacy directory / adapter writes).
     * Does not require {@code org_unit} rows (O7).
     */
    /**
     * Write-through one legacy unit id into ontology + map (called from org.legacy directory).
     * Primitive args so this class does not depend on org.legacy types (O8-3).
     */
    public void syncUnitWriteThrough(
            String tenantId,
            String orgUnitId,
            String parentOrgUnitId,
            String unitName,
            String unitState) {
        String tid = requireNonBlank(tenantId, "tenantId");
        String uid = requireNonBlank(orgUnitId, "orgUnitId");
        ensureTenantRow(tid);
        boolean unique = orgUnitIdGloballyUniqueIncluding(tid, uid);
        String organizationId = findOrganizationId(tid, uid)
                .orElseGet(() -> OrganizationIdMint.mint(tid, uid, unique));
        String name = requireNonBlank(unitName, "unitName");
        String state =
                unitState == null || unitState.isBlank()
                        ? JdbcOrganizationStore.STATE_ACTIVE
                        : unitState.trim();
        store.upsertOrganization(organizationId, name, state);
        store.upsertTenantOrganization(tid, organizationId, JdbcOrganizationStore.STATE_ACTIVE);
        upsertMap(tid, uid, organizationId);
        syncContainsParent(tid, parentOrgUnitId, organizationId);
    }

    public void syncMembershipWriteThrough(
            String tenantId, String subjectId, String orgUnitId, String membershipState) {
        String tid = requireNonBlank(tenantId, "tenantId");
        String sid = requireNonBlank(subjectId, "subjectId");
        String uid = requireNonBlank(orgUnitId, "orgUnitId");
        String organizationId = resolveOrganizationIdForLegacyWire(tid, uid);
        ensureSubjectRow(sid);
        String state =
                membershipState == null || membershipState.isBlank()
                        ? JdbcOrganizationStore.STATE_ACTIVE
                        : membershipState.trim();
        store.upsertMembership(sid, organizationId, state);
    }

    public void endMembershipWriteThrough(String tenantId, String subjectId, String orgUnitId) {
        String tid = requireNonBlank(tenantId, "tenantId");
        String uid = requireNonBlank(orgUnitId, "orgUnitId");
        Optional<String> organizationId = findOrganizationId(tid, uid);
        if (organizationId.isEmpty() && store.organizationExists(uid)) {
            organizationId = Optional.of(uid);
        }
        if (organizationId.isEmpty()) {
            return;
        }
        store.upsertMembership(subjectId, organizationId.get(), JdbcOrganizationStore.STATE_ENDED);
    }

    /**
     * Map alias first; else treat wire id as organization_id when linked to the tenant (O8-4
     * ontology-first legacy adapter).
     */
    private String resolveOrganizationIdForLegacyWire(String tenantId, String wireId) {
        Optional<String> mapped = findOrganizationId(tenantId, wireId);
        if (mapped.isPresent()) {
            return mapped.get();
        }
        boolean linked = store.listOrganizationsLinkedToTenant(tenantId).stream()
                .anyMatch(o -> o.organizationId().equals(wireId));
        if (linked) {
            return wireId;
        }
        throw new IllegalStateException(
                "org unit not mapped; backfill or upsert unit first: " + wireId);
    }


    /**
     * Whether this org_unit_id string is unique across tenants for minting (including {@code tenantId}).
     * Used so a second tenant claiming the same unit id gets a hashed organization_id (MIG-03).
     */
    public boolean orgUnitIdGloballyUniqueIncluding(String tenantId, String orgUnitId) {
        String tid = requireNonBlank(tenantId, "tenantId");
        String uid = requireNonBlank(orgUnitId, "orgUnitId");
        Set<String> tenants = new HashSet<>();
        List<String> fromMap = jdbc.query(
                "SELECT DISTINCT tenant_id FROM org_unit_organization_map WHERE org_unit_id = ?",
                (row, n) -> row.getString("tenant_id"),
                uid);
        tenants.addAll(fromMap);
        if (legacyOrgTablesPresent()) {
            List<String> fromLegacy = jdbc.query(
                    "SELECT DISTINCT tenant_id FROM org_unit WHERE org_unit_id = ?",
                    (row, n) -> row.getString("tenant_id"),
                    uid);
            tenants.addAll(fromLegacy);
        }
        tenants.add(tid);
        return tenants.size() <= 1;
    }

    private List<LegacyUnitRow> listLegacyUnits(String tenantId) {
        return jdbc.query(
                """
                SELECT tenant_id, org_unit_id, parent_org_unit_id, unit_name, unit_state
                FROM org_unit
                WHERE tenant_id = ?
                ORDER BY org_unit_id
                """,
                (row, n) -> new LegacyUnitRow(
                        row.getString("tenant_id"),
                        row.getString("org_unit_id"),
                        row.getString("parent_org_unit_id"),
                        row.getString("unit_name"),
                        row.getString("unit_state")),
                tenantId);
    }

    private List<LegacyMembershipRow> listLegacyMemberships(String tenantId) {
        return jdbc.query(
                """
                SELECT tenant_id, subject_id, org_unit_id, membership_state
                FROM org_membership
                WHERE tenant_id = ?
                ORDER BY subject_id, org_unit_id
                """,
                (row, n) -> new LegacyMembershipRow(
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

    private boolean tableExists(String tableName) {
        try {
            if (jdbc.getDataSource() == null) {
                return false;
            }
            Set<String> tables = new HashSet<>();
            try (Connection connection = jdbc.getDataSource().getConnection()) {
                DatabaseMetaData meta = connection.getMetaData();
                try (ResultSet rs = meta.getTables(null, null, "%", new String[] {"TABLE"})) {
                    while (rs.next()) {
                        tables.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                    }
                }
            }
            return tables.contains(tableName.toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
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

    /** Internal row for pre-DROP org_unit reads — not a production domain type. */
    private record LegacyUnitRow(
            String tenantId, String orgUnitId, String parentOrgUnitId, String unitName, String unitState) {}

    /** Internal row for pre-DROP org_membership reads — not a production domain type. */
    private record LegacyMembershipRow(
            String tenantId, String subjectId, String orgUnitId, String membershipState) {}
}
