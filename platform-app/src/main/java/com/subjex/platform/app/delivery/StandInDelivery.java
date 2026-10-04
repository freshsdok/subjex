package com.subjex.platform.app.delivery;

import com.subjex.platform.contract.task.DeliveryResult;

/**
 * StandInDelivery — 替身投递结果：出箱状态，加上这次发出去的追踪标识。
 * <p>
 * {@code traceId} is the id placed on the traceparent header so the other application can continue the same trace.
 * {@code traceId} 是放进 traceparent 头的标识，另一个应用用它续上同一次追踪。
 */
public record StandInDelivery(DeliveryResult result, String traceId) {
}
