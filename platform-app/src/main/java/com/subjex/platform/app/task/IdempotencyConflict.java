package com.subjex.platform.app.task;

/**
 * IdempotencyConflict — 幂等冲突：同一枚记号被用来提交了另一组事实。
 */
public final class IdempotencyConflict extends RuntimeException {

    public IdempotencyConflict() {
        super("idempotency token was already used for another request");
    }
}
