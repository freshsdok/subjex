package com.subjex.platform.app.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.organization.JdbcOrganizationStore;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ObjectGrantAccessTest — WP3-1 deny-by-default owner-or-grant checks (ADR 0015).
 * 目的：所有者允许、无授权非所有者拒绝、主体授权允许、组织授权仅在 ACTIVE 成员且租户已绑定组织时允许；
 * 仅有成员关系拒绝；跨租户失败关闭；缺租户/组织范围失败关闭；不以 platform 为默认归属。
 */
class ObjectGrantAccessTest {

    private static final Instant NOW = Instant.parse("2026-11-17T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String FROZEN = "frozen";
    private static final String KEY = "object-one";
    private static final String OWNER = "subject-owner";
    private static final String OTHER = "subject-other";
    private static final String MEMBER = "subject-member";
    private static final String ORG = "org-one";

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void schemaExistsAndLeavesEntityBlobUnowned(H2PlatformTables.Mode mode) {
        Harness h = Harness.open(mode);
        assertEquals(0, h.count("object_descriptor"));
        assertEquals(0, h.count("object_grant"));
        // Legacy entity_blob has no owner and is not silently backfilled.
        // 既有 entity_blob 无所有者，也不会被静默回填。
        h.jdbc.update(
                """
                INSERT INTO entity_blob
                  (blob_id, tenant_id, entity_key, record_id, field_name, content_type,
                   byte_size, storage_key, checksum_sha256, created_at)
                VALUES ('legacy-blob', ?, 'demo', 'r1', 'attachment', 'application/pdf',
                        1, 'legacy-key', '00', CURRENT_TIMESTAMP)
                """,
                TENANT_A);
        assertEquals(ObjectAccessDecision.DENY, h.auth.decide(TENANT_A, "legacy-key", OWNER));
        assertEquals(0, h.count("object_descriptor"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void ownerIsAllowedAndStrangerWithoutGrantIsDenied(H2PlatformTables.Mode mode) {
        Harness h = Harness.open(mode);
        h.store.register(TENANT_A, KEY, OWNER);
        assertEquals(ObjectAccessDecision.ALLOW, h.auth.decide(TENANT_A, KEY, OWNER));
        assertTrue(h.auth.allows(TENANT_A, KEY, OWNER));
        assertEquals(ObjectAccessDecision.DENY, h.auth.decide(TENANT_A, KEY, OTHER));
        assertFalse(h.auth.allows(TENANT_A, KEY, OTHER));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void subjectGrantAllowsGranteeOnly(H2PlatformTables.Mode mode) {
        Harness h = Harness.open(mode);
        h.store.register(TENANT_A, KEY, OWNER);
        h.store.grantToSubject(TENANT_A, KEY, OTHER, null, OWNER);
        assertEquals(ObjectAccessDecision.ALLOW, h.auth.decide(TENANT_A, KEY, OTHER));
        assertEquals(ObjectAccessDecision.DENY, h.auth.decide(TENANT_A, KEY, MEMBER));
        h.store.revoke(h.store.listGrants(TENANT_A, KEY).get(0).grantId());
        assertEquals(ObjectAccessDecision.DENY, h.auth.decide(TENANT_A, KEY, OTHER));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void orgGrantAllowsOnlyActiveMembershipInThatOrgAndTenant(H2PlatformTables.Mode mode) {
        Harness h = Harness.open(mode);
        h.store.register(TENANT_A, KEY, OWNER);
        h.orgs.upsertOrganization(ORG, "Org One", JdbcOrganizationStore.STATE_ACTIVE);
        h.orgs.upsertTenantOrganization(TENANT_A, ORG, JdbcOrganizationStore.STATE_ACTIVE);
        h.subject(MEMBER);
        h.orgs.upsertMembership(MEMBER, ORG, JdbcOrganizationStore.STATE_ACTIVE);
        h.store.grantToOrganization(TENANT_A, KEY, ORG, null, OWNER);

        assertEquals(ObjectAccessDecision.ALLOW, h.auth.decide(TENANT_A, KEY, MEMBER));
        // A member of a different org, or a non-member, stays denied.
        // 其他组织成员或非成员仍拒绝。
        h.orgs.upsertOrganization("org-two", "Org Two", JdbcOrganizationStore.STATE_ACTIVE);
        h.orgs.upsertTenantOrganization(TENANT_A, "org-two", JdbcOrganizationStore.STATE_ACTIVE);
        h.subject(OTHER);
        h.orgs.upsertMembership(OTHER, "org-two", JdbcOrganizationStore.STATE_ACTIVE);
        assertEquals(ObjectAccessDecision.DENY, h.auth.decide(TENANT_A, KEY, OTHER));

        h.subject(MEMBER);
        h.orgs.upsertMembership(MEMBER, ORG, JdbcOrganizationStore.STATE_ENDED);
        assertEquals(ObjectAccessDecision.DENY, h.auth.decide(TENANT_A, KEY, MEMBER));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void membershipWithoutGrantIsDenied(H2PlatformTables.Mode mode) {
        Harness h = Harness.open(mode);
        h.store.register(TENANT_A, KEY, OWNER);
        h.orgs.upsertOrganization(ORG, "Org One", JdbcOrganizationStore.STATE_ACTIVE);
        h.orgs.upsertTenantOrganization(TENANT_A, ORG, JdbcOrganizationStore.STATE_ACTIVE);
        h.subject(MEMBER);
        h.orgs.upsertMembership(MEMBER, ORG, JdbcOrganizationStore.STATE_ACTIVE);
        assertEquals(ObjectAccessDecision.DENY, h.auth.decide(TENANT_A, KEY, MEMBER));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void orgGrantWithoutActiveTenantLinkIsDenied(H2PlatformTables.Mode mode) {
        Harness h = Harness.open(mode);
        h.store.register(TENANT_A, KEY, OWNER);
        h.orgs.upsertOrganization(ORG, "Org One", JdbcOrganizationStore.STATE_ACTIVE);
        h.orgs.upsertTenantOrganization(TENANT_A, ORG, JdbcOrganizationStore.STATE_ACTIVE);
        h.subject(MEMBER);
        h.orgs.upsertMembership(MEMBER, ORG, JdbcOrganizationStore.STATE_ACTIVE);
        h.store.grantToOrganization(TENANT_A, KEY, ORG, null, OWNER);
        h.orgs.upsertTenantOrganization(TENANT_A, ORG, JdbcOrganizationStore.STATE_ENDED);
        assertEquals(ObjectAccessDecision.DENY, h.auth.decide(TENANT_A, KEY, MEMBER));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void crossTenantGrantIsRejectedAndAccessFailsClosed(H2PlatformTables.Mode mode) {
        Harness h = Harness.open(mode);
        h.store.register(TENANT_A, KEY, OWNER);
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> h.store.grantToSubject(TENANT_B, KEY, OTHER, null, OWNER));
        assertTrue(thrown.getMessage().contains("not registered"));
        // Owner of the tenant-A object is not allowed under tenant B (no default / no leak).
        // tenant-A 对象的所有者在 tenant-B 下也不被允许（无默认、无泄漏）。
        assertEquals(ObjectAccessDecision.DENY, h.auth.decide(TENANT_B, KEY, OWNER));
        h.store.grantToSubject(TENANT_A, KEY, OTHER, null, OWNER);
        assertEquals(ObjectAccessDecision.DENY, h.auth.decide(TENANT_B, KEY, OTHER));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void missingTenantOrOrgScopeFailsClosedAndPlatformIsNotADefault(H2PlatformTables.Mode mode) {
        Harness h = Harness.open(mode);
        assertThrows(TenantMissingException.class, () -> h.store.register("  ", KEY, OWNER));
        assertThrows(TenantMissingException.class, () -> h.store.register(null, KEY, OWNER));
        assertThrows(
                IllegalArgumentException.class,
                () -> h.store.register(ObjectGrantStore.RESERVED_TENANT, KEY, OWNER));
        assertThrows(TenantNotFoundException.class, () -> h.store.register("missing-tenant", KEY, OWNER));
        assertThrows(TenantDisabledException.class, () -> h.store.register(FROZEN, KEY, OWNER));

        assertEquals(ObjectAccessDecision.DENY, h.auth.decide(null, KEY, OWNER));
        assertEquals(ObjectAccessDecision.DENY, h.auth.decide("", KEY, OWNER));
        assertEquals(ObjectAccessDecision.DENY, h.auth.decide(ObjectGrantStore.RESERVED_TENANT, KEY, OWNER));
        assertEquals(ObjectAccessDecision.DENY, h.auth.decide("missing-tenant", KEY, OWNER));
        assertEquals(ObjectAccessDecision.DENY, h.auth.decide(FROZEN, KEY, OWNER));

        h.store.register(TENANT_A, KEY, OWNER);
        h.orgs.upsertOrganization(ORG, "Org One", JdbcOrganizationStore.STATE_ACTIVE);
        // Org not bound to the tenant — write fails closed; blank org id also rejected.
        // 组织未绑定到租户则写入失败关闭；空组织标识同样拒绝。
        IllegalArgumentException unbound = assertThrows(
                IllegalArgumentException.class,
                () -> h.store.grantToOrganization(TENANT_A, KEY, ORG, null, OWNER));
        assertTrue(unbound.getMessage().contains("not bound"));
        assertThrows(
                IllegalArgumentException.class,
                () -> h.store.grantToOrganization(TENANT_A, KEY, "  ", null, OWNER));
        assertThrows(
                IllegalArgumentException.class,
                () -> h.store.grantToOrganization(TENANT_A, KEY, null, null, OWNER));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void expiredGrantIsIgnored(H2PlatformTables.Mode mode) {
        Harness h = Harness.open(mode);
        h.store.register(TENANT_A, KEY, OWNER);
        h.store.grantToSubject(TENANT_A, KEY, OTHER, NOW.plusSeconds(30), OWNER);
        assertEquals(ObjectAccessDecision.ALLOW, h.auth.decide(TENANT_A, KEY, OTHER));
        ObjectAccessAuthorizer later = new ObjectAccessAuthorizer(
                h.store, h.jdbc, Clock.fixed(NOW.plusSeconds(31), ZoneOffset.UTC));
        assertEquals(ObjectAccessDecision.DENY, later.decide(TENANT_A, KEY, OTHER));
        assertEquals(ObjectAccessDecision.ALLOW, later.decide(TENANT_A, KEY, OWNER));
    }

    private static final class Harness {
        final JdbcTemplate jdbc;
        final JdbcObjectGrantStore store;
        final ObjectAccessAuthorizer auth;
        final JdbcOrganizationStore orgs;

        private Harness(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
            this.store = new JdbcObjectGrantStore(jdbc, CLOCK);
            this.auth = new ObjectAccessAuthorizer(store, jdbc, CLOCK);
            this.orgs = new JdbcOrganizationStore(jdbc);
        }

        static Harness open(H2PlatformTables.Mode mode) {
            DataSource ds = H2PlatformTables.migrated(mode);
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    TENANT_A,
                    "Tenant A");
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    TENANT_B,
                    "Tenant B");
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'SUSPENDED')",
                    FROZEN,
                    "Frozen");
            return new Harness(jdbc);
        }


        void subject(String subjectId) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM subject WHERE subject_id = ?", Integer.class, subjectId);
            if (n != null && n > 0) {
                return;
            }
            jdbc.update(
                    "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?, ?, 'PERSON')",
                    subjectId,
                    subjectId);
        }

        int count(String table) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            return n == null ? 0 : n;
        }
    }
}
