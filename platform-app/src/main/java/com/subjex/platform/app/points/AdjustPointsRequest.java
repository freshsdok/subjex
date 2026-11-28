package com.subjex.platform.app.points;

/**
 * JSON body for an operator grant or deduct. The token lives on {@code X-Idempotency-Token}.
 * 操作员发放或扣减的 JSON 体。幂等令牌放在 {@code X-Idempotency-Token}。
 */
public record AdjustPointsRequest(
        String subjectId,
        String direction,
        Long amount,
        String reason,
        String memo,
        Reference reference,
        String idempotencyToken) {

    /** Optional generic hang-on — 可选的通用挂靠。 */
    public record Reference(String type, String id) {}
}
