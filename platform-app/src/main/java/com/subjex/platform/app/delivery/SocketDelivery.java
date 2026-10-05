package com.subjex.platform.app.delivery;

import com.subjex.platform.contract.task.DeliveryResult;

/**
 * SocketDelivery — 套接字投递结果：出箱状态，加上这次写进 traceparent 的追踪标识。
 * <p>
 * {@code traceId} is the publisher span. sample-consumer continues that same trace from the socket frame.
 * {@code traceId} 是发布方跨度。sample-consumer 从套接字帧续上同一次追踪。
 */
public record SocketDelivery(DeliveryResult result, String traceId) {
}
