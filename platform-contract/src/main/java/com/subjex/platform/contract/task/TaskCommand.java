package com.subjex.platform.contract.task;

/**
 * TaskCommand — 任务指令：提交一条任务时必须给出的事实。
 * <p>
 * Deterministic commands leave {@code modelId}, {@code inputDigest}, and {@code humanConfirmation} empty.
 * Non-deterministic commands carry all three and no others.
 * 确定性指令不带 {@code modelId}、{@code inputDigest}、{@code humanConfirmation}。
 * 非确定性指令这三件都要带，且不再多带别的模型事实。
 */
public record TaskCommand(
        String tenantId,
        String idempotencyToken,
        String actorIdentityId,
        TaskKind taskKind,
        String stepName,
        boolean stepSucceeded,
        String modelId,
        String inputDigest,
        HumanConfirmation humanConfirmation) {
}
