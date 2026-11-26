package com.subjex.platform.app.exporthook;

import com.subjex.platform.app.identity.enduser.EndUserAccountStore;
import com.subjex.platform.app.support.TenantKeyset;
import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.app.identity.enduser.EndUserTenantAccessDeniedException;
import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.OrganizationScope;
import com.subjex.platform.app.security.PolicyContext;
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.security.PolicyPrincipal;
import com.subjex.platform.app.security.PolicyResource;
import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.app.workflow.WorkflowAction;
import com.subjex.platform.app.workflow.WorkflowInstance;
import com.subjex.platform.app.workflow.WorkflowExportHook;
import com.subjex.platform.app.workflow.WorkflowService;
import com.subjex.platform.contract.audit.AuditEntry;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.audit.AuditPort;
import com.subjex.platform.contract.exporthook.ExportCrossTenantDeniedException;
import com.subjex.platform.contract.exporthook.ExportRequest;
import com.subjex.platform.contract.exporthook.ExportRequestStatus;
import com.subjex.platform.contract.exporthook.ExportRequesterRealm;
import com.subjex.platform.contract.exporthook.ExportSelfApprovalException;
import com.subjex.platform.contract.identity.Identity;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ExportRequestService — export request lifecycle and thin-workflow approval hook.
 * 导出申请生命周期与薄工作流审批钩子。批准后立即入队后台生成，不在请求线程写出产物。
 */
public final class ExportRequestService {

    /** Declared machine key — 已声明机器键. */
    public static final String WORKFLOW_KEY = "data-export";

    /** Entity key bound to the machine / record id = request id — 机器绑定的实体键，记录标识即申请标识. */
    public static final String ENTITY_KEY = "data-export";

    /** Scope meaning "all of this subject's own data" — 范围：该主体本人全部数据. */
    public static final String SCOPE_ALL_OWN = "ALL_OWN";

    public static final String AUDIT_REQUEST = "export.request";
    public static final String AUDIT_APPROVE = "export.approve";
    public static final String AUDIT_REJECT = "export.reject";

    public static final String RESERVED_TENANT = "platform";

    private static final Pattern ENTITY_KEY_PART = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final int MAX_SCOPE_LENGTH = 512;

    private final JdbcTemplate jdbc;
    private final JdbcExportRequestStore store;
    private final WorkflowService workflows;
    private final WorkflowExportHook exportHook;
    private final ExportExpiryService expiry;
    private final EndUserAccountStore accounts;
    private final OperatorTenantAccess tenantAccess;
    private final PolicyEngine policyEngine;
    private final AuditPort audit;
    private final Clock clock;

