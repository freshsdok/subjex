package com.subjex.platform.app.workflow;

/**
 * WorkflowState — thin FSM states for repair-ticket (ADR 0005).
 * 薄状态机状态（报修工单，ADR 0005）。
 * <p>
 * {@code REJECTED} is not parked: reject action returns to {@link #OPEN}.
 * {@code REJECTED} 不落库驻留：驳回动作直接回到 {@link #OPEN}。
 */
public enum WorkflowState {
    OPEN,
    IN_PROGRESS,
    AWAITING_APPROVAL,
    APPROVED,
    CLOSED
}
