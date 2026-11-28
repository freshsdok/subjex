package com.subjex.platform.app.storage;

import com.subjex.platform.app.identity.enduser.EndUserAccountStore;
import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.OrganizationScope;
import com.subjex.platform.app.security.PolicyContext;
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.security.PolicyPrincipal;
import com.subjex.platform.app.security.PolicyResource;
import com.subjex.platform.contract.identity.Identity;
import com.subjex.platform.contract.identity.IdentityState;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

/**
 * ObjectGrantManagementService — list, grant, and revoke on {@link ObjectGrantStore}.
 * 对象授权管理：在已有授权存储上列出、授予与撤销。
 * <p>
 * End users may manage grants only on objects they own. Operators need a tenant grant plus
 * {@code object.grant.read} / {@code object.grant}. Reserved {@code platform} is never a home.
 * 终端用户只能管理本人拥有的对象。操作员须有租户授权及相应权限。保留租户 platform 绝不是归属。
 */
public final class ObjectGrantManagementService {

    /** Stable 404 reason — 稳定的未找到原因. */
    public static final String NOT_FOUND_REASON = "object-grant-not-found";

    private final ObjectGrantStore grants;
    private final JdbcTemplate jdbc;
    private final EndUserAccountStore accounts;
    private final OperatorTenantAccess tenantAccess;
    private final PolicyEngine policyEngine;
    private final Clock clock;

