package com.subjex.platform.contract.points;

/**
 * AdjustPointsCommand — one operator grant or deduct. Amount is a positive integer; direction signs it.
 * 操作员一次发放或扣减。金额为正整数，方向决定符号。
 */
public record AdjustPointsCommand(
        String tenantId,
        String subjectId,
        PointsDirection direction,
        long amount,
        String reason,
        String memo,
        PointsReference reference,
        String idempotencyToken,
        String actorSubjectId,
        PointsActorKind actorKind) {

    public AdjustPointsCommand {
        tenantId = blankToNull(tenantId);
        subjectId = PointsReference.requireText(subjectId, "subject id");
        if (direction == null) {
            throw new IllegalArgumentException("direction is required");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be a positive integer");
        }
        reason = PointsReference.requireText(reason, "reason");
        if (reason.length() > 256) {
            throw new IllegalArgumentException("reason too long");
        }
        memo = blankToNull(memo);
        if (memo != null && memo.length() > 256) {
            throw new IllegalArgumentException("memo too long");
        }
        idempotencyToken = PointsReference.requireText(idempotencyToken, "idempotency token");
        if (idempotencyToken.length() > 128) {
            throw new IllegalArgumentException("idempotency token too long");
        }
        actorSubjectId = PointsReference.requireText(actorSubjectId, "actor subject id");
        if (actorKind == null) {
            throw new IllegalArgumentException("actor kind is required");
        }
    }

    /** Signed delta applied to the balance / 写入余额的带符号增量。 */
    public long signedDelta() {
        return direction == PointsDirection.GRANT ? amount : -amount;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
