package com.subjex.platform.app.workflow;

/**
 * IllegalWorkflowTransitionException — fail-closed illegal FSM edge (HTTP 409).
 * 非法状态机边，失败关闭（HTTP 409）。
 */
public final class IllegalWorkflowTransitionException extends RuntimeException {

    private final String instanceId;
    private final WorkflowState fromState;
    private final String actionName;

    public IllegalWorkflowTransitionException(String instanceId, WorkflowState fromState, String actionName) {
        super("illegal workflow transition: instance="
                + instanceId
                + " from="
                + fromState
                + " action="
                + actionName);
        this.instanceId = instanceId;
        this.fromState = fromState;
        this.actionName = actionName;
    }

    public String instanceId() {
        return instanceId;
    }

    public WorkflowState fromState() {
        return fromState;
    }

    public String actionName() {
        return actionName;
    }
}
