package com.subjex.platform.contract.task;

import java.time.Instant;

/**
 * DeadLetter — 死信：重试耗尽后留下的一条失败事实，供只读管理台查看。
 */
public record DeadLetter(
        String deadLetterId,
        String tenantId,
        OriginKind originKind,
        String originId,
        String failureReason,
        Instant recordedAt) {
}
