package com.subjex.platform.app.delivery;

import com.subjex.platform.contract.task.TaskMessagePort;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * OutboxRelay — 出箱后台重投：按固定间隔拉取 PENDING 行并继续投递，直到成功或进死信。
 * <p>
 * Submit still tries once after commit. This worker covers rows that stayed PENDING (consumer down,
 * reject, network blip). Selection and attempt accounting live on {@link TaskMessagePort#relayPending(int)}.
 * 提交后仍同步试一次。本工人负责仍停在 PENDING 的行（消费者宕机、拒绝、网络抖动）。
 * 选行与尝试次数记在 {@link TaskMessagePort#relayPending(int)}。
 */
@Component
public final class OutboxRelay {

    private final TaskMessagePort taskMessagePort;
    private final int batchSize;

    public OutboxRelay(
            TaskMessagePort taskMessagePort,
            @Value("${platform.delivery.relay-batch-size:20}") int batchSize) {
        this.taskMessagePort = Objects.requireNonNull(taskMessagePort, "taskMessagePort");
        if (batchSize < 1) {
            throw new IllegalArgumentException("relay batch size must be at least 1");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${platform.delivery.relay-interval-ms:2000}")
    public void relay() {
        taskMessagePort.relayPending(batchSize);
    }
}
