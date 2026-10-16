package com.subjex.platform.app.workflow;

import java.util.Objects;

/**
 * WorkflowTransition — one legal edge: from + action → to, with permission and optional Cap id.
 * 一条合法边：from + action → to，带权限与可选能力 id。
 */
public record WorkflowTransition(
        WorkflowState from,
        WorkflowAction action,
        WorkflowState to,
        String requiredPermission,
        String capabilityId) {

    public WorkflowTransition {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(requiredPermission, "requiredPermission");
        if (requiredPermission.isBlank()) {
            throw new IllegalArgumentException("requiredPermission blank");
        }
        capabilityId = capabilityId == null || capabilityId.isBlank() ? null : capabilityId.trim();
    }

    public WorkflowTransition(WorkflowState from, WorkflowAction action, WorkflowState to, String requiredPermission) {
        this(from, action, to, requiredPermission, null);
    }
}
