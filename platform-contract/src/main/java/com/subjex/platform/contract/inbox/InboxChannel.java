package com.subjex.platform.contract.inbox;

/**
 * InboxChannel — 站内信渠道：系统 / 工作流 / 组织广播 / 组群发.
 * <p>
 * {@link #ORG_BROADCAST} and {@link #GROUP_SEND} APIs land in IM-A4/A5 (ADR 0009).
 * {@link #ORG_BROADCAST} 与 {@link #GROUP_SEND} 的公开 API 在 IM-A4/A5（ADR 0009）。
 */
public enum InboxChannel {

    /** Platform / ops notice — 平台或运维通知. */
    SYSTEM,
    /** Workflow / approval alert — 工作流或审批提醒. */
    WORKFLOW,
    /** Org-unit broadcast — 组织节点广播. */
    ORG_BROADCAST,
    /** Dedicated inbox_group send — 站内信组群发. */
    GROUP_SEND
}
