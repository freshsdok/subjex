package com.subjex.platform.app.web;

import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.task.RateLimitExceeded;
import com.subjex.platform.app.tenant.TenantQuotaService;
import com.subjex.platform.contract.ratelimit.RateLimitPort;
import com.subjex.platform.contract.task.TaskCommand;
import com.subjex.platform.contract.task.TaskMessagePort;
import com.subjex.platform.contract.task.TaskRecord;
import com.subjex.platform.contract.tenant.TenantGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * TaskSubmissionEndpoint — 任务提交端点：租户作用域里进入唯一任务端口的 HTTP 面。
 * <p>
 * Applies tenant quota hard caps (P5) before rate-limit on submit.
 * 提交时在限流前先过租户配额硬顶（P5）。
 */
@RestController
public class TaskSubmissionEndpoint {

    private final TenantGuard tenantGuard;
    private final RateLimitPort rateLimitPort;
    private final TaskMessagePort taskMessagePort;
    private final TenantQuotaService tenantQuota;

    public TaskSubmissionEndpoint(
            TenantGuard tenantGuard,
            RateLimitPort rateLimitPort,
            TaskMessagePort taskMessagePort,
            TenantQuotaService tenantQuota) {
        this.tenantGuard = tenantGuard;
        this.rateLimitPort = rateLimitPort;
        this.taskMessagePort = taskMessagePort;
        this.tenantQuota = tenantQuota;
    }

    @PostMapping("/tasks")
    public ResponseEntity<TaskRecord> submit(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId,
            @RequestHeader(value = "X-Idempotency-Token", required = false) String idempotencyToken,
            @RequestBody TaskSubmission submission) {
        tenantGuard.requireTenant(tenantId);
        if (idempotencyToken == null || idempotencyToken.isBlank()) {
            throw new IllegalArgumentException("idempotency token is missing");
        }
        if (operator != null) {
            tenantQuota.requireWithinQuota(operator, tenantId);
        }
        permit(tenantId, "submit-task");
        TaskRecord task = taskMessagePort.submit(new TaskCommand(
                tenantId,
                idempotencyToken,
                submission.actorIdentityId(),
                submission.taskKind(),
                submission.stepName(),
                submission.stepSucceeded(),
                submission.modelId(),
                submission.inputDigest(),
                submission.humanConfirmation()));
        return ResponseEntity.status(HttpStatus.CREATED).body(task);
    }

    @PostMapping("/tasks/{taskId}/attempts")
    public TaskRecord retry(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId,
            @PathVariable String taskId,
            @RequestBody StepAttempt attempt) {
        tenantGuard.requireTenant(tenantId);
        permit(tenantId, "retry-task");
        return taskMessagePort.retry(tenantId, taskId, attempt.stepSucceeded());
    }

    @PostMapping("/tasks/{taskId}/confirmation")
    public TaskRecord confirm(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId,
            @PathVariable String taskId) {
        tenantGuard.requireTenant(tenantId);
        permit(tenantId, "confirm-task");
        return taskMessagePort.confirm(tenantId, taskId);
    }

    private void permit(String tenantId, String actionName) {
        if (!rateLimitPort.permit(tenantId, actionName)) {
            throw new RateLimitExceeded();
        }
    }
}
