package com.subjex.platform.app.workflow;

/**
 * WorkflowAlreadyStartedException — one instance per (tenant, entityKey, recordId) (HTTP 409).
 * 同一租户实体记录仅允许一个实例（HTTP 409）。
 */
public final class WorkflowAlreadyStartedException extends RuntimeException {

    public WorkflowAlreadyStartedException(String tenantId, String entityKey, String recordId) {
        super("workflow already started for " + tenantId + "/" + entityKey + "/" + recordId);
    }
}
