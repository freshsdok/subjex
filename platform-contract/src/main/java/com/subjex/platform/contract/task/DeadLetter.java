package com.subjex.platform.contract.task;

import java.time.Instant;

/**
 * DeadLetter — 死信：重试耗尽后留下的一条失败事实，供管理台查看与重放/丢弃。
 *
 * @param discardedAt when non-null, operator discarded this letter (no further replay)
 */
public record DeadLetter(
        String deadLetterId,
        String tenantId,
        OriginKind originKind,
        String originId,
        String failureReason,
        Instant recordedAt,
        Instant discardedAt) {

    /** Compat ctor without discard (tests / older call sites) — 无丢弃字段的兼容构造。 */
    public DeadLetter(
            String deadLetterId,
            String tenantId,
            OriginKind originKind,
            String originId,
            String failureReason,
            Instant recordedAt) {
        this(deadLetterId, tenantId, originKind, originId, failureReason, recordedAt, null);
    }
}
