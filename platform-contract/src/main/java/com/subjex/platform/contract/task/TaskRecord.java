package com.subjex.platform.contract.task;

import java.time.Instant;

/**
 * TaskRecord — 任务记录：任务表中的一行。
 * <p>
 * Deterministic and non-deterministic tasks share this shape. The three extra facts are null on a deterministic row.
 * 确定性与非确定性任务共用这一形状。确定性行上那三件额外事实为 null。
 */
public record TaskRecord(
        String taskId,
        String tenantId,
        TaskKind taskKind,
        TaskState taskState,
        String stepName,
        int attemptCount,
        int maxAttempt,
        String modelId,
        String inputDigest,
        HumanConfirmation humanConfirmation,
        String failureReason,
        Instant createdAt) {
}
