package com.subjex.platform.contract.inbox;

/**
 * InboxChannel — 站内信渠道：系统 / 工作流 / 组织广播（广播仅 schema 占位）。
 * <p>
 * {@link #ORG_BROADCAST} is stored for forward compatibility; no public API until IM-7.
 * {@link #ORG_BROADCAST} 为向前兼容占位；IM-7 前无公开 API。
 */
public enum InboxChannel {

    /** Platform / ops notice — 平台或运维通知. */
    SYSTEM,
    /** Workflow / approval alert — 工作流或审批提醒. */
    WORKFLOW,
    /** Org-wide announcement (deferred) — 组织公告（后置）. */
    ORG_BROADCAST
}
