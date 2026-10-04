package com.subjex.platform.app.web;

import com.subjex.platform.contract.task.HumanConfirmation;
import com.subjex.platform.contract.task.TaskKind;

/**
 * TaskSubmission — 任务提交：调用方在租户头和幂等记号之外给出的步骤事实。
 * <p>
 * The three non-deterministic facts are {@code modelId}, {@code inputDigest}, and {@code humanConfirmation}.
 * 非确定性的三件事实是 {@code modelId}、{@code inputDigest} 和 {@code humanConfirmation}。
 */
public record TaskSubmission(
        String actorIdentityId,
        TaskKind taskKind,
        String stepName,
        boolean stepSucceeded,
        String modelId,
        String inputDigest,
        HumanConfirmation humanConfirmation) {
}
