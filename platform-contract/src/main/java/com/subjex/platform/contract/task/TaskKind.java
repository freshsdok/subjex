package com.subjex.platform.contract.task;

/**
 * TaskKind — 任务种类：步骤的结果是可重复核对的，还是必须等人确认的。
 */
public enum TaskKind {
    /** Retryable checkable step — 可重试、可核对的步骤。 */
    DETERMINISTIC,
    /** Step that names a model and waits for a person — 点名了模型并等待人的步骤。 */
    NON_DETERMINISTIC
}
