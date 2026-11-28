package com.subjex.platform.app.schedule;

import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.OrganizationScope;
import com.subjex.platform.app.security.PolicyContext;
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.security.PolicyPrincipal;
import com.subjex.platform.app.security.PolicyResource;
import com.subjex.platform.app.workflow.WorkflowCatalog;
import com.subjex.platform.app.workflow.WorkflowDefinition;
import com.subjex.platform.contract.schedule.CreateScheduleCommand;
import com.subjex.platform.contract.schedule.ScheduleActionType;
import com.subjex.platform.contract.schedule.ScheduleNotFoundException;
import com.subjex.platform.contract.schedule.SchedulePort;
import com.subjex.platform.contract.schedule.ScheduleRecord;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;

/**
 * OperatorScheduleService — create, list, enable, and disable schedules in a granted tenant.
 * 操作员在已授权租户内创建、列出、启用或停用调度。
 */
public final class OperatorScheduleService {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 200;
    static final int MAX_NAME = 200;
    static final int MAX_STEP = 128;
    static final int MAX_RECORD = 96;

    private final SchedulePort schedules;
    private final WorkflowCatalog workflows;
    private final OperatorTenantAccess tenantAccess;
    private final PolicyEngine policyEngine;
    private final Clock clock;

    public OperatorScheduleService(
            SchedulePort schedules,
            WorkflowCatalog workflows,
            OperatorTenantAccess tenantAccess,
            PolicyEngine policyEngine,
            Clock clock) {
        this.schedules = Objects.requireNonNull(schedules, "schedules");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<ScheduleRecord> list(OperatorPrincipal operator, String tenantId, Integer limit) {
        String tid = requireOperatorAccess(operator, tenantId, OperatorPermission.SCHEDULE_READ, "read");
        return schedules.list(tid, clampLimit(limit));
    }

    public ScheduleRecord create(
            OperatorPrincipal operator, String tenantId, SaveScheduleRequest request) {
        String tid = requireOperatorAccess(operator, tenantId, OperatorPermission.SCHEDULE_WRITE, "write");
        SaveScheduleRequest body = request == null ? new SaveScheduleRequest(null, null, null, null, null, null, null) : request;
        String name = requireLength(trimToNull(body.name()), "name", MAX_NAME);
        String cron = ScheduleCron.requireValid(body.cronExpression()).toString();
        // Re-parse the original text so we store what the operator typed, after validation.
        // 校验后仍保存操作员填写的原文。
        cron = body.cronExpression().trim();
        ScheduleCron.requireValid(cron);
        ScheduleActionType action = parseAction(body.action());
        String stepName = null;
        String definitionKey = null;
        String entityKey = null;
        String recordId = null;
        if (action == ScheduleActionType.ENQUEUE_TASK) {
            stepName = requireLength(trimToNull(body.taskStepName()), "taskStepName", MAX_STEP);
        } else if (action == ScheduleActionType.START_WORKFLOW) {
            definitionKey = requireLength(trimToNull(body.workflowDefinitionKey()), "workflowDefinitionKey", MAX_STEP);
            recordId = requireLength(trimToNull(body.workflowRecordId()), "workflowRecordId", MAX_RECORD);
            entityKey = requireClasspathWorkflow(definitionKey);
        }
        boolean enabled = body.enabled() == null || body.enabled();
        Instant now = clock.instant();
        Instant next = enabled ? ScheduleCron.nextRunAfter(cron, now) : null;
        return schedules.create(
                new CreateScheduleCommand(
                        tid,
                        name,
                        cron,
                        enabled,
                        action,
                        stepName,
                        definitionKey,
                        recordId,
                        operator.subjectId(),
                        operator.identityId()),
                entityKey,
                next);
    }

    public ScheduleRecord enable(OperatorPrincipal operator, String tenantId, String scheduleId) {
        String tid = requireOperatorAccess(operator, tenantId, OperatorPermission.SCHEDULE_WRITE, "write");
        ScheduleRecord current = schedules
                .find(tid, scheduleId)
                .orElseThrow(() -> new ScheduleNotFoundException(scheduleId));
        Instant now = clock.instant();
        Instant next = ScheduleCron.nextRunAfter(current.cronExpression(), now);
        return schedules.enable(tid, current.scheduleId(), next, now);
    }

    public ScheduleRecord disable(OperatorPrincipal operator, String tenantId, String scheduleId) {
        String tid = requireOperatorAccess(operator, tenantId, OperatorPermission.SCHEDULE_WRITE, "write");
        ScheduleRecord current = schedules
                .find(tid, scheduleId)
                .orElseThrow(() -> new ScheduleNotFoundException(scheduleId));
        return schedules.disable(tid, current.scheduleId(), clock.instant());
    }

    private String requireClasspathWorkflow(String definitionKey) {
        WorkflowDefinition definition;
        try {
            definition = workflows.require(definitionKey);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown workflow definition: " + definitionKey);
        }
        if (definition.entityKey() == null || definition.entityKey().isBlank()) {
            throw new IllegalArgumentException("workflow definition has no entity key: " + definitionKey);
        }
        return definition.entityKey();
    }

    private String requireOperatorAccess(
            OperatorPrincipal operator, String tenantId, OperatorPermission permission, String action) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        String tid = tenantId.trim();
        if (JdbcSchedulePort.RESERVED_TENANT.equals(tid)) {
            throw new AccessDeniedException("reserved-tenant");
        }
        tenantAccess.requireGranted(operator, tid);
        policyEngine.require(
                PolicyPrincipal.from(operator),
                permission.permissionName(),
                AccessAction.of(action),
                PolicyResource.of("schedule", "_").withAttribute(PolicyResource.ATTR_TENANT_ID, tid),
                PolicyContext.of(tid, false, OrganizationScope.unrestricted()));
        return tid;
    }

    private static ScheduleActionType parseAction(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("action is required");
        }
        try {
            return ScheduleActionType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown schedule action: " + raw.trim());
        }
    }

    static int clampLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String requireLength(String value, String name, int max) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return value;
    }
}
