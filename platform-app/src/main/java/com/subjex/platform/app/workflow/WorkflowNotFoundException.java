package com.subjex.platform.app.workflow;

/**
 * WorkflowNotFoundException — missing instance (HTTP 404).
 * 实例不存在（HTTP 404）。
 */
public final class WorkflowNotFoundException extends RuntimeException {

    public WorkflowNotFoundException(String instanceId) {
        super("workflow instance not found: " + instanceId);
    }
}
