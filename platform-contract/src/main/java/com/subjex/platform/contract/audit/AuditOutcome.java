package com.subjex.platform.contract.audit;

/**
 * AuditOutcome — 审计结果：这一次动作记下时的结局。
 * <p>
 * Allowed, refused, or failed. It is not a free-form note.
 * 放行、拒绝或失败。不是一段自由备注。
 */
public enum AuditOutcome {
    ALLOWED,
    REFUSED,
    FAILED
}
