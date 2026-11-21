package com.subjex.platform.contract.notification;

/**
 * NotificationPort — 外部模板通知端口：把投递意图写入现有出箱，不写站内信，不发送验证码。
 * <p>
 * Callers enqueue a template id and parameter map (domain-owned wording). The implementation
 * writes {@link NotificationRequested} into existing {@code outbox_event}. It must not call
 * {@link com.subjex.platform.contract.task.TaskMessagePort#submit} (that creates {@code platform_task}
 * + {@code TaskRecorded}). Channel adapters and dispatch land in a later slice (WP5-2).
 * 调用方入队模板标识与参数（文案归域模块）。实现把 {@link NotificationRequested} 写入已有
 * {@code outbox_event}。不得调用 {@code TaskMessagePort.submit}。渠道适配与调度留到 WP5-2。
 * <p>
 * Every enqueue requires an explicit business tenant; reserved {@code platform} is never a default.
 * Missing, unknown, or non-ACTIVE tenants fail closed and write no row. Cross-tenant send is denied.
 * 每次入队必须带明确业务租户；保留租户 {@code platform} 绝不是默认值。缺失/未知/非 ACTIVE 失败关闭且不写行。禁止跨租户发信。
 */
public interface NotificationPort {

    /**
     * Persist one send intent as a {@code NotificationRequested} outbox row and return a stable id.
     * 将一次投递意图落成 {@code NotificationRequested} 出箱行，并返回稳定标识。
     */
    NotificationReceipt enqueue(NotificationCommand command);
}
