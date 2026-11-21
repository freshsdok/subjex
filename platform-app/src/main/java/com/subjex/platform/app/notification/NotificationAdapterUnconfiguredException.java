package com.subjex.platform.app.notification;

import com.subjex.platform.contract.notification.NotificationChannel;

/**
 * NotificationAdapterUnconfiguredException — 该渠道没有可用的真实发送器，生产必须失败关闭。
 */
public final class NotificationAdapterUnconfiguredException extends RuntimeException {

    public static final String REASON = "notification-adapter-not-configured";

    public NotificationAdapterUnconfiguredException(NotificationChannel channel) {
        super("notification adapter not configured for " + channel.name());
    }

    public String reason() {
        return REASON;
    }
}
