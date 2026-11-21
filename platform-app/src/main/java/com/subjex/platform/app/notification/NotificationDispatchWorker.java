package com.subjex.platform.app.notification;

import com.subjex.platform.contract.notification.NotificationDispatcher;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * NotificationDispatchWorker — 通知后台调度：按固定间隔拉取 PENDING 的 {@code NotificationRequested}，经渠道适配器投递。
 * <p>
 * Distinct from {@code OutboxRelay}, which only relays {@code TaskRecorded} via {@code DeliveryPort}.
 * 与只经 {@code DeliveryPort} 重投 {@code TaskRecorded} 的 {@code OutboxRelay} 分开。
 */
@Component
public final class NotificationDispatchWorker {

    private final NotificationDispatcher notificationDispatcher;
    private final int batchSize;

    public NotificationDispatchWorker(
            NotificationDispatcher notificationDispatcher,
            @Value("${platform.notification.dispatch-batch-size:20}") int batchSize) {
        this.notificationDispatcher = Objects.requireNonNull(notificationDispatcher, "notificationDispatcher");
        if (batchSize < 1) {
            throw new IllegalArgumentException("notification dispatch batch size must be at least 1");
        }
        this.batchSize = batchSize;
    }

    /**
     * Poll PENDING {@code NotificationRequested} rows and run channel adapters —
     * 拉取 PENDING 的 {@code NotificationRequested} 并运行渠道适配器。
     */
    @Scheduled(fixedDelayString = "${platform.notification.dispatch-interval-ms:2000}")
    public void dispatch() {
        notificationDispatcher.dispatchPending(batchSize);
    }
}
