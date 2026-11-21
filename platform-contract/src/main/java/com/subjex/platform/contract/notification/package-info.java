/**
 * Notification — 外部模板通知：短信 / 邮件 / 微信模板的投递意图，不是站内信，也不是验证码。
 * <p>
 * Callers enqueue a template id plus parameters onto the existing {@code outbox_event} bus
 * ({@code NotificationRequested}). Wording stays in the declaring module. Tenant fail-closed;
 * reserved {@code platform} is never a default. Cross-tenant send is denied.
 * 调用方把模板标识与参数写入已有出箱总线。文案留在声明模块。租户失败关闭；保留租户 {@code platform}
 * 绝不是默认值。禁止跨租户发信。
 */
package com.subjex.platform.contract.notification;
