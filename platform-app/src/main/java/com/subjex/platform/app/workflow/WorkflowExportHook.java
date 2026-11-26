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

    /**
     * Move APPROVED → GENERATING after approval, without {@code workflow.write}.
     * 批准后将 APPROVED 转到 GENERATING，不要求 {@code workflow.write}。
     */
    public WorkflowInstance enqueue(String instanceId, String actorSubjectId) {
        return workflows.transitionFromHook(
                instanceId, WorkflowAction.fromWire("enqueue"), actor(actorSubjectId, "export-enqueue"), ALLOWED_KEY);
    }

    /**
     * Move GENERATING → READY when the worker finishes the artifact — 工作线程完成产物时转到 READY.
     */
    public WorkflowInstance complete(String instanceId) {
        return workflows.transitionFromHook(
                instanceId, WorkflowAction.fromWire("complete"), "export-worker", ALLOWED_KEY);
    }

    /**
     * Move GENERATING → FAILED when the worker cannot finish the artifact — 工作线程无法完成产物时转到 FAILED.
     */
    public WorkflowInstance fail(String instanceId) {
        return workflows.transitionFromHook(
                instanceId, WorkflowAction.fromWire("fail"), "export-worker", ALLOWED_KEY);
    }

    /**
     * Move FAILED → GENERATING so the worker may try again — 将 FAILED 重新入队为 GENERATING.
     */
    public WorkflowInstance retry(String instanceId, String actorSubjectId) {
        return workflows.transitionFromHook(
                instanceId, WorkflowAction.fromWire("retry"), actor(actorSubjectId, "export-retry"), ALLOWED_KEY);
    }

    private static String actor(String actorSubjectId, String fallback) {
        return actorSubjectId == null || actorSubjectId.isBlank() ? fallback : actorSubjectId.trim();
    }

    private static void requireAllowed(String definitionKey, String entityKey) {
        if (!ALLOWED_KEY.equals(definitionKey) || !ALLOWED_KEY.equals(entityKey)) {
            throw new IllegalArgumentException(
                    "workflow hook allowlist only permits definition/entity " + ALLOWED_KEY);
        }
    }
}
