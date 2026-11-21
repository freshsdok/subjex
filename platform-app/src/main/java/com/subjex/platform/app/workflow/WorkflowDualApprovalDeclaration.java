package com.subjex.platform.app.workflow;

import java.util.List;
import java.util.Objects;

/**
 * WorkflowDualApprovalDeclaration — declared dual-approve hook, applied by {@link WorkflowService}.
 * 声明中的双人审批钩子，由工作流服务应用。
 */
public record WorkflowDualApprovalDeclaration(String requestAction, List<String> consumeActions) {

    public WorkflowDualApprovalDeclaration {
        requestAction = requireToken("dualApproval.requestAction", requestAction);
        consumeActions = List.copyOf(Objects.requireNonNull(consumeActions, "dualApproval.consumeActions"));
        if (consumeActions.isEmpty()) {
            throw new IllegalArgumentException("dualApproval.consumeActions empty");
        }
        consumeActions.forEach(action -> requireToken("dualApproval.consumeActions", action));
    }

    private static String requireToken(String label, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(label + " is missing");
        }
        return raw.trim();
    }
}
