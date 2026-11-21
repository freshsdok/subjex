package com.subjex.platform.contract.payment;

/**
 * PaymentIdempotencyConflictException — 幂等令牌已用于不同指纹，拒绝覆盖已入账结果。
 * <p>
 * Same token + same fingerprint must replay the original document or refund line; a mismatch fails closed.
 * 相同令牌且相同指纹应回放原单或原退款行；指纹不一致则失败关闭。
 */
public final class PaymentIdempotencyConflictException extends RuntimeException {

    public PaymentIdempotencyConflictException(String tenantId, String idempotencyToken) {
        super("payment idempotency conflict: tenant "
                + tenantId
                + " token "
                + idempotencyToken);
    }
}
