package com.subjex.platform.app.notification;

/**
 * NotificationChannelSendFailedException — a configured channel adapter failed to deliver.
 * 已配置的渠道适配器投递失败。
 * <p>
 * The message is a stable reason only. It must not include the body, destination, or API key.
 * 消息只含稳定原因码，不得包含请求体、目的地或 API 密钥。
 */
public final class NotificationChannelSendFailedException extends RuntimeException {

    public static final String REASON = "notification-channel-send-failed";

    public NotificationChannelSendFailedException() {
        super(REASON);
    }
}
