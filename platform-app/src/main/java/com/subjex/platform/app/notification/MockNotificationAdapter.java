package com.subjex.platform.app.notification;

import com.subjex.platform.contract.notification.NotificationChannel;
import com.subjex.platform.contract.notification.NotificationChannelAdapter;
import com.subjex.platform.contract.notification.NotificationRequested;
import java.util.Objects;

/**
 * MockNotificationAdapter — 开发/测试模拟适配器：把投递记入内存槽，不调用厂商，不打印密钥。
 * <p>
 * One instance serves one {@link NotificationChannel}. A later vendor adapter can replace this bean.
 * 一个实例服务一个渠道。后续厂商适配器可以替换本 Bean。
 */
public final class MockNotificationAdapter implements NotificationChannelAdapter {

    private final NotificationChannel channel;
    private final RecordingNotificationSink sink;

    public MockNotificationAdapter(NotificationChannel channel, RecordingNotificationSink sink) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public NotificationChannel channel() {
        return channel;
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public void send(NotificationRequested request) {
        Objects.requireNonNull(request, "request");
        if (request.channel() != channel) {
            throw new IllegalArgumentException(
                    "adapter channel " + channel + " cannot send " + request.channel());
        }
        sink.accept(request);
    }
}
