package com.subjex.platform.app.workflow;

import com.subjex.platform.app.capability.CapabilityId;
import com.subjex.platform.app.security.OperatorPermission;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * RepairTicketWorkflowDefinitions — fixed ADR 0005 repair-ticket machine (+ registry).
 * 固定的 ADR 0005 报修工单状态机（含注册表）。
 * <p>
 * {@link WorkflowAction#REQUEST_APPROVAL} optionally attaches {@code ai.summarizePreview}
 * (catalog check only; Cap is not executed in WF-1).
 * {@code request_approval} 可选挂接摘要预览能力（仅校验目录；WF-1 不执行能力）。
 */
public final class RepairTicketWorkflowDefinitions {

    public static final String REPAIR_TICKET = "repair-ticket";

    private static final WorkflowDefinition REPAIR_TICKET_DEF = new WorkflowDefinition(
            REPAIR_TICKET,
            WorkflowState.OPEN,
            List.of(
                    new WorkflowTransition(
                            WorkflowState.OPEN,
                            WorkflowAction.START_WORK,
                            WorkflowState.IN_PROGRESS,
                            OperatorPermission.WORKFLOW_WRITE.permissionName()),
                    new WorkflowTransition(
                            WorkflowState.IN_PROGRESS,
                            WorkflowAction.REQUEST_APPROVAL,
                            WorkflowState.AWAITING_APPROVAL,
                            OperatorPermission.WORKFLOW_WRITE.permissionName(),
                            CapabilityId.AI_SUMMARIZE_PREVIEW.id()),
                    new WorkflowTransition(
                            WorkflowState.AWAITING_APPROVAL,
                            WorkflowAction.APPROVE,
                            WorkflowState.APPROVED,
                            OperatorPermission.WORKFLOW_APPROVE.permissionName()),
                    new WorkflowTransition(
                            WorkflowState.AWAITING_APPROVAL,
                            WorkflowAction.REJECT,
                            WorkflowState.OPEN,
                            OperatorPermission.WORKFLOW_APPROVE.permissionName()),
                    new WorkflowTransition(
                            WorkflowState.APPROVED,
                            WorkflowAction.CLOSE,
                            WorkflowState.CLOSED,
                            OperatorPermission.WORKFLOW_WRITE.permissionName())));

    private static final Map<String, WorkflowDefinition> BY_KEY = Map.of(REPAIR_TICKET, REPAIR_TICKET_DEF);

    private RepairTicketWorkflowDefinitions() {}

    public static WorkflowDefinition repairTicket() {
        return REPAIR_TICKET_DEF;
    }

    public static Optional<WorkflowDefinition> find(String definitionKey) {
        if (definitionKey == null || definitionKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_KEY.get(definitionKey.trim()));
    }

    public static WorkflowDefinition require(String definitionKey) {
        return find(definitionKey)
                .orElseThrow(() -> new IllegalArgumentException("unknown workflow definition: " + definitionKey));
    }
}
