package com.subjex.platform.app.web;

import com.subjex.platform.app.capability.CapabilityRejected;
import com.subjex.platform.app.uiauthz.UiPermissionRejected;
import com.subjex.platform.app.declaration.DeclarationAlreadyPromoted;
import com.subjex.platform.app.declaration.DeclarationMigrationApplyFailed;
import com.subjex.platform.app.declaration.DeclarationMigrationNotReady;
import com.subjex.platform.app.declaration.DeclarationPromoteBlockedByMigration;
import com.subjex.platform.app.declaration.DeclarationPromoteNeedsSecondOperator;
import com.subjex.platform.app.declaration.DeclarationRollbackUnavailable;
import com.subjex.platform.app.declaration.DeclarationOverlayConflict;
import com.subjex.platform.app.form.FormProblemDocument;
import com.subjex.platform.app.form.FormValidationException;
import com.subjex.platform.app.storage.ObjectDownloadDeniedException;
import com.subjex.platform.app.storage.ObjectUploadRejectedException;
import com.subjex.platform.app.storage.ObjectDownloadInvalidException;
import com.subjex.platform.app.security.AccessDecision;
import com.subjex.platform.app.security.AccessDecisionDeniedException;
import com.subjex.platform.app.security.AccessResource;
import com.subjex.platform.app.security.DeclarationPermissionDeniedException;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantNotGrantedException;
import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.app.identity.enduser.EndUserPhoneAuthService;
import com.subjex.platform.app.identity.enduser.EndUserTenantAccessDeniedException;
import com.subjex.platform.app.task.IdempotencyConflict;
import com.subjex.platform.app.task.RateLimitExceeded;
import com.subjex.platform.contract.questionnaire.QuestionnaireRateLimited;
import com.subjex.platform.app.tenant.TenantQuotaExceeded;
import com.subjex.platform.app.workflow.IllegalWorkflowTransitionException;
import com.subjex.platform.app.workflow.WorkflowAlreadyStartedException;
import com.subjex.platform.app.workflow.WorkflowApproverOutOfScope;
import com.subjex.platform.app.workflow.WorkflowNotFoundException;
import com.subjex.platform.app.workflow.WorkflowNeedsSecondApprover;
import com.subjex.platform.app.task.SubmitLockHeld;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.consent.ConsentCrossTenantDeniedException;
import com.subjex.platform.contract.consent.ConsentRequiredException;
import com.subjex.platform.contract.exporthook.ExportArtifactUnavailableException;
import com.subjex.platform.contract.exporthook.ExportCrossTenantDeniedException;
import com.subjex.platform.contract.exporthook.ExportExpiredException;
import com.subjex.platform.contract.exporthook.ExportNotApprovedException;
import com.subjex.platform.contract.exporthook.ExportSelfApprovalException;
import com.subjex.platform.app.notification.NotificationSendCrossTenantDeniedException;
import com.subjex.platform.app.notification.NotificationSendNotFoundException;
import com.subjex.platform.contract.payment.PaymentCrossTenantDeniedException;
import com.subjex.platform.contract.payment.PaymentNotFoundException;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * PlatformExceptionAdvice — 平台异常对应：把门禁和任务规则变成 HTTP 状态，不把库错误写回调用方。
 * <p>
 * Form submit validation and explainable access denials return structured JSON for the console
 * debug panel. Other IllegalArgumentException / AccessDeniedException keep the previous shapes.
 * 表单校验与可解释访问拒绝返回结构化 JSON，供控制台调试面板；其它非法参数 / 拒绝保持原形状。
 */
@RestControllerAdvice
public class PlatformExceptionAdvice {

    private final OperatorActionAudit audit;

    public PlatformExceptionAdvice(ObjectProvider<OperatorActionAudit> audit) {
        this.audit = audit == null ? null : audit.getIfAvailable();
    }

