package com.subjex.platform.app.task;

/**
 * SubmitLockHeld — 提交锁被占用：同一租户的同一枚记号正在被另一次提交持有。
 */
public final class SubmitLockHeld extends RuntimeException {

    public SubmitLockHeld() {
        super("submit lock is held");
    }
}
