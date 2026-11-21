package com.subjex.platform.app.workflow;

/**
 * RepairTicketWorkflowDefinitions — repair-ticket definition key (ADR 0005 / 0016).
 * 报修工单定义键。
 * <p>
 * The FSM graph now lives in {@code workflows/repair-ticket.workflow.yaml} and is loaded by
 * {@link WorkflowCatalog}. This class keeps the historic key so auto-start, org-scope, and
 * inbox hooks (still hardcoded until WP4-2) can name the machine without changing wire names.
 * 状态机图已迁入 YAML，由目录加载。本类保留历史键，供尚未声明化的自动启动、组织范围与站内信钩子使用。
 */
public final class RepairTicketWorkflowDefinitions {

    public static final String REPAIR_TICKET = "repair-ticket";

    private RepairTicketWorkflowDefinitions() {}
}
