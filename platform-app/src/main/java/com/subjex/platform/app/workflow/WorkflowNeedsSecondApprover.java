package com.subjex.platform.app.workflow;

/**
 * WorkflowNeedsSecondApprover — requester cannot self-approve/reject (HTTP 409).
 * 请求人不得自批/自驳（HTTP 409）。
 */
public final class WorkflowNeedsSecondApprover extends RuntimeException {

    public WorkflowNeedsSecondApprover(String message) {
        super(message);
    }
}