    public ExportRequestService(
            JdbcTemplate jdbc,
            JdbcExportRequestStore store,
            WorkflowService workflows,
            EndUserAccountStore accounts,
            OperatorTenantAccess tenantAccess,
            PolicyEngine policyEngine,
            AuditPort audit,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.store = Objects.requireNonNull(store, "store");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.exportHook = new WorkflowExportHook(workflows);
        this.expiry = new ExportExpiryService(store, clock, this.exportHook);
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ExportRequest requestAsEndUser(EndUserPrincipal principal, String tenantId, String scopeDescriptor) {
        Identity identity = accounts.requireActiveMembership(principal.accountId(), tenantId);
        if (!principal.subjectId().equals(identity.subjectId())) {
            throw new EndUserTenantAccessDeniedException();
        }
        return open(
                identity.tenantId(),
                identity.subjectId(),
                ExportRequesterRealm.END_USER,
                identity.subjectId(),
                identity.identityId(),
                scopeDescriptor);
    }

    public ExportRequest requestAsOperator(OperatorPrincipal operator, String tenantId, String subjectId, String scopeDescriptor) {
        String tid = requireActiveBusinessTenant(tenantId);
        tenantAccess.requireGranted(operator, tid);
        requirePermission(operator, OperatorPermission.EXPORT_REQUEST.permissionName(), "request", tid, "_");
        String sid = requireSubjectInTenant(tid, subjectId);
        return open(
                tid,
                sid,
                ExportRequesterRealm.OPERATOR,
                operator.subjectId(),
                operator.identityId(),
                scopeDescriptor);
    }

    /**
     * Own requests, newest first, tenant-scoped keyset page.
     * 本人申请，从新到旧，按租户键集分页。
     */
    public ExportRequestPage listOwn(
            EndUserPrincipal principal, String tenantId, String cursor, Integer limit) {
        Identity identity = accounts.requireActiveMembership(principal.accountId(), tenantId);
        return pageRequests(
                store.listBySubject(
                        identity.tenantId(),
                        identity.subjectId(),
                        afterTime(cursor),
                        afterId(cursor),
                        TenantKeyset.clamp(limit)),
                TenantKeyset.clamp(limit));
    }

    public ExportRequest getOwn(EndUserPrincipal principal, String tenantId, String requestId) {
        Identity identity = accounts.requireActiveMembership(principal.accountId(), tenantId);
        ExportRequest request = loadInTenant(identity.tenantId(), requestId);
        if (!identity.subjectId().equals(request.subjectId())) {
            throw new ExportCrossTenantDeniedException();
        }
        return expiry.expireIfNeeded(request);
    }

    /**
     * Tenant-scoped operator list, newest first, keyset page.
     * 操作员按租户列出，从新到旧，键集分页。
     */
    public ExportRequestPage listTenant(
            OperatorPrincipal operator, String tenantId, String cursor, Integer limit) {
        String tid = requireActiveBusinessTenant(tenantId);
        tenantAccess.requireGranted(operator, tid);
        requireExportListPermission(operator, tid);
        int cap = TenantKeyset.clamp(limit);
        return pageRequests(
                store.listByTenant(tid, afterTime(cursor), afterId(cursor), cap), cap);
    }

    private ExportRequestPage pageRequests(List<ExportRequest> rows, int limit) {
        List<ExportRequest> items = rows.stream().map(expiry::expireIfNeeded).toList();
        String next = TenantKeyset.nextCursor(
                items, limit, row -> TenantKeyset.encode(row.createdAt(), row.requestId()));
        return new ExportRequestPage(items, next);
    }

    private static Instant afterTime(String cursor) {
        TenantKeyset.Cursor decoded = TenantKeyset.decode(cursor);
        return decoded == null ? null : decoded.instant();
    }

    private static String afterId(String cursor) {
        TenantKeyset.Cursor decoded = TenantKeyset.decode(cursor);
        return decoded == null ? null : decoded.id();
    }

    /** One keyset page of export requests — 导出申请的一页键集结果. */
    public record ExportRequestPage(List<ExportRequest> requests, String nextCursor) {}

    public ExportRequest getForOperator(OperatorPrincipal operator, String tenantId, String requestId) {
        String tid = requireActiveBusinessTenant(tenantId);
        tenantAccess.requireGranted(operator, tid);
        requireExportListPermission(operator, tid);
        return expiry.expireIfNeeded(loadInTenant(tid, requestId));
    }

    public ExportRequest approve(OperatorPrincipal operator, String tenantId, String requestId) {
        return decide(operator, tenantId, requestId, WorkflowAction.APPROVE, ExportRequestStatus.APPROVED, AUDIT_APPROVE);
    }

    public ExportRequest reject(OperatorPrincipal operator, String tenantId, String requestId) {
        return decide(operator, tenantId, requestId, WorkflowAction.REJECT, ExportRequestStatus.REJECTED, AUDIT_REJECT);
    }

    private ExportRequest decide(
            OperatorPrincipal operator,
            String tenantId,
            String requestId,
            WorkflowAction action,
            ExportRequestStatus next,
            String auditAction) {
        String tid = requireActiveBusinessTenant(tenantId);
        tenantAccess.requireGranted(operator, tid);
        requirePermission(operator, OperatorPermission.EXPORT_APPROVE.permissionName(), action.wireName(), tid, requestId);
        ExportRequest current = loadInTenant(tid, requestId);
        if (operator.subjectId().equals(current.requesterId())) {
            throw new ExportSelfApprovalException();
        }
        if (current.status() != ExportRequestStatus.REQUESTED) {
            throw new IllegalArgumentException("export request is not awaiting approval");
        }
        workflows.transition(operator, current.workflowInstanceId(), action, null);
        ExportRequestStatus stored = next;
        if (next == ExportRequestStatus.APPROVED) {
            // Enqueue immediately; do not generate the artifact on this request thread.
            // 立即入队；不在本请求线程生成产物。
            exportHook.enqueue(current.workflowInstanceId(), operator.subjectId());
            stored = ExportRequestStatus.GENERATING;
        }
        Instant now = clock.instant();
        ExportRequest updated = new ExportRequest(
                current.requestId(),
                current.tenantId(),
                current.subjectId(),
                current.requesterRealm(),
                current.requesterId(),
                current.scopeDescriptor(),
                stored,
                operator.subjectId(),
                current.workflowInstanceId(),
                current.createdAt(),
                now,
                now);
        store.updateDecision(updated);
        audit.record(new AuditEntry(
                UUID.randomUUID().toString(),
                tid,
                operator.identityId(),
                auditAction,
                updated.requestId(),
                AuditOutcome.ALLOWED,
                now));
        return updated;
    }

    private ExportRequest open(
            String tenantId,
            String subjectId,
            ExportRequesterRealm realm,
            String requesterId,
            String actorIdentityId,
            String scopeDescriptor) {
        String scope = requireScope(scopeDescriptor);
        Instant now = clock.instant();
        String requestId = UUID.randomUUID().toString();
        WorkflowInstance instance = exportHook.start(
                WORKFLOW_KEY, tenantId, ENTITY_KEY, requestId, requesterId);
        ExportRequest request = new ExportRequest(
                requestId,
                tenantId,
                subjectId,
                realm,
                requesterId,
                scope,
                ExportRequestStatus.REQUESTED,
                null,
                instance.instanceId(),
                now,
                now,
                null);
        store.insert(request);
        audit.record(new AuditEntry(
                UUID.randomUUID().toString(),
                tenantId,
                actorIdentityId,
                AUDIT_REQUEST,
                requestId,
                AuditOutcome.ALLOWED,
                now));
        return request;
    }

    private ExportRequest loadInTenant(String tenantId, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
        ExportRequest request = store.find(requestId.trim()).orElseThrow(ExportCrossTenantDeniedException::new);
        if (!tenantId.equals(request.tenantId())) {
            throw new ExportCrossTenantDeniedException();
        }
        return request;
    }

    private void requireExportListPermission(OperatorPrincipal operator, String tenantId) {
        boolean can = operator.permissionNames().contains(OperatorPermission.EXPORT_REQUEST.permissionName())
                || operator.permissionNames().contains(OperatorPermission.EXPORT_APPROVE.permissionName());
        if (!can) {
            requirePermission(operator, OperatorPermission.EXPORT_REQUEST.permissionName(), "list", tenantId, "_");
        }
    }

    private void requirePermission(
            OperatorPrincipal operator, String permission, String action, String tenantId, String resourceId) {
        policyEngine.require(
                PolicyPrincipal.from(operator),
                permission,
                AccessAction.of(action),
                PolicyResource.of("data-export", resourceId).withAttribute(PolicyResource.ATTR_TENANT_ID, tenantId),
                PolicyContext.of(tenantId, false, OrganizationScope.unrestricted()));
    }

    private String requireActiveBusinessTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        String id = tenantId.trim();
        if (RESERVED_TENANT.equals(id)) {
            throw new IllegalArgumentException("reserved tenant " + RESERVED_TENANT + " is not an export home");
        }
        List<String> states = jdbc.query(
                "SELECT tenant_state FROM tenant WHERE tenant_id = ?",
                (row, n) -> row.getString("tenant_state"),
                id);
        if (states.isEmpty()) {
            throw new TenantNotFoundException(id);
        }
        String state = states.get(0) == null ? "" : states.get(0).trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(state)) {
            throw new TenantDisabledException(id);
        }
        return id;
    }

    private String requireSubjectInTenant(String tenantId, String subjectId) {
        if (subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("subjectId is required");
        }
        String sid = subjectId.trim();
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM subject_identity
                WHERE tenant_id = ? AND subject_id = ? AND identity_state = 'ACTIVE'
                """,
                Integer.class,
                tenantId,
                sid);
        if (count == null || count == 0) {
            throw new ExportCrossTenantDeniedException();
        }
        return sid;
    }

    static String requireScope(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("scope is required");
        }
        String scope = raw.trim();
        if (scope.length() > MAX_SCOPE_LENGTH) {
            throw new IllegalArgumentException("scope must be at most " + MAX_SCOPE_LENGTH + " characters");
        }
        if (SCOPE_ALL_OWN.equals(scope)) {
            return scope;
        }
        String[] parts = scope.split(",");
        if (parts.length == 0) {
            throw new IllegalArgumentException("scope must be ALL_OWN or a list of entity keys");
        }
        StringBuilder normalized = new StringBuilder();
        for (String part : parts) {
            String key = part.trim();
            if (!ENTITY_KEY_PART.matcher(key).matches()) {
                throw new IllegalArgumentException("scope must be ALL_OWN or a list of entity keys");
            }
            if (normalized.length() > 0) {
                normalized.append(',');
            }
            normalized.append(key);
        }
        return normalized.toString();
    }
}
