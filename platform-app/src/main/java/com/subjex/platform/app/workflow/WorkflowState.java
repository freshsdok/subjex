package com.subjex.platform.app.workflow;

import java.util.Map;

/**
 * WorkflowState — declared FSM state token (ADR 0016 / WP4-1).
 * 已声明的状态机状态令牌。
 * <p>
 * Wire names are strings stored in {@code workflow_instance.current_state} (already VARCHAR).
 * Well-known repair-ticket constants keep the historic names ({@code OPEN}, …) so existing
 * rows and tests stay valid. New machines may declare other tokens without changing this class.
 * 线名是字符串，写入已有 VARCHAR 列。报修工单常量保留历史名，既有行与测试仍有效。新机器可声明其它令牌，不必改本类。
 */
public final class WorkflowState {

    /** Repair-ticket create state — 报修工单创建态。 */
    public static final WorkflowState OPEN = new WorkflowState("OPEN");

    /** Repair-ticket work started — 报修工单已开工。 */
    public static final WorkflowState IN_PROGRESS = new WorkflowState("IN_PROGRESS");

    /** Repair-ticket waiting for approval — 报修工单待审批。 */
    public static final WorkflowState AWAITING_APPROVAL = new WorkflowState("AWAITING_APPROVAL");

    /** Repair-ticket approved — 报修工单已批准。 */
    public static final WorkflowState APPROVED = new WorkflowState("APPROVED");

    /** Repair-ticket terminal closed — 报修工单已关闭（终态）。 */
    public static final WorkflowState CLOSED = new WorkflowState("CLOSED");

    private static final Map<String, WorkflowState> KNOWN = Map.of(
            OPEN.wireName, OPEN,
            IN_PROGRESS.wireName, IN_PROGRESS,
            AWAITING_APPROVAL.wireName, AWAITING_APPROVAL,
            APPROVED.wireName, APPROVED,
            CLOSED.wireName, CLOSED);

    private final String wireName;

    private WorkflowState(String wireName) {
        this.wireName = wireName;
    }

    /**
     * Parse a declared or well-known state token — 解析已声明或已知状态令牌。
     * <p>
     * Known repair-ticket names return the interned constants. Other trimmed names are accepted
     * as declared tokens. Blank values are rejected.
     * 已知报修工单名返回常量；其它裁剪后的名字作为声明令牌；空值拒绝。
     */
    public static WorkflowState of(String raw) {
        String key = requireToken(raw);
        WorkflowState known = KNOWN.get(key);
        return known != null ? known : new WorkflowState(key);
    }

    /**
     * Same as {@link #of} — kept so JDBC mapping can call {@code valueOf} as it did for the enum.
     * 与 {@link #of} 相同，保留给仍调用枚举式 {@code valueOf} 的 JDBC 映射。
     */
    public static WorkflowState valueOf(String raw) {
        return of(raw);
    }

    /** Database / API wire name — 库内与 API 线名。 */
    public String wireName() {
        return wireName;
    }

    /**
     * Historic enum-style name (identical to the wire name for all current tokens).
     * 历史枚举式名字（与当前令牌的线名相同）。
     */
    public String name() {
        return wireName;
    }

    @Override
    public String toString() {
        return wireName;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WorkflowState state)) {
            return false;
        }
        return wireName.equals(state.wireName);
    }

    @Override
    public int hashCode() {
        return wireName.hashCode();
    }

    static String requireToken(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("workflow state is missing");
        }
        String key = raw.trim();
        if (key.indexOf(' ') >= 0 || key.indexOf('/') >= 0 || key.indexOf('.') >= 0) {
            throw new IllegalArgumentException("illegal workflow state token: " + raw);
        }
        return key;
    }
}
