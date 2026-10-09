package com.subjex.platform.app.organization;

import com.subjex.platform.app.org.OrgMembership;
import com.subjex.platform.app.org.OrgUnit;
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
 * OrganizationOntologyBackfill — O3 per-tenant 1:1 copy of org_unit / org_membership into
 * organization ontology tables. Idempotent. Never merges same-name orgs across tenants (MIG-03).
 * <p>
 * O7: after Flyway V24, legacy tables are gone. {@link #backfillAll()} is a no-op when tables are
 * absent. Organization API "reverse sync" keeps {@code org_unit_organization_map} only (no writes
 * to dropped tables). Directory write-through uses {@link #syncUnitWriteThrough} /
 * {@link #syncMembershipWriteThrough} against ontology + map.
 * <p>
 * O3 回填；O7 后旧表已删，回填空操作；反向同步只维护映射表。
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
        List<OrgUnit> units = listLegacyUnits(tid);
        int orgCount = 0;
        int mapCount = 0;
        Map<String, String> unitToOrg = new HashMap<>();
        for (OrgUnit unit : units) {
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
    public void syncUnitWriteThrough(String tenantId, OrgUnit unit) {
        String tid = requireNonBlank(tenantId, "tenantId");
        Objects.requireNonNull(unit, "unit");
        ensureTenantRow(tid);
        boolean unique = orgUnitIdGloballyUniqueIncluding(tid, unit.orgUnitId());
        String organizationId = findOrganizationId(tid, unit.orgUnitId())
                .orElseGet(() -> OrganizationIdMint.mint(tid, unit.orgUnitId(), unique));
        store.upsertOrganization(organizationId, unit.unitName(), unit.unitState());
        store.upsertTenantOrganization(tid, organizationId, JdbcOrganizationStore.STATE_ACTIVE);
        upsertMap(tid, unit.orgUnitId(), organizationId);
        syncContainsParent(tid, unit.parentOrgUnitId(), organizationId);
    }

    public void syncMembershipWriteThrough(String tenantId, OrgMembership membership) {
        String tid = requireNonBlank(tenantId, "tenantId");
        Objects.requireNonNull(membership, "membership");
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
     * O5/O7: ensure map alias for Organization API writes (legacy org_unit_id compatibility).
     * No longer writes {@code org_unit} (dropped in V24).
     */
    public void syncOrganizationToLegacy(
            String tenantId, Organization organization, String parentOrganizationIdOrNull) {
        String tid = requireNonBlank(tenantId, "tenantId");
        Objects.requireNonNull(organization, "organization");
        String oid = requireNonBlank(organization.organizationId(), "organizationId");
        ensureTenantRow(tid);
        String orgUnitId = findOrgUnitId(tid, oid).orElse(oid);
        upsertMap(tid, orgUnitId, oid);
        // Parent validation only — CONTAINS already set by OrganizationApiEndpoint.
        if (parentOrganizationIdOrNull != null && !parentOrganizationIdOrNull.isBlank()) {
            String parentOrg = parentOrganizationIdOrNull.trim();
            findOrgUnitId(tid, parentOrg)
                    .orElseThrow(() -> new IllegalArgumentException("parent organization not mapped in tenant"));
        }
    }

    /**
     * O5/O7: membership already lives in ontology; legacy table removed — no-op.
     */
    public void syncMembershipToLegacy(String tenantId, Membership membership) {
        requireNonBlank(tenantId, "tenantId");
        Objects.requireNonNull(membership, "membership");
        // Ensure map exists when membership org was created via Organization API.
        findOrgUnitId(tenantId, membership.organizationId())
                .orElseGet(
                        () -> {
                            upsertMap(tenantId, membership.organizationId(), membership.organizationId());
                            return membership.organizationId();
                        });
    }

    /** O5/O7: ontology membership already ENDED by caller; legacy delete is no-op. */
    public void endMembershipToLegacy(String tenantId, String subjectId, String organizationId) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(subjectId, "subjectId");
        requireNonBlank(organizationId, "organizationId");
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
}
