package com.subjex.platform.contract.task;

/**
 * TaskLifecycle — 任务生命周期：完成与重试的规则，不依赖数据库。
 * <p>
 * A non-deterministic task is complete only after human confirmation. The same rule guards both kinds because they share one table.
 * 非确定性任务只有在人工确认之后才算完成。两类任务共用一张表，所以用同一套规则看守。
 */
public final class TaskLifecycle {

    public static final int DEFAULT_MAX_ATTEMPT = 3;

    private TaskLifecycle() {
    }

    /**
     * Reject a command whose three extra facts do not match its kind.
     * 若那三件额外事实与任务种类不符，则拒绝该指令。
     */
    public static void validate(TaskCommand command) {
        if (command.tenantId() == null || command.tenantId().isBlank()) {
            throw new IllegalArgumentException("tenant is missing");
        }
        if (command.idempotencyToken() == null || command.idempotencyToken().isBlank()) {
            throw new IllegalArgumentException("idempotency token is missing");
        }
        if (command.actorIdentityId() == null || command.actorIdentityId().isBlank()) {
            throw new IllegalArgumentException("actor identity is missing");
        }
        if (command.stepName() == null || command.stepName().isBlank()) {
            throw new IllegalArgumentException("step name is missing");
        }
        if (command.taskKind() == null) {
            throw new IllegalArgumentException("task kind is missing");
        }
        boolean extraPresent = command.modelId() != null
                || command.inputDigest() != null
                || command.humanConfirmation() != null;
        if (command.taskKind() == TaskKind.DETERMINISTIC) {
            if (extraPresent) {
                throw new IllegalArgumentException(
                        "deterministic task must not carry model id, input digest, or human confirmation");
            }
            return;
        }
        if (command.modelId() == null || command.modelId().isBlank()
                || command.inputDigest() == null || command.inputDigest().isBlank()
                || command.humanConfirmation() == null) {
            throw new IllegalArgumentException(
                    "non-deterministic task requires model id, input digest, and human confirmation");
        }
    }

    /**
     * @return whether this pair of facts is allowed to be stored as {@link TaskState#COMPLETED}
     *         这组事实能否记成 {@link TaskState#COMPLETED}
     */
    public static boolean mayComplete(TaskKind taskKind, HumanConfirmation humanConfirmation) {
        if (taskKind == TaskKind.DETERMINISTIC) {
            return true;
        }
        return humanConfirmation == HumanConfirmation.CONFIRMED;
    }

    /**
     * Next state after one step attempt — 一次步骤尝试之后的状态。
     */
    public static TaskState stateAfterStep(
            TaskKind taskKind,
            HumanConfirmation humanConfirmation,
            boolean stepSucceeded,
            int attemptCount,
            int maxAttempt) {
        if (!stepSucceeded) {
            return attemptCount >= maxAttempt ? TaskState.DEAD : TaskState.PENDING;
        }
        if (!mayComplete(taskKind, humanConfirmation)) {
            return TaskState.AWAITING_CONFIRMATION;
        }
        return TaskState.COMPLETED;
    }

    /**
     * Confirmation finishes a waiting non-deterministic task — 确认使等待中的非确定性任务完成。
     */
    public static TaskState stateAfterConfirmation(TaskState current, HumanConfirmation humanConfirmation) {
        if (current != TaskState.AWAITING_CONFIRMATION) {
            throw new IllegalArgumentException("only a task awaiting confirmation can be confirmed");
        }
        if (humanConfirmation != HumanConfirmation.CONFIRMED) {
            return TaskState.AWAITING_CONFIRMATION;
        }
        return TaskState.COMPLETED;
    }

    /**
     * A task may be retried or confirmed only inside its own tenant — 任务只能在它所属的租户里重试或确认。
     */
    public static void requireSameTenant(String requestedTenantId, String storedTenantId) {
        if (requestedTenantId == null || !requestedTenantId.equals(storedTenantId)) {
            throw new IllegalArgumentException("task is not in this tenant");
        }
    }
}
