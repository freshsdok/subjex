package com.subjex.platform.contract.delivery;

import com.subjex.platform.contract.task.DeliveryResult;

/**
 * DeliveryAttempt — 一次出箱投递尝试的结果：状态加上写入 traceparent 的追踪标识。
 * <p>
 * {@code traceId} is the publisher span id when the transport wrote a W3C traceparent; otherwise may be blank.
 * {@code traceId} 是发布方跨度标识（传输写了 W3C traceparent 时）；否则可为空。
 */
public record DeliveryAttempt(DeliveryResult result, String traceId) {
}
