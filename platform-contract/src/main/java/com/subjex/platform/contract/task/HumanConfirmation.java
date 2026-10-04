package com.subjex.platform.contract.task;

/**
 * HumanConfirmation — 人工确认：人是否已经认可这次非确定性步骤。
 * <p>
 * Absent on a deterministic task. {@link #UNCONFIRMED} means the task is not complete.
 * 确定性任务上不出现。{@link #UNCONFIRMED} 表示任务尚未完成。
 */
public enum HumanConfirmation {
    UNCONFIRMED,
    CONFIRMED
}
