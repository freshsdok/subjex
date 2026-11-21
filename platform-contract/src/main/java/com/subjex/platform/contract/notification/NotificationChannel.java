package com.subjex.platform.contract.notification;

/**
 * NotificationChannel — 外部通知渠道标识：短信、邮件、微信模板（仅标识，不是传输枚举）。
 * <p>
 * These are channel adapters (WP5-2), not {@code DeliveryTransport} values competing with socket/Kafka.
 * The kernel does not hardcode a vendor here.
 * 这些是渠道适配器（WP5-2），不是与 socket/Kafka 并列的传输枚举。此处不写死厂商。
 */
public enum NotificationChannel {

    /** Short message service — 短信. */
    SMS,

    /** Email — 邮件. */
    EMAIL,

    /** WeChat template message (official-account or subscribe; product choice is later) — 微信模板消息（具体产品形态后定）. */
    WECHAT_TEMPLATE
}
