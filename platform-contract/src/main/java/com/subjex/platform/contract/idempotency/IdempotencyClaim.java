package com.subjex.platform.contract.idempotency;

/**
 * IdempotencyClaim — 幂等占用：某租户已经用这枚记号提交过，并且对应到一个任务。
 * <p>
 * {@code requestFingerprint} is the digest of the request facts. The same token with a different fingerprint is a conflict.
 * {@code requestFingerprint} 是请求事实的摘要。同一枚记号配上不同摘要，视为冲突。
 */
public record IdempotencyClaim(
        String tenantId,
        String idempotencyToken,
        String requestFingerprint,
        String taskId) {
}