    public ObjectGrantManagementService(
            ObjectGrantStore grants,
            JdbcTemplate jdbc,
            EndUserAccountStore accounts,
            OperatorTenantAccess tenantAccess,
            PolicyEngine policyEngine,
            Clock clock) {
        this.grants = Objects.requireNonNull(grants, "grants");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public List<ObjectListItem> listRegistered(OperatorPrincipal operator, String tenantId, int limit, int offset) {
        String tid = requireOperator(operator, tenantId, OperatorPermission.OBJECT_GRANT_READ, "read");
        return grants.listRegistered(tid, limit, offset).stream()
                .map(descriptor -> ObjectListItem.from(descriptor))
                .toList();
    }

    public GrantList listForOperator(OperatorPrincipal operator, String tenantId, String objectId) {
        String tid = requireOperator(operator, tenantId, OperatorPermission.OBJECT_GRANT_READ, "read");
        ObjectDescriptor descriptor = requireObjectInTenant(tid, objectId);
        return GrantList.from(descriptor, grants.listGrants(tid, descriptor.objectKey()), clock.instant());
    }

    public GrantItem grantForOperator(
            OperatorPrincipal operator,
            String tenantId,
            String objectId,
            String granteeKind,
            String granteeId,
            Instant expiresAt) {
        String tid = requireOperator(operator, tenantId, OperatorPermission.OBJECT_GRANT, "write");
        ObjectDescriptor descriptor = requireObjectInTenant(tid, objectId);
        ObjectGrant row = grant(descriptor, granteeKind, granteeId, expiresAt, operator.subjectId());
        return GrantItem.from(descriptor.objectId(), row, clock.instant());
    }

    public void revokeForOperator(OperatorPrincipal operator, String tenantId, String objectId, String grantId) {
        String tid = requireOperator(operator, tenantId, OperatorPermission.OBJECT_GRANT, "write");
        ObjectDescriptor descriptor = requireObjectInTenant(tid, objectId);
        revokeOn(descriptor, grantId, operator.subjectId());
    }

    public GrantList listForEndUser(EndUserPrincipal principal, String tenantId, String objectId) {
        Identity identity = requireEndUser(principal, tenantId);
        ObjectDescriptor descriptor = requireOwned(identity, objectId);
        return GrantList.from(descriptor, grants.listGrants(descriptor.tenantId(), descriptor.objectKey()), clock.instant());
    }

    public GrantItem grantForEndUser(
            EndUserPrincipal principal,
            String tenantId,
            String objectId,
            String granteeKind,
            String granteeId,
            Instant expiresAt) {
        Identity identity = requireEndUser(principal, tenantId);
        ObjectDescriptor descriptor = requireOwned(identity, objectId);
        ObjectGrant row = grant(descriptor, granteeKind, granteeId, expiresAt, identity.subjectId());
        return GrantItem.from(descriptor.objectId(), row, clock.instant());
    }

    public void revokeForEndUser(EndUserPrincipal principal, String tenantId, String objectId, String grantId) {
        Identity identity = requireEndUser(principal, tenantId);
        ObjectDescriptor descriptor = requireOwned(identity, objectId);
        revokeOn(descriptor, grantId, identity.subjectId());
    }

    private ObjectGrant grant(
            ObjectDescriptor descriptor,
            String granteeKind,
            String granteeId,
            Instant expiresAt,
            String actorSubjectId) {
        ObjectGrantKind kind = parseKind(granteeKind);
        String id = requireToken(granteeId, "granteeId");
        Instant expiry = requireFutureOrNull(expiresAt);
        if (kind == ObjectGrantKind.SUBJECT) {
            requireSubjectInTenant(descriptor.tenantId(), id);
            return grants.grantToSubject(descriptor.tenantId(), descriptor.objectKey(), id, expiry, actorSubjectId);
        }
        return grants.grantToOrganization(
                descriptor.tenantId(), descriptor.objectKey(), id, expiry, actorSubjectId);
    }

    private void revokeOn(ObjectDescriptor descriptor, String grantId, String actorSubjectId) {
        String id = requireToken(grantId, "grantId");
        ObjectGrant row = grants.findGrant(id).orElseThrow(ObjectGrantManagementService::notFound);
        if (!descriptor.tenantId().equals(row.tenantId()) || !descriptor.objectKey().equals(row.objectKey())) {
            throw notFound();
        }
        if (grants instanceof JdbcObjectGrantStore jdbcStore) {
            jdbcStore.revoke(id, actorSubjectId);
        } else {
            grants.revoke(id);
        }
    }

    private String requireOperator(
            OperatorPrincipal operator, String tenantId, OperatorPermission permission, String action) {
        if (operator == null) {
            throw new AccessDeniedException("operator-authentication-required");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        String tid = tenantId.trim();
        if (ObjectGrantStore.RESERVED_TENANT.equals(tid)) {
            throw new AccessDeniedException("reserved-tenant");
        }
        tenantAccess.requireGranted(operator, tid);
        policyEngine.require(
                PolicyPrincipal.from(operator),
                permission.permissionName(),
                AccessAction.of(action),
                PolicyResource.of("object-grant", "_").withAttribute(PolicyResource.ATTR_TENANT_ID, tid),
                PolicyContext.of(tid, false, OrganizationScope.unrestricted()));
        return tid;
    }

    private Identity requireEndUser(EndUserPrincipal principal, String tenantId) {
        if (principal == null || !principal.active()) {
            throw new AccessDeniedException("end-user-authentication-required");
        }
        return accounts.requireActiveMembership(principal.accountId(), tenantId);
    }

    private ObjectDescriptor requireObjectInTenant(String tenantId, String objectId) {
        String id = requireToken(objectId, "objectId");
        ObjectDescriptor descriptor = grants.findByObjectId(id).orElseThrow(ObjectGrantManagementService::notFound);
        if (!tenantId.equals(descriptor.tenantId())) {
            throw notFound();
        }
        return descriptor;
    }

    private ObjectDescriptor requireOwned(Identity identity, String objectId) {
        ObjectDescriptor descriptor = requireObjectInTenant(identity.tenantId(), objectId);
        if (!identity.subjectId().equals(descriptor.ownerSubjectId())) {
            throw new AccessDeniedException("object-grant-owner-required");
        }
        return descriptor;
    }

    private void requireSubjectInTenant(String tenantId, String subjectId) {
        List<String> states = jdbc.query(
                """
                SELECT identity_state
                  FROM subject_identity
                 WHERE subject_id = ? AND tenant_id = ?
                """,
                (row, n) -> row.getString("identity_state"),
                subjectId,
                tenantId);
        boolean active = states.stream()
                .anyMatch(state -> IdentityState.ACTIVE.name().equals(state == null ? "" : state.trim()));
        if (!active) {
            throw new IllegalArgumentException("grantee subject is not in tenant");
        }
    }

    private Instant requireFutureOrNull(Instant expiresAt) {
        if (expiresAt == null) {
            return null;
        }
        if (!expiresAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
        return expiresAt;
    }

    private static ObjectGrantKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("granteeKind required");
        }
        try {
            return ObjectGrantKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown granteeKind");
        }
    }

    private static String requireToken(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " required");
        }
        return value.trim();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_REASON);
    }

    /** Registered object row — 已登记对象行. */
    public record ObjectListItem(
            String objectId, String objectKey, String ownerSubjectId, Instant createdAt) {
        static ObjectListItem from(ObjectDescriptor descriptor) {
            return new ObjectListItem(
                    descriptor.objectId(),
                    descriptor.objectKey(),
                    descriptor.ownerSubjectId(),
                    descriptor.createdAt());
        }
    }

    /** Grants on one object, including expired rows — 一个对象上的授权（含已过期）. */
    public record GrantList(ObjectListItem object, List<GrantItem> grants) {
        static GrantList from(ObjectDescriptor descriptor, List<ObjectGrant> rows, Instant now) {
            return new GrantList(
                    ObjectListItem.from(descriptor),
                    rows.stream().map(row -> GrantItem.from(descriptor.objectId(), row, now)).toList());
        }
    }

    /** One grant row — 一条授权. */
    public record GrantItem(
            String grantId,
            String objectId,
            String objectKey,
            String tenantId,
            String granteeKind,
            String granteeId,
            Instant expiresAt,
            Instant createdAt,
            String createdBySubjectId,
            boolean effective) {
        static GrantItem from(String objectId, ObjectGrant row, Instant now) {
            boolean effective = row.expiresAt() == null || row.expiresAt().isAfter(now);
            return new GrantItem(
                    row.grantId(),
                    objectId,
                    row.objectKey(),
                    row.tenantId(),
                    row.granteeKind().name(),
                    row.granteeId(),
                    row.expiresAt(),
                    row.createdAt(),
                    row.createdBySubjectId(),
                    effective);
        }
    }
}
