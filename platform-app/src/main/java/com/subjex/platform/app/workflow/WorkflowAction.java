package com.subjex.platform.app.workflow;

/**
 * WorkflowAction — named transition triggers on a workflow instance.
 * 工作流实例上的具名流转动作。
 */
public enum WorkflowAction {
    /** OPEN → IN_PROGRESS */
    START_WORK("start_work"),
    /** IN_PROGRESS → AWAITING_APPROVAL */
    REQUEST_APPROVAL("request_approval"),
    /** AWAITING_APPROVAL → APPROVED */
    APPROVE("approve"),
    /** AWAITING_APPROVAL → OPEN (reject; no parked REJECTED) */
    REJECT("reject"),
    /** APPROVED → CLOSED */
    CLOSE("close");

    private final String wireName;

    WorkflowAction(String wireName) {
        this.wireName = wireName;
    }

    /** API / history wire name — API 与历史中的名字。 */
    public String wireName() {
        return wireName;
    }

    public static WorkflowAction fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("action is missing");
        }
        String key = raw.trim();
        for (WorkflowAction action : values()) {
            if (action.wireName.equals(key) || action.name().equalsIgnoreCase(key)) {
                return action;
            }
        }
        throw new IllegalArgumentException("unknown workflow action: " + raw);
    }
}
