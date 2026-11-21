package com.subjex.platform.app.workflow;

import org.junit.jupiter.api.Tag;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * RepairTicketWorkflowDefinitionsTest — ADR 0005 / 0016 edge table smoke from the classpath declaration.
 * 报修工单边表烟测：图来自 classpath YAML，线名不变。
 */
@Tag(WorkflowTags.WORKFLOW)
class RepairTicketWorkflowDefinitionsTest {

    @Test
    void repairTicketEdges() {
        WorkflowDefinition def = new WorkflowCatalog().require(RepairTicketWorkflowDefinitions.REPAIR_TICKET);
        assertThat(def.initialState()).isEqualTo(WorkflowState.OPEN);
        assertThat(def.findTransition(WorkflowState.OPEN, WorkflowAction.START_WORK)).isPresent();
        assertThat(def.findTransition(WorkflowState.IN_PROGRESS, WorkflowAction.REQUEST_APPROVAL)).isPresent();
        assertThat(def.findTransition(WorkflowState.AWAITING_APPROVAL, WorkflowAction.APPROVE)).isPresent();
        assertThat(def.findTransition(WorkflowState.AWAITING_APPROVAL, WorkflowAction.REJECT))
                .map(WorkflowTransition::to)
                .contains(WorkflowState.OPEN);
        assertThat(def.findTransition(WorkflowState.APPROVED, WorkflowAction.CLOSE)).isPresent();
        assertThat(def.findTransition(WorkflowState.CLOSED, WorkflowAction.START_WORK)).isEmpty();
        assertThat(def.findTransition(WorkflowState.OPEN, WorkflowAction.APPROVE)).isEmpty();
    }
}
