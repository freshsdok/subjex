package com.subjex.platform.contract.points;

/**
 * PointsIdempotencyConflictException — The same idempotency token was reused with a different adjustment fingerprint. / 同一幂等令牌被用于不同的调整指纹。
 */
public final class PointsIdempotencyConflictException extends RuntimeException {

    public static final String REASON = "points-idempotency-conflict";

    public PointsIdempotencyConflictException(String message) {
        super(message);
    }
}
