package com.subjex.platform.app.workflow;

import java.time.Instant;
import java.util.Objects;

/**
 * WorkflowInstance — one FSM run bound to a tenant entity record.
 * 绑定租户实体记录的一次状态机运行。
 */
public record WorkflowInstance(
        String instanceId,
        String tenantId,
        String definitionKey,
        String entityKey,
        String recordId,
        WorkflowState currentState,
        String startedBySubjectId,
        Instant startedAt,
        Instant updatedAt) {

    public WorkflowInstance {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(definitionKey, "definitionKey");
        Objects.requireNonNull(entityKey, "entityKey");
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(currentState, "currentState");
        Objects.requireNonNull(startedBySubjectId, "startedBySubjectId");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
