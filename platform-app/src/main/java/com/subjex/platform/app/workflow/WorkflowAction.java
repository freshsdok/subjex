package com.subjex.platform.app.workflow;

import java.util.List;

/**
 * WorkflowAction — declared FSM action token (ADR 0016 / WP4-1).
 * 已声明的状态机动作令牌。
 * <p>
 * HTTP and history use the wire name ({@code start_work}, …). Well-known repair-ticket constants
 * keep those historic names so existing API clients and tests stay valid. New machines may declare
 * other tokens without changing this class.
 * HTTP 与历史使用线名。报修工单常量保留历史名。新机器可声明其它令牌，不必改本类。
 */
public final class WorkflowAction {

    /** OPEN → IN_PROGRESS */
    public static final WorkflowAction START_WORK = new WorkflowAction("start_work", "START_WORK");

    /** IN_PROGRESS → AWAITING_APPROVAL */
    public static final WorkflowAction REQUEST_APPROVAL = new WorkflowAction("request_approval", "REQUEST_APPROVAL");

    /** AWAITING_APPROVAL → APPROVED */
    public static final WorkflowAction APPROVE = new WorkflowAction("approve", "APPROVE");

    /** AWAITING_APPROVAL → OPEN (reject; no parked REJECTED) */
    public static final WorkflowAction REJECT = new WorkflowAction("reject", "REJECT");

    /** APPROVED → CLOSED */
    public static final WorkflowAction CLOSE = new WorkflowAction("close", "CLOSE");

    private static final List<WorkflowAction> KNOWN =
            List.of(START_WORK, REQUEST_APPROVAL, APPROVE, REJECT, CLOSE);

    private final String wireName;
    private final String legacyEnumName;

    private WorkflowAction(String wireName, String legacyEnumName) {
        this.wireName = wireName;
        this.legacyEnumName = legacyEnumName;
    }

    /** API / history wire name — API 与历史中的名字。 */
    public String wireName() {
        return wireName;
    }

    /**
     * Parse an HTTP / history action. Known repair-ticket names still accept the historic
     * enum spelling ({@code START_WORK}) as well as the wire name ({@code start_work}).
     * Other trimmed tokens are accepted as declared actions.
     * 解析 HTTP / 历史动作。已知报修工单名仍同时接受历史枚举拼写与线名；其它裁剪后的令牌作为声明动作。
     */
    public static WorkflowAction fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("action is missing");
        }
        String key = raw.trim();
        for (WorkflowAction action : KNOWN) {
            if (action.wireName.equals(key) || action.legacyEnumName.equalsIgnoreCase(key)) {
                return action;
            }
        }
        if (key.indexOf(' ') >= 0 || key.indexOf('/') >= 0) {
            throw new IllegalArgumentException("illegal workflow action token: " + raw);
        }
        return new WorkflowAction(key, key);
    }

    /** Same as {@link #fromWire} — 与 {@link #fromWire} 相同。 */
    public static WorkflowAction of(String raw) {
        return fromWire(raw);
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
        if (!(other instanceof WorkflowAction action)) {
            return false;
        }
        return wireName.equals(action.wireName);
    }

    @Override
    public int hashCode() {
        return wireName.hashCode();
    }
}
