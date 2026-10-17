package com.subjex.platform.app.workflow;

/**
 * WorkflowApproverOutOfScope — approve/reject actor outside repair-ticket org scope (HTTP 403).
 * 审批人不在报修单组织范围内（HTTP 403）。
 */
public final class WorkflowApproverOutOfScope extends RuntimeException {

    public WorkflowApproverOutOfScope(String message) {
        super(message);
    }
}
