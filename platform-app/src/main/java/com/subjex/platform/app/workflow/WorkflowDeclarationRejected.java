package com.subjex.platform.app.workflow;

/**
 * WorkflowDeclarationRejected — fail-closed when a workflow YAML cannot be loaded.
 * 工作流声明无法加载时失败关闭。
 */
public final class WorkflowDeclarationRejected extends IllegalArgumentException {

    public WorkflowDeclarationRejected(String message) {
        super(message);
    }
}
