package com.subjex.platform.app.web;

/**
 * StepAttempt — 步骤尝试：一次确定性重试是否核对通过。
 */
public record StepAttempt(boolean stepSucceeded) {
}
