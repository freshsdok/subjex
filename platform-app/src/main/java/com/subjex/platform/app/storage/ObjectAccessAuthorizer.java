package com.subjex.platform.app.storage;

import com.subjex.platform.app.organization.JdbcOrganizationStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ObjectAccessAuthorizer — deny-by-default access check above {@code ObjectStorage} (ADR 0015 / WP3-1).
 * 对象访问判定：默认拒绝，叠在对象存储端口之上，两端后端共用同一结论。
 * <p>
 * Allow the owner subject, or an explicit unexpired in-tenant grant to the actor subject,
 * or to an organization where the actor currently has ACTIVE Membership and the org has an
 * ACTIVE TenantOrganization link in the object's tenant. Membership alone is not authorization.
 * 允许所有者，或未过期的主体授权，或「当前 ACTIVE 成员 + 该租户 ACTIVE 组织关联」的组织授权。
 * 仅有成员关系不是授权。
 * <p>
 * Missing tenant/org scope, reserved {@code platform}, disabled tenant, unregistered or
 * unowned objects, and cross-tenant lookups all fail closed ({@link ObjectAccessDecision#DENY}).
 * This class does not issue signed URLs and does not write audit events (WP3-2 / WP3-4).
 * 缺范围、保留租户、停用租户、未登记/无所有者、跨租户查找一律失败关闭。本切片不签发签名链接、不写审计。
 */
public final class ObjectAccessAuthorizer {

    private final ObjectGrantStore grants;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ObjectAccessAuthorizer(ObjectGrantStore grants, JdbcTemplate jdbc, Clock clock) {
        this.grants = Objects.requireNonNull(grants, "grants");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** True when {@link #decide} is {@link ObjectAccessDecision#ALLOW} — 判定为允许时为 true。 */
    public boolean allows(String tenantId, String objectKey, String actorSubjectId) {
        return decide(tenantId, objectKey, actorSubjectId) == ObjectAccessDecision.ALLOW;
    }

    /**
     * Decide access. Never defaults the tenant to {@code platform}.
     * 判定访问。永不把租户默认成 platform。
     */
    public ObjectAccessDecision decide(String tenantId, String objectKey, String actorSubjectId) {
        if (tenantId == null || tenantId.isBlank() || ObjectGrantStore.RESERVED_TENANT.equals(tenantId.trim())) {
            return ObjectAccessDecision.DENY;
        }
        if (objectKey == null || objectKey.isBlank() || actorSubjectId == null || actorSubjectId.isBlank()) {
            return ObjectAccessDecision.DENY;
        }
        String tid = tenantId.trim();
        String key = objectKey.trim();
        String actor = actorSubjectId.trim();
        if (!isActiveBusinessTenant(tid)) {
            return ObjectAccessDecision.DENY;
        }
        var descriptor = grants.find(tid, key);
        if (descriptor.isEmpty()) {
            // Unregistered objects (including legacy entity_blob with no owner) are denied.
            // 未登记对象（含无所有者的既有 entity_blob）一律拒绝。
            return ObjectAccessDecision.DENY;
        }
        ObjectDescriptor object = descriptor.get();
        if (object.ownerSubjectId() == null || object.ownerSubjectId().isBlank()) {
            return ObjectAccessDecision.DENY;
        }
        if (actor.equals(object.ownerSubjectId())) {
            return ObjectAccessDecision.ALLOW;
        }
        Instant now = clock.instant();
        for (ObjectGrant grant : grants.listGrants(tid, key)) {
            if (!isEffective(grant, now)) {
                continue;
            }
            if (grant.granteeKind() == ObjectGrantKind.SUBJECT && actor.equals(grant.granteeId())) {
                return ObjectAccessDecision.ALLOW;
            }
            if (grant.granteeKind() == ObjectGrantKind.ORGANIZATION
                    && isActiveMemberInTenant(tid, grant.granteeId(), actor)) {
                return ObjectAccessDecision.ALLOW;
            }
        }
        return ObjectAccessDecision.DENY;
    }

    private boolean isEffective(ObjectGrant grant, Instant now) {
        if (grant == null || grant.granteeId() == null || grant.granteeId().isBlank()) {
            return false;
        }
        if (grant.expiresAt() == null) {
            return true;
        }
        return grant.expiresAt().isAfter(now);
    }

    private boolean isActiveBusinessTenant(String tenantId) {
        List<String> states = jdbc.query(
                "SELECT tenant_state FROM tenant WHERE tenant_id = ?",
                (row, n) -> row.getString("tenant_state"),
                tenantId);
        if (states.isEmpty()) {
            return false;
        }
        String state = states.get(0) == null ? "" : states.get(0).trim().toUpperCase(Locale.ROOT);
        return "ACTIVE".equals(state);
    }

    /**
     * ACTIVE membership in the organization, and the org is ACTIVE-linked to the tenant.
     * 主体在该组织有 ACTIVE 成员关系，且组织与该租户有 ACTIVE 关联。
     */
    boolean isActiveMemberInTenant(String tenantId, String organizationId, String subjectId) {
        if (organizationId == null || organizationId.isBlank()) {
            return false;
        }
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM membership m
                INNER JOIN tenant_organization t
                    ON t.organization_id = m.organization_id
                   AND t.tenant_id = ?
                   AND t.link_state = ?
                INNER JOIN organization o
                    ON o.organization_id = m.organization_id
                   AND o.organization_state = ?
                WHERE m.subject_id = ?
                  AND m.organization_id = ?
                  AND m.membership_state = ?
                """,
                Integer.class,
                tenantId,
                JdbcOrganizationStore.STATE_ACTIVE,
                JdbcOrganizationStore.STATE_ACTIVE,
                subjectId,
                organizationId.trim(),
                JdbcOrganizationStore.STATE_ACTIVE);
        return count != null && count > 0;
    }
}
