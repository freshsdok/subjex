package com.subjex.platform.contract.points;

import java.time.Instant;

/**
 * One append-only ledger line. Delta is signed; balanceAfter is the projection after this line.
 * 一条只追加的台账行。delta 带符号；balanceAfter 是本行之后的余额投影。
 */
public record PointsLedgerEntry(
        String entryId,
        String tenantId,
        String subjectId,
        long delta,
        long balanceAfter,
        PointsDirection direction,
        String reason,
        String memo,
        String referenceType,
        String referenceId,
        String actorSubjectId,
        PointsActorKind actorKind,
        Instant createdAt) {}
