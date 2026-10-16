package com.subjex.platform.app.workflow;

import org.junit.jupiter.api.Tag;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * RepairTicketWorkflowDefinitionsTest — ADR 0005 edge table smoke.
 * ADR 0005 边表冒烟。
 */
@Tag(WorkflowTags.WORKFLOW)
class RepairTicketWorkflowDefinitionsTest {

    @Test
    void repairTicketEdgesMatchAdr() {
        WorkflowDefinition def = RepairTicketWorkflowDefinitions.repairTicket();
        assertThat(def.initialState()).isEqualTo(WorkflowState.OPEN);
        assertThat(def.findTransition(WorkflowState.OPEN, WorkflowAction.START_WORK)).isPresent();
        assertThat(def.findTransition(WorkflowState.IN_PROGRESS, WorkflowAction.REQUEST_APPROVAL)).isPresent();
        assertThat(def.findTransition(WorkflowState.AWAITING_APPROVAL, WorkflowAction.APPROVE)).isPresent();
        assertThat(def.findTransition(WorkflowState.AWAITING_APPROVAL, WorkflowAction.REJECT))
                .get()
                .extracting(WorkflowTransition::to)
                .isEqualTo(WorkflowState.OPEN);
        assertThat(def.findTransition(WorkflowState.APPROVED, WorkflowAction.CLOSE)).isPresent();
        assertThat(def.findTransition(WorkflowState.CLOSED, WorkflowAction.START_WORK)).isEmpty();
        assertThat(def.findTransition(WorkflowState.OPEN, WorkflowAction.APPROVE)).isEmpty();
    }
}
