package com.subjex.platform.app.exporthook;

import com.subjex.platform.app.identity.enduser.EndUserAccountStore;
import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.contract.identity.Identity;
import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.OrganizationScope;
import com.subjex.platform.app.security.PolicyContext;
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.security.PolicyPrincipal;
import com.subjex.platform.app.security.PolicyResource;
import com.subjex.platform.app.storage.IssuedDownloadLink;
import com.subjex.platform.app.storage.ObjectDownloadLinkService;
import com.subjex.platform.app.storage.ObjectGrantStore;
import com.subjex.platform.app.workflow.WorkflowAction;
import com.subjex.platform.app.workflow.WorkflowService;
import com.subjex.platform.contract.audit.AuditEntry;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.audit.AuditPort;
import com.subjex.platform.contract.exporthook.ExportArtifact;
import com.subjex.platform.contract.exporthook.ExportArtifactUnavailableException;
import com.subjex.platform.contract.exporthook.ExportCrossTenantDeniedException;
import com.subjex.platform.contract.exporthook.ExportExpiredException;
import com.subjex.platform.contract.exporthook.ExportRequest;
import com.subjex.platform.contract.exporthook.ExportRequestStatus;
import com.subjex.platform.contract.exporthook.SubjectRecordExporter;
import com.subjex.platform.contract.exporthook.SensitivityClass;
import com.subjex.platform.contract.storage.ObjectStorage;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ExportGenerationService — fail-closed generate after approval, store via WP3, issue signed links (WP8-3).
 * 批准后失败关闭地生成产物，经 WP3 存储，并签发短时签名链接。
 * <p>
 * Operator generate and operator download-link reuse {@code export.approve} (least new permission surface).
 * 操作员生成与下载链接复用 {@code export.approve}（最少新增权限面）。
 */
public final class ExportGenerationService {

    public static final String AUDIT_GENERATE = "export.generate";
    public static final String AUDIT_DOWNLOAD_LINK = "export.download-link";
    public static final Duration DEFAULT_ARTIFACT_TTL = Duration.ofDays(7);
    /** Reuses export.approve — 复用 export.approve. */
    public static final String OPERATOR_PERMISSION = OperatorPermission.EXPORT_APPROVE.permissionName();

    private final JdbcExportRequestStore store;
    private final ExportApprovalGate gate;
    private final ExportExpiryService expiry;
    private final List<SubjectRecordExporter> exporters;
    private final ObjectStorage storage;
    private final ObjectGrantStore grants;
    private final ObjectDownloadLinkService downloads;
    private final WorkflowService workflows;
    private final EndUserAccountStore accounts;
    private final OperatorTenantAccess tenantAccess;
    private final PolicyEngine policyEngine;
    private final AuditPort audit;
    private final Clock clock;
    private final Duration artifactTtl;
    private final TransactionTemplate transactionTemplate;

    public ExportGenerationService(
            JdbcExportRequestStore store,
            ExportApprovalGate gate,
            ExportExpiryService expiry,
            List<SubjectRecordExporter> exporters,
            ObjectStorage storage,
            ObjectGrantStore grants,
            ObjectDownloadLinkService downloads,
            WorkflowService workflows,
            EndUserAccountStore accounts,
            OperatorTenantAccess tenantAccess,
            PolicyEngine policyEngine,
            AuditPort audit,
            Clock clock,
            Duration artifactTtl,
            TransactionTemplate transactionTemplate) {
        this.store = Objects.requireNonNull(store, "store");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.expiry = Objects.requireNonNull(expiry, "expiry");
        this.exporters = List.copyOf(Objects.requireNonNull(exporters, "exporters"));
        this.storage = Objects.requireNonNull(storage, "storage");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.downloads = Objects.requireNonNull(downloads, "downloads");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.artifactTtl = artifactTtl == null || artifactTtl.isZero() || artifactTtl.isNegative()
                ? DEFAULT_ARTIFACT_TTL
                : artifactTtl;
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
    }

