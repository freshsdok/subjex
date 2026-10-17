package com.subjex.platform.app.workflow;

import com.subjex.entity.declare.RenderedEntity;
import com.subjex.platform.app.capability.CapabilityCatalog;
import com.subjex.platform.app.declaration.DeclarationKind;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * WorkflowService — start / transition / status for thin FSM (WF-1).
 * 薄状态机的启动 / 流转 / 状态查询（WF-1）。
 * <p>
 * Tenant grant via {@link OperatorTenantAccess#requireGranted}; action permission from definition;
 * optional Cap id on a transition is catalog-checked only (not executed).
 * WF-2: {@code request_approval} opens PENDING; {@code approve}/{@code reject} require a second operator.
 * WF-RT-3: repair-ticket approve/reject also require org scope via {@link RepairTicketOrgApproverGate}.
 * WF-RT-4: workflow audits use tenant + entityKey + declaration version + instance id target.
 * 经 requireGranted 核租户；动作权限来自定义；边上的可选能力 id 只校验目录、不执行。
 * WF-2：request_approval 开 PENDING；approve/reject 须第二操作员。
 * WF-RT-3：报修单 approve/reject 另须组织范围。
 * WF-RT-4：工作流审计带租户、实体键、声明版本与实例 id。
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
    /** Null in legacy unit tests that skip org gate — 旧单测可空以跳过组织门闩。 */
    private final RepairTicketOrgApproverGate orgApproverGate;
    /** Null in legacy tests; production resolves entity declaration version — 旧测可空；生产解析声明版本。 */
    private final EffectiveDeclarationService declarations;

    public WorkflowService(
            JdbcWorkflowStore store,
            OperatorTenantAccess tenantAccess,
            CapabilityCatalog capabilityCatalog,
            OperatorActionAudit audit,
            Clock clock,
            RepairTicketOrgApproverGate orgApproverGate,
            EffectiveDeclarationService declarations) {
        this.store = Objects.requireNonNull(store, "store");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.capabilityCatalog = Objects.requireNonNull(capabilityCatalog, "capabilityCatalog");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.orgApproverGate = orgApproverGate;
        this.declarations = declarations;
    }

    /** Production-shaped ctor without skipping declarations — 生产形态（声明服务可空仅测用）。 */
    public WorkflowService(
            JdbcWorkflowStore store,
            OperatorTenantAccess tenantAccess,
            CapabilityCatalog capabilityCatalog,
            OperatorActionAudit audit,
            Clock clock,
            RepairTicketOrgApproverGate orgApproverGate) {
        this(store, tenantAccess, capabilityCatalog, audit, clock, orgApproverGate, null);
    }

    /** Test helper without org gate / declarations — 无组织门闩与声明服务的测试构造。 */
    public WorkflowService(
            JdbcWorkflowStore store,
            OperatorTenantAccess tenantAccess,
            CapabilityCatalog capabilityCatalog,
            OperatorActionAudit audit,
            Clock clock) {
        this(store, tenantAccess, capabilityCatalog, audit, clock, null, null);
    }

    /**
     * Start a workflow instance bound to an entity record (initial state OPEN).
     * 为实体记录启动工作流实例（初始 OPEN）。
     * <p>
     * Explicit API / console Start requires {@code workflow.write}.
     * 显式 API / 控制台「启动」需要 {@code workflow.write}。
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
        DeclarationLinkage linkage = resolveLinkage(tid, ek);
        return insertNew(operator, definitionKey, tid, ek, rid, linkage);
    }

    /**
     * WF-RT-2: idempotent start after form upsert — skip if by-record instance exists.
     * WF-RT-2：表单 upsert 后幂等启动；已有 by-record 实例则跳过。
     */
    public WorkflowInstance startIfAbsentAfterUpsert(
            OperatorPrincipal operator, String tenantId, String entityKey, String recordId) {
        return startIfAbsentAfterUpsert(operator, tenantId, entityKey, recordId, null, null);
    }

    /**
     * WF-RT-2/4: upsert auto-start with optional declaration version from the form path.
     * When version/source are null, resolves via {@link EffectiveDeclarationService}.
     * WF-RT-2/4：表单路径可传入声明版本；缺省则经生效声明服务解析。
     */
    public WorkflowInstance startIfAbsentAfterUpsert(
            OperatorPrincipal operator,
            String tenantId,
            String entityKey,
            String recordId,
            Integer declarationVersion,
            String resolutionSource) {
        Objects.requireNonNull(operator, "operator");
        String tid = requireNonBlank(tenantId, "tenantId");
        String ek = requireNonBlank(entityKey, "entityKey");
        String rid = requireNonBlank(recordId, "recordId");
        tenantAccess.requireGranted(operator, tid);
        Optional<WorkflowInstance> existing = store.findByRecord(tid, ek, rid);
        if (existing.isPresent()) {
            return existing.get();
        }
        DeclarationLinkage linkage =
                declarationVersion != null
                        ? new DeclarationLinkage(declarationVersion, blankToNull(resolutionSource))
                        : resolveLinkage(tid, ek);
        try {
            return insertNew(operator, RepairTicketWorkflowDefinitions.REPAIR_TICKET, tid, ek, rid, linkage);
        } catch (WorkflowAlreadyStartedException race) {
            return store.findByRecord(tid, ek, rid).orElseThrow(() -> race);
        }
    }

    private WorkflowInstance insertNew(
            OperatorPrincipal operator,
            String definitionKey,
            String tenantId,
            String entityKey,
            String recordId,
            DeclarationLinkage linkage) {
        WorkflowDefinition definition = RepairTicketWorkflowDefinitions.require(definitionKey);
        Instant now = clock.instant();
        String instanceId = UUID.randomUUID().toString();
        WorkflowInstance instance = new WorkflowInstance(
                instanceId,
                tenantId,
                definition.definitionKey(),
                entityKey,
                recordId,
                definition.initialState(),
                operator.subjectId(),
                now,
                now);
        WorkflowHistoryEntry startEntry = new WorkflowHistoryEntry(
                UUID.randomUUID().toString(),
                instanceId,
                tenantId,
                null,
                "start",
                definition.initialState(),
                operator.subjectId(),
                null,
                null,
                now);
        store.insertInstance(instance, startEntry);
        recordWorkflowAudit(operator, tenantId, AUDIT_START, instanceId, entityKey, linkage);
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
            if (orgApproverGate != null) {
                orgApproverGate.requireApproverInScope(current, operator.subjectId());
            }
            approvalSideEffect = () -> approvalHolder[0] =
                    store.consumeApproval(id, operator.subjectId(), action.wireName(), now);
        }
        store.applyTransition(id, edge.to(), now, history, approvalSideEffect);
        DeclarationLinkage linkage = resolveLinkage(current.tenantId(), current.entityKey());
        if (approvalHolder[0] != null) {
            if (action == WorkflowAction.REQUEST_APPROVAL) {
                recordWorkflowAudit(
                        operator,
                        current.tenantId(),
                        AUDIT_APPROVAL_REQUEST,
                        approvalHolder[0].approvalId() + ":" + id,
                        current.entityKey(),
                        linkage);
            } else {
                recordWorkflowAudit(
                        operator,
                        current.tenantId(),
                        AUDIT_APPROVAL_CONSUME,
                        approvalHolder[0].approvalId() + ":" + action.wireName() + ":" + id,
                        current.entityKey(),
                        linkage);
            }
        }
        recordWorkflowAudit(
                operator,
                current.tenantId(),
                AUDIT_TRANSITION,
                id + ":" + action.wireName() + "->" + edge.to().name(),
                current.entityKey(),
                linkage);
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
        WorkflowInstance instance = store.findByRecord(
                        tid, requireNonBlank(entityKey, "entityKey"), requireNonBlank(recordId, "recordId"))
                .orElseThrow(() -> new WorkflowNotFoundException(tid + "/" + entityKey + "/" + recordId));
        return toStatusView(instance);
    }

    private void recordWorkflowAudit(
            OperatorPrincipal operator,
            String tenantId,
            String actionName,
            String actionTarget,
            String entityKey,
            DeclarationLinkage linkage) {
        audit.recordWorkflow(
                operator,
                tenantId,
                actionName,
                actionTarget,
                entityKey,
                linkage == null ? null : linkage.version(),
                linkage == null ? null : linkage.source(),
                AuditOutcome.ALLOWED);
    }

    /**
     * Resolve entity declaration version + source at action time (WF-RT-4).
     * 在动作时刻解析实体声明版本与来源（WF-RT-4）。
     */
    private DeclarationLinkage resolveLinkage(String tenantId, String entityKey) {
        if (declarations == null) {
            return DeclarationLinkage.unknown();
        }
        Optional<RenderedEntity> entity = declarations.runtimeEntity(tenantId, entityKey);
        if (entity.isEmpty()) {
            return DeclarationLinkage.unknown();
        }
        String source = declarations.resolutionSource(tenantId, DeclarationKind.ENTITY, entityKey);
        return new DeclarationLinkage(entity.get().version(), source);
    }

    private WorkflowStatusView toStatusView(WorkflowInstance instance) {
        List<String> candidates = List.of();
        if (orgApproverGate != null
                && store.findPendingApproval(instance.instanceId()).isPresent()) {
            candidates = orgApproverGate.listCandidateSubjectIds(instance);
        }
        return new WorkflowStatusView(
                instance,
                store.listHistory(instance.instanceId()),
                store.findPendingApproval(instance.instanceId()).orElse(null),
                candidates);
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

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record DeclarationLinkage(Integer version, String source) {
        static DeclarationLinkage unknown() {
            return new DeclarationLinkage(null, null);
        }
    }

    /** Status + history (+ optional pending approval + org candidates) — 状态、历史、待审与组织候选人。 */
    public record WorkflowStatusView(
            WorkflowInstance instance,
            List<WorkflowHistoryEntry> history,
            WorkflowApproval pendingApproval,
            List<String> approvalCandidateSubjectIds) {
        public WorkflowStatusView {
            Objects.requireNonNull(instance, "instance");
            history = List.copyOf(Objects.requireNonNull(history, "history"));
            approvalCandidateSubjectIds =
                    approvalCandidateSubjectIds == null
                            ? List.of()
                            : List.copyOf(approvalCandidateSubjectIds);
        }

        /** Backward-compatible 3-arg view — 兼容三参视图。 */
        public WorkflowStatusView(
                WorkflowInstance instance, List<WorkflowHistoryEntry> history, WorkflowApproval pendingApproval) {
            this(instance, history, pendingApproval, List.of());
        }
    }
}
