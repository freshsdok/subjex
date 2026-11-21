package com.subjex.platform.app.notification;

import com.subjex.platform.contract.notification.NotificationChannel;
import com.subjex.platform.contract.notification.NotificationRequested;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RecordingNotificationSink — 模拟投递的内存接收槽：供测试检查成功发送，并可强制失败以验证重试/死信。
 * <p>
 * Does not call a vendor and must not be logged as if it were a production audit trail.
 * 不调用厂商，也不得当成生产审计轨迹来打印。
 */
public final class RecordingNotificationSink {

    private final List<NotificationDispatchRecord> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger remainingFailures = new AtomicInteger(0);
    private final Clock clock;

    public RecordingNotificationSink() {
        this(Clock.systemUTC());
    }

    public RecordingNotificationSink(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Force the next {@code count} accept calls to fail (for retry / DLQ tests).
     * 让接下来的 {@code count} 次接收失败（供重试/死信测试）。
     */
    public void failNext(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("failNext count must be >= 0");
        }
        remainingFailures.set(count);
    }

    /**
     * Record a successful mock send, or throw when a forced failure remains.
     * 记录一次成功的模拟发送；若仍有强制失败则抛错。
     */
    public void accept(NotificationRequested request) {
        Objects.requireNonNull(request, "request");
        if (remainingFailures.get() > 0) {
            remainingFailures.decrementAndGet();
            throw new IllegalStateException("forced mock adapter failure");
        }
        Instant at = clock.instant();
        sent.add(new NotificationDispatchRecord(
                request.eventId(),
                request.tenantId(),
                request.templateId(),
                request.channel(),
                request.recipient(),
                Map.copyOf(request.parameters()),
                at));
    }

    public List<NotificationDispatchRecord> sent() {
        return List.copyOf(sent);
    }

    public List<NotificationDispatchRecord> sent(NotificationChannel channel) {
        Objects.requireNonNull(channel, "channel");
        List<NotificationDispatchRecord> matches = new ArrayList<>();
        for (NotificationDispatchRecord record : sent) {
            if (record.channel() == channel) {
                matches.add(record);
            }
        }
        return List.copyOf(matches);
    }

    public void clear() {
        sent.clear();
        remainingFailures.set(0);
    }
}
