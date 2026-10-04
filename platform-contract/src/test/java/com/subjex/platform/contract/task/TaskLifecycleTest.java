package com.subjex.platform.contract.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TaskLifecycleTest {

    @Test
    void deterministicStepCompletesWithoutTheThreeExtraFacts() {
        TaskCommand command = deterministic(true);
        TaskLifecycle.validate(command);
        assertTrue(TaskLifecycle.mayComplete(TaskKind.DETERMINISTIC, null));
        assertEquals(
                TaskState.COMPLETED,
                TaskLifecycle.stateAfterStep(TaskKind.DETERMINISTIC, null, true, 1, 3));
    }

    @Test
    void deterministicFailureStaysPendingUntilAttemptsAreExhausted() {
        assertEquals(
                TaskState.PENDING,
                TaskLifecycle.stateAfterStep(TaskKind.DETERMINISTIC, null, false, 1, 3));
        assertEquals(
                TaskState.DEAD,
                TaskLifecycle.stateAfterStep(TaskKind.DETERMINISTIC, null, false, 3, 3));
    }

    @Test
    void unconfirmedNonDeterministicTaskIsNotComplete() {
        TaskCommand command = nonDeterministic(HumanConfirmation.UNCONFIRMED);
        TaskLifecycle.validate(command);
        assertFalse(TaskLifecycle.mayComplete(TaskKind.NON_DETERMINISTIC, HumanConfirmation.UNCONFIRMED));
        assertEquals(
                TaskState.AWAITING_CONFIRMATION,
                TaskLifecycle.stateAfterStep(
                        TaskKind.NON_DETERMINISTIC, HumanConfirmation.UNCONFIRMED, true, 1, 3));
        assertEquals(
                TaskState.AWAITING_CONFIRMATION,
                TaskLifecycle.stateAfterConfirmation(
                        TaskState.AWAITING_CONFIRMATION, HumanConfirmation.UNCONFIRMED));
    }

    @Test
    void confirmedNonDeterministicTaskMayComplete() {
        assertTrue(TaskLifecycle.mayComplete(TaskKind.NON_DETERMINISTIC, HumanConfirmation.CONFIRMED));
        assertEquals(
                TaskState.COMPLETED,
                TaskLifecycle.stateAfterConfirmation(
                        TaskState.AWAITING_CONFIRMATION, HumanConfirmation.CONFIRMED));
    }

    @Test
    void extraFactsMustMatchTheKind() {
        assertThrows(IllegalArgumentException.class, () -> TaskLifecycle.validate(new TaskCommand(
                "tenant-north",
                "token-1",
                "identity-1",
                TaskKind.DETERMINISTIC,
                "admit-subject",
                true,
                "model-1",
                null,
                null)));
        assertThrows(IllegalArgumentException.class, () -> TaskLifecycle.validate(new TaskCommand(
                "tenant-north",
                "token-1",
                "identity-1",
                TaskKind.NON_DETERMINISTIC,
                "draft-reply",
                true,
                "model-1",
                "digest-1",
                null)));
    }

    @Test
    void retryAndConfirmationStayInsideTheTenant() {
        TaskLifecycle.requireSameTenant("tenant-north", "tenant-north");
        assertThrows(IllegalArgumentException.class,
                () -> TaskLifecycle.requireSameTenant("tenant-other", "tenant-north"));
    }

    @Test
    void deliveryBecomesDeadWhenAttemptsAreExhausted() {
        assertEquals(OutboxState.PUBLISHED, DeliveryProgress.next(true, 1, 3));
        assertEquals(OutboxState.PENDING, DeliveryProgress.next(false, 1, 3));
        assertEquals(OutboxState.DEAD, DeliveryProgress.next(false, 3, 3));
    }

    private static TaskCommand deterministic(boolean stepSucceeded) {
        return new TaskCommand(
                "tenant-north",
                "token-1",
                "identity-1",
                TaskKind.DETERMINISTIC,
                "admit-subject",
                stepSucceeded,
                null,
                null,
                null);
    }

    private static TaskCommand nonDeterministic(HumanConfirmation confirmation) {
        return new TaskCommand(
                "tenant-north",
                "token-1",
                "identity-1",
                TaskKind.NON_DETERMINISTIC,
                "draft-reply",
                true,
                "model-1",
                "digest-1",
                confirmation);
    }
}
