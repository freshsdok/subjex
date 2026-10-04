package com.subjex.platform.contract.task;

import java.time.Instant;

/**
 * OutboxEvent — 出箱事件：先和任务写在同一张库里，再送到另一个进程。
 * <p>
 * {@code eventBody} is the notice text. {@code traceId} is the trace that should continue in the consumer.
 * {@code eventBody} 是通知正文。{@code traceId} 是应当在消费者里续上的追踪。
 */
public record OutboxEvent(
        String eventId,
        String tenantId,
        String eventName,
        String eventBody,
        OutboxState eventState,
        String traceId,
        int attemptCount,
        String failureReason,
        Instant occurredAt,
        Instant publishedAt) {
}