    @ExceptionHandler({TenantMissingException.class, OperatorTenantNotGrantedException.class, TenantDisabledException.class})
    ResponseEntity<Void> missingTenantOrGrant() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @ExceptionHandler(TenantNotFoundException.class)
    ResponseEntity<Map<String, String>> unknownTenant(TenantNotFoundException ex) {
        // WP1-3: missing catalog row on submit — 403 with a stable reason, no auto-create.
        // WP1-3：提交时目录无此租户——403 且带稳定原因，不自动建户。
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("reason", ex.reason()));
    }

    @ExceptionHandler(EndUserPhoneAuthService.EndUserAuthenticationRequiredException.class)
    ResponseEntity<Map<String, String>> endUserAuthenticationRequired() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("reason", EndUserPhoneAuthService.EndUserAuthenticationRequiredException.REASON));
    }

    @ExceptionHandler(EndUserTenantAccessDeniedException.class)
    ResponseEntity<Map<String, String>> endUserTenantDenied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("reason", EndUserTenantAccessDeniedException.REASON));
    }

    @ExceptionHandler(ConsentRequiredException.class)
    ResponseEntity<Map<String, String>> consentRequired() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("reason", ConsentRequiredException.REASON));
    }

    @ExceptionHandler(ConsentCrossTenantDeniedException.class)
    ResponseEntity<Map<String, String>> consentCrossTenant() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("reason", ConsentCrossTenantDeniedException.REASON));
    }

    @ExceptionHandler(ExportNotApprovedException.class)
    ResponseEntity<Map<String, String>> exportNotApproved() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("reason", ExportNotApprovedException.REASON));
    }

    @ExceptionHandler(ExportSelfApprovalException.class)
    ResponseEntity<Map<String, String>> exportSelfApproval() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("reason", ExportSelfApprovalException.REASON));
    }

    @ExceptionHandler(ExportCrossTenantDeniedException.class)
    ResponseEntity<Map<String, String>> exportCrossTenant() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("reason", ExportCrossTenantDeniedException.REASON));
    }

    @ExceptionHandler(ExportExpiredException.class)
    ResponseEntity<Map<String, String>> exportExpired() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("reason", ExportExpiredException.REASON));
    }

    @ExceptionHandler(ExportArtifactUnavailableException.class)
    ResponseEntity<Map<String, String>> exportArtifactUnavailable() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("reason", ExportArtifactUnavailableException.REASON));
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    ResponseEntity<Map<String, String>> paymentMissing() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("reason", "payment-not-found"));
    }

    @ExceptionHandler(PaymentCrossTenantDeniedException.class)
    ResponseEntity<Map<String, String>> paymentCrossTenant() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("reason", "payment-cross-tenant-denied"));
    }

    @ExceptionHandler(NotificationSendNotFoundException.class)
    ResponseEntity<Map<String, String>> notificationSendMissing() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("reason", NotificationSendNotFoundException.REASON));
    }

    @ExceptionHandler(NotificationSendCrossTenantDeniedException.class)
    ResponseEntity<Map<String, String>> notificationSendCrossTenant() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("reason", NotificationSendCrossTenantDeniedException.REASON));
    }

    @ExceptionHandler(ObjectUploadRejectedException.class)
    ResponseEntity<Map<String, String>> objectUploadRejected(ObjectUploadRejectedException ex) {
        return ResponseEntity.status(ex.status()).body(Map.of("reason", ex.reason()));
    }

    @ExceptionHandler({RateLimitExceeded.class, QuestionnaireRateLimited.class})
    ResponseEntity<Void> rateLimited() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
    }

    @ExceptionHandler(TenantQuotaExceeded.class)
    ResponseEntity<Map<String, String>> tenantQuota(TenantQuotaExceeded ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("reason", ex.reason()));
    }

    @ExceptionHandler({IllegalWorkflowTransitionException.class, WorkflowAlreadyStartedException.class,
        WorkflowNeedsSecondApprover.class})
    ResponseEntity<Map<String, String>> workflowConflict(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("reason", ex.getMessage()));
    }

    @ExceptionHandler(WorkflowNotFoundException.class)
    ResponseEntity<Map<String, String>> workflowMissing(WorkflowNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("reason", ex.getMessage()));
    }

    @ExceptionHandler({IdempotencyConflict.class, SubmitLockHeld.class})
    ResponseEntity<Void> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }


    @ExceptionHandler(DeclarationOverlayConflict.class)
    ResponseEntity<Map<String, String>> declarationOverlayConflict(DeclarationOverlayConflict ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("reason", ex.getMessage()));
    }

    @ExceptionHandler(DeclarationAlreadyPromoted.class)
    ResponseEntity<Map<String, String>> declarationAlreadyPromoted(DeclarationAlreadyPromoted ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("reason", ex.getMessage()));
    }

    @ExceptionHandler(DeclarationPromoteNeedsSecondOperator.class)
    ResponseEntity<Map<String, String>> promoteNeedsSecond(DeclarationPromoteNeedsSecondOperator ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("reason", ex.getMessage()));
    }

    @ExceptionHandler(DeclarationRollbackUnavailable.class)
    ResponseEntity<Map<String, String>> rollbackUnavailable(DeclarationRollbackUnavailable ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("reason", ex.getMessage()));
    }

    @ExceptionHandler({DeclarationMigrationNotReady.class, DeclarationPromoteBlockedByMigration.class})
    ResponseEntity<Map<String, String>> declarationMigrationConflict(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("reason", ex.getMessage()));
    }

    @ExceptionHandler(DeclarationMigrationApplyFailed.class)
    ResponseEntity<Map<String, String>> declarationMigrationApplyFailed(DeclarationMigrationApplyFailed ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("reason", ex.getMessage()));
    }

    @ExceptionHandler(FormValidationException.class)
    ResponseEntity<FormProblemDocument> formValidation(FormValidationException ex) {
        return ResponseEntity.badRequest()
                .body(new FormProblemDocument("validation", ex.fieldErrors(), null, ex.getMessage()));
    }

    @ExceptionHandler(AccessDecisionDeniedException.class)
    ResponseEntity<FormProblemDocument> accessDecisionDenied(AccessDecisionDeniedException ex) {
        AccessDecision decision = ex.decision();
        recordAccessRefuse(decision);
        AccessResource resource = decision.resource();
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new FormProblemDocument(
                        "permission_denied",
                        List.of(),
                        ex.requiredPermission(),
                        ex.getMessage(),
                        decision.subjectId(),
                        decision.tenantId(),
                        decision.orgScope(),
                        resource == null ? null : resource.kind(),
                        resource == null ? null : resource.id(),
                        decision.action() == null ? null : decision.action().name(),
                        decision.matchedPermission(),
                        decision.allowed(),
                        decision.denyReason()));
    }

    /** Legacy declaration permission deny (pre-AX-1) — 旧声明权限拒绝（AX-1 前）。 */
    @ExceptionHandler(DeclarationPermissionDeniedException.class)
    ResponseEntity<FormProblemDocument> declarationPermissionDenied(DeclarationPermissionDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new FormProblemDocument(
                        "permission_denied", List.of(), ex.permission(), ex.getMessage()));
    }

    @ExceptionHandler(CapabilityRejected.class)
    ResponseEntity<Map<String, String>> capabilityRejected(CapabilityRejected ex) {
        return ResponseEntity.badRequest().body(Map.of("reason", ex.getMessage()));
    }

    @ExceptionHandler(UiPermissionRejected.class)
    ResponseEntity<Map<String, String>> uiPermissionRejected(UiPermissionRejected ex) {
        return ResponseEntity.badRequest().body(Map.of("reason", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> rejected(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("reason", ex.getMessage()));
    }

    @ExceptionHandler(WorkflowApproverOutOfScope.class)
    ResponseEntity<Map<String, String>> workflowApproverOutOfScope(WorkflowApproverOutOfScope ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("reason", ex.getMessage()));
    }

    @ExceptionHandler(ObjectDownloadDeniedException.class)
    ResponseEntity<Map<String, String>> objectDownloadDenied() {
        // Generic denial — do not reveal whether the object exists.
        // 通用拒绝，不向未授权调用方泄露对象是否存在。
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("reason", ObjectDownloadDeniedException.REASON));
    }

    @ExceptionHandler(ObjectDownloadInvalidException.class)
    ResponseEntity<Map<String, String>> objectDownloadInvalid() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("reason", ObjectDownloadInvalidException.REASON));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Void> accessDenied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    private void recordAccessRefuse(AccessDecision decision) {
        if (audit == null || decision == null) {
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof OperatorPrincipal operator)) {
            return;
        }
        AccessResource resource = decision.resource();
        String kind = resource == null ? "" : resource.kind();
        String id = resource == null ? "" : resource.id();
        String reason = decision.denyReason() == null ? "denied" : decision.denyReason();
        String target = kind + ":" + id + ":" + reason;
        audit.record(operator, "access.deny", target, AuditOutcome.REFUSED);
    }
}
