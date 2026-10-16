package com.subjex.platform.app.workflow;

import com.subjex.platform.app.capability.CapabilityCatalog;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * WorkflowService — start / transition / status for thin FSM (WF-1).
 * 薄状态机的启动 / 流转 / 状态查询（WF-1）。
 * <p>
 * Tenant grant via {@link OperatorTenantAccess#requireGranted}; action permission from definition;
 * optional Cap id on a transition is catalog-checked only (not executed).
 * WF-2: {@code request_approval} opens PENDING; {@code approve}/{@code reject} require a second operator.
 * 经 requireGranted 核租户；动作权限来自定义；边上的可选能力 id 只校验目录、不执行。
 * WF-2：request_approval 开 PENDING；approve/reject 须第二操作员。
 */
public final class WorkflowService {

    public static final String AUDIT_START = "workflow.start";
    public static final String AUDIT_TRANSITION = "workflow.transition";
    public static final String AUDIT_APPROVAL_REQUEST = "workflow.approval.request";
    public static final String AUDIT_APPROVAL_CONSUME = "workflow.approval.consume";

    private final JdbcWorkflowStore store;
    private final OperatorTenantAccess tenantAccess;
    private final CapabilityCatalog capabilityCatalog;
    private final OperatorActionAudit audit;
    private final Clock clock;

    public WorkflowService(
            JdbcWorkflowStore store,
            OperatorTenantAccess tenantAccess,
            CapabilityCatalog capabilityCatalog,
            OperatorActionAudit audit,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.capabilityCatalog = Objects.requireNonNull(capabilityCatalog, "capabilityCatalog");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Start a workflow instance bound to an entity record (initial state OPEN).
     * 为实体记录启动工作流实例（初始 OPEN）。
     */
    public WorkflowInstance start(
            OperatorPrincipal operator,
            String definitionKey,
            String tenantId,
            String entityKey,
            String recordId) {
        Objects.requireNonNull(operator, "operator");
        String tid = requireNonBlank(tenantId, "tenantId");
        String ek = requireNonBlank(entityKey, "entityKey");
        String rid = requireNonBlank(recordId, "recordId");
        tenantAccess.requireGranted(operator, tid);
        requirePermission(operator, OperatorPermission.WORKFLOW_WRITE.permissionName());
        WorkflowDefinition definition = RepairTicketWorkflowDefinitions.require(definitionKey);
        Instant now = clock.instant();
        String instanceId = UUID.randomUUID().toString();
        WorkflowInstance instance = new WorkflowInstance(
                instanceId,
                tid,
                definition.definitionKey(),
                ek,
                rid,
                definition.initialState(),
                operator.subjectId(),
                now,
                now);
        WorkflowHistoryEntry startEntry = new WorkflowHistoryEntry(
                UUID.randomUUID().toString(),
                instanceId,
                tid,
                null,
                "start",
                definition.initialState(),
                operator.subjectId(),
                null,
                null,
                now);
        store.insertInstance(instance, startEntry);
        audit.record(operator, AUDIT_START, instanceId, AuditOutcome.ALLOWED);
        return instance;
    }

    /**
     * Apply a named action; illegal edges → {@link IllegalWorkflowTransitionException}.
     * 应用具名动作；非法边抛异常。
     */
    public WorkflowInstance transition(
            OperatorPrincipal operator, String instanceId, WorkflowAction action, String note) {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(action, "action");
        String id = requireNonBlank(instanceId, "instanceId");
        WorkflowInstance current =
                store.findById(id).orElseThrow(() -> new WorkflowNotFoundException(id));
        tenantAccess.requireGranted(operator, current.tenantId());
        WorkflowDefinition definition = RepairTicketWorkflowDefinitions.require(current.definitionKey());
        WorkflowTransition edge = definition
                .findTransition(current.currentState(), action)
                .orElseThrow(() -> new IllegalWorkflowTransitionException(
                        id, current.currentState(), action.wireName()));
        requirePermission(operator, edge.requiredPermission());
        String capabilityId = edge.capabilityId();
        if (capabilityId != null) {
            // Light Cap hook: validate catalog membership only — 轻量能力挂接：仅校验目录存在。
            capabilityCatalog.require(capabilityId);
        }
        Instant now = clock.instant();
        String noteText = note == null || note.isBlank() ? null : note.trim();
        if (noteText != null && noteText.length() > 512) {
            throw new IllegalArgumentException("note too long (max 512)");
        }
        WorkflowHistoryEntry history = new WorkflowHistoryEntry(
                UUID.randomUUID().toString(),
                id,
                current.tenantId(),
                current.currentState(),
                action.wireName(),
                edge.to(),
                operator.subjectId(),
                noteText,
                capabilityId,
                now);
        final WorkflowApproval[] approvalHolder = new WorkflowApproval[1];
        Runnable approvalSideEffect = null;
        if (action == WorkflowAction.REQUEST_APPROVAL) {
            approvalSideEffect = () -> approvalHolder[0] =
                    store.openApproval(id, current.tenantId(), operator.subjectId(), now);
        } else if (action == WorkflowAction.APPROVE || action == WorkflowAction.REJECT) {
            approvalSideEffect = () -> approvalHolder[0] =
                    store.consumeApproval(id, operator.subjectId(), action.wireName(), now);
        }
        store.applyTransition(id, edge.to(), now, history, approvalSideEffect);
        if (approvalHolder[0] != null) {
            if (action == WorkflowAction.REQUEST_APPROVAL) {
                audit.record(
                        operator, AUDIT_APPROVAL_REQUEST, approvalHolder[0].approvalId(), AuditOutcome.ALLOWED);
            } else {
                audit.record(
                        operator,
                        AUDIT_APPROVAL_CONSUME,
                        approvalHolder[0].approvalId() + ":" + action.wireName(),
                        AuditOutcome.ALLOWED);
            }
        }
        audit.record(
                operator,
                AUDIT_TRANSITION,
                id + ":" + action.wireName() + "->" + edge.to().name(),
                AuditOutcome.ALLOWED);
        return store.findById(id).orElseThrow(() -> new WorkflowNotFoundException(id));
    }

    public WorkflowStatusView status(OperatorPrincipal operator, String instanceId) {
        Objects.requireNonNull(operator, "operator");
        String id = requireNonBlank(instanceId, "instanceId");
        WorkflowInstance instance =
                store.findById(id).orElseThrow(() -> new WorkflowNotFoundException(id));
        tenantAccess.requireGranted(operator, instance.tenantId());
        requirePermission(operator, OperatorPermission.WORKFLOW_READ.permissionName());
        return toStatusView(instance);
    }

    public WorkflowStatusView statusByRecord(
            OperatorPrincipal operator, String tenantId, String entityKey, String recordId) {
        Objects.requireNonNull(operator, "operator");
        String tid = requireNonBlank(tenantId, "tenantId");
        tenantAccess.requireGranted(operator, tid);
        requirePermission(operator, OperatorPermission.WORKFLOW_READ.permissionName());
        WorkflowInstance instance = store.findByRecord(tid, requireNonBlank(entityKey, "entityKey"), requireNonBlank(recordId, "recordId"))
                .orElseThrow(() -> new WorkflowNotFoundException(tid + "/" + entityKey + "/" + recordId));
        return toStatusView(instance);
    }

    private WorkflowStatusView toStatusView(WorkflowInstance instance) {
        return new WorkflowStatusView(
                instance,
                store.listHistory(instance.instanceId()),
                store.findPendingApproval(instance.instanceId()).orElse(null));
    }

    private static void requirePermission(OperatorPrincipal operator, String permission) {
        if (!operator.permissionNames().contains(permission)) {
            throw new org.springframework.security.access.AccessDeniedException("missing permission " + permission);
        }
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is missing");
        }
        return value.trim();
    }

    /** Status + history (+ optional pending approval) — 状态、历史与可选待审。 */
    public record WorkflowStatusView(
            WorkflowInstance instance, List<WorkflowHistoryEntry> history, WorkflowApproval pendingApproval) {
        public WorkflowStatusView {
            Objects.requireNonNull(instance, "instance");
            history = List.copyOf(Objects.requireNonNull(history, "history"));
        }
    }
}
