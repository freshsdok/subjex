package com.subjex.platform.app.workflow;

import java.util.Objects;

/**
 * WorkflowExportHook — the only public entry that may call {@link WorkflowService#startFromHook}.
 * Allow-lists {@code data-export} so non-export callers cannot start a machine without {@code workflow.write}.
 * 唯一可调用 {@code startFromHook} 的公开入口；仅允许 {@code data-export}，避免非导出调用方绕过 {@code workflow.write}。
 */
public final class WorkflowExportHook {

    /** Sole allowed definition / entity key — 唯一允许的定义与实体键. */
    public static final String ALLOWED_KEY = "data-export";

    private final WorkflowService workflows;

    public WorkflowExportHook(WorkflowService workflows) {
        this.workflows = Objects.requireNonNull(workflows, "workflows");
    }

    /**
     * Start the data-export machine after the export service has authorized the requester.
     * 在导出服务完成申请人授权后启动 data-export 机器。
     */
    public WorkflowInstance start(
            String definitionKey,
            String tenantId,
            String entityKey,
            String recordId,
            String startedBySubjectId) {
        requireAllowed(definitionKey, entityKey);
        return workflows.startFromHook(definitionKey, tenantId, entityKey, recordId, startedBySubjectId);
    }

    /**
     * Move a data-export instance READY → EXPIRED without {@code workflow.write}.
     * 将 data-export 实例从 READY 转到 EXPIRED，不要求 {@code workflow.write}。
     */
    public WorkflowInstance expire(String instanceId) {
        return workflows.transitionFromHook(
                instanceId, WorkflowAction.fromWire("expire"), "export-expiry", ALLOWED_KEY);
    }

    private static void requireAllowed(String definitionKey, String entityKey) {
        if (!ALLOWED_KEY.equals(definitionKey) || !ALLOWED_KEY.equals(entityKey)) {
            throw new IllegalArgumentException(
                    "workflow hook allowlist only permits definition/entity " + ALLOWED_KEY);
        }
    }
}