    /**
     * Require approved, run a supporting exporter, store the artifact, move to READY.
     * Synchronous in this slice. Idempotent when already READY and not expired.
     * 先要求已批准，再跑支持该范围的导出器，存储产物并转到 READY。本切片同步。已 READY 且未过期则幂等。
     */
    public ExportRequest generate(OperatorPrincipal operator, String tenantId, String requestId) {
        Objects.requireNonNull(operator, "operator");
        ExportRequest request = loadInTenant(tenantId, requestId);
        tenantAccess.requireGranted(operator, request.tenantId());
        requireApprovePermission(operator, "generate", request.tenantId(), request.requestId());
        request = expiry.expireIfNeeded(request);
        if (request.status() == ExportRequestStatus.EXPIRED) {
            throw new ExportExpiredException();
        }
        if (request.status() == ExportRequestStatus.READY) {
            return request;
        }
        gate.requireApproved(request.requestId());
        final ExportRequest approved = request;
        SubjectRecordExporter exporter = resolveExporter(approved);
        ExportArtifact artifact =
                exporter.export(approved.tenantId(), approved.subjectId(), approved.scopeDescriptor());
        String objectKey = "export-" + approved.requestId();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(artifactTtl);
        transactionTemplate.executeWithoutResult(status -> {
            storage.put(approved.tenantId(), objectKey, artifact.content(), artifact.contentType());
            grants.register(approved.tenantId(), objectKey, approved.subjectId());
            grants.grantToSubject(
                    approved.tenantId(), objectKey, approved.subjectId(), expiresAt, operator.subjectId());
            grants.setObjectSensitivity(approved.tenantId(), objectKey, SensitivityClass.SENSITIVE);
            workflows.transition(
                    operator, approved.workflowInstanceId(), WorkflowAction.fromWire("generate"), null);
            store.updateArtifact(new ExportRequest(
                    approved.requestId(),
                    approved.tenantId(),
                    approved.subjectId(),
                    approved.requesterRealm(),
                    approved.requesterId(),
                    approved.scopeDescriptor(),
                    ExportRequestStatus.READY,
                    approved.approverSubjectId(),
                    approved.workflowInstanceId(),
                    approved.createdAt(),
                    now,
                    approved.decidedAt(),
                    expiresAt,
                    objectKey));
        });
        audit.record(new AuditEntry(
                UUID.randomUUID().toString(),
                approved.tenantId(),
                operator.identityId(),
                AUDIT_GENERATE,
                approved.requestId(),
                AuditOutcome.ALLOWED,
                now));
        return store.find(approved.requestId()).orElseThrow(ExportCrossTenantDeniedException::new);
    }

    /**
     * End-user short-lived signed link for their own READY export (delegates to WP3).
     * 终端用户为自己的 READY 导出签发短时签名链接（委托 WP3）。
     */
    public IssuedDownloadLink issueEndUserLink(
            EndUserPrincipal principal, String tenantId, String requestId, Duration requestedTtl) {
        Identity identity = accounts.requireActiveMembership(principal.accountId(), tenantId);
        ExportRequest request = prepareReady(loadInTenant(identity.tenantId(), requestId));
        if (!identity.subjectId().equals(request.subjectId())) {
            throw new ExportCrossTenantDeniedException();
        }
        IssuedDownloadLink link = downloads.issue(
                request.tenantId(), request.artifactObjectKey(), identity.subjectId(), requestedTtl);
        audit.record(new AuditEntry(
                UUID.randomUUID().toString(),
                request.tenantId(),
                principal.accountId(),
                AUDIT_DOWNLOAD_LINK,
                request.requestId(),
                AuditOutcome.ALLOWED,
                clock.instant()));
        return link;
    }

    /**
     * Operator link, only with {@code export.approve}; object access is evaluated as the subject (owner).
     * 操作员链接仅在持有 {@code export.approve} 时签发；对象访问按主体（所有者）判定。
     */
    public IssuedDownloadLink issueOperatorLink(
            OperatorPrincipal operator, String tenantId, String requestId, Duration requestedTtl) {
        Objects.requireNonNull(operator, "operator");
        ExportRequest request = prepareReady(loadInTenant(tenantId, requestId));
        tenantAccess.requireGranted(operator, request.tenantId());
        requireApprovePermission(operator, "download-link", request.tenantId(), request.requestId());
        IssuedDownloadLink link = downloads.issue(
                request.tenantId(), request.artifactObjectKey(), request.subjectId(), requestedTtl);
        audit.record(new AuditEntry(
                UUID.randomUUID().toString(),
                request.tenantId(),
                operator.identityId(),
                AUDIT_DOWNLOAD_LINK,
                request.requestId(),
                AuditOutcome.ALLOWED,
                clock.instant()));
        return link;
    }

    public ExportRequest expireIfNeeded(ExportRequest request) {
        return expiry.expireIfNeeded(request);
    }

    public int sweepExpired() {
        return expiry.sweep();
    }

    private ExportRequest prepareReady(ExportRequest request) {
        ExportRequest current = expiry.expireIfNeeded(request);
        if (current.status() == ExportRequestStatus.EXPIRED) {
            throw new ExportExpiredException();
        }
        if (current.status() != ExportRequestStatus.READY
                || current.artifactObjectKey() == null
                || current.artifactObjectKey().isBlank()) {
            throw new ExportArtifactUnavailableException();
        }
        return current;
    }

    private SubjectRecordExporter resolveExporter(ExportRequest request) {
        for (SubjectRecordExporter exporter : exporters) {
            if (exporter.supports(request.tenantId(), request.subjectId(), request.scopeDescriptor())) {
                return exporter;
            }
        }
        throw new IllegalStateException("no personal-data exporter supports this scope (fail closed)");
    }

    private ExportRequest loadInTenant(String tenantId, String requestId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
        ExportRequest request = store.find(requestId.trim()).orElseThrow(ExportCrossTenantDeniedException::new);
        if (!tenantId.trim().equals(request.tenantId())) {
            throw new ExportCrossTenantDeniedException();
        }
        return request;
    }

    private void requireApprovePermission(
            OperatorPrincipal operator, String action, String tenantId, String resourceId) {
        policyEngine.require(
                PolicyPrincipal.from(operator),
                OPERATOR_PERMISSION,
                AccessAction.of(action),
                PolicyResource.of("data-export", resourceId).withAttribute(PolicyResource.ATTR_TENANT_ID, tenantId),
                PolicyContext.of(tenantId, false, OrganizationScope.unrestricted()));
    }
}
