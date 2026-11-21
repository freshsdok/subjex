package com.subjex.platform.app.notification;

import com.subjex.platform.contract.notification.NotificationChannel;
import com.subjex.platform.contract.notification.NotificationChannelAdapter;
import com.subjex.platform.contract.notification.NotificationRequested;
import java.util.Objects;

/**
 * FailClosedNotificationAdapter — 生产占位：未配置真实发送器时失败关闭，绝不投递、不当成成功空操作。
 * <p>
 * The dispatcher records the failure on the existing outbox row so it is visible and can dead-letter.
 * 调度器把失败记在已有出箱行上，从而可查、可进入死信。
 */
public final class FailClosedNotificationAdapter implements NotificationChannelAdapter {

    private final NotificationChannel channel;

    public FailClosedNotificationAdapter(NotificationChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    @Override
    public NotificationChannel channel() {
        return channel;
    }

    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public void send(NotificationRequested request) {
        Objects.requireNonNull(request, "request");
        throw new NotificationAdapterUnconfiguredException(channel);
    }
}
