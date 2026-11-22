package com.subjex.platform.app.notification;

import java.time.Instant;
import java.util.Map;

/**
 * NotificationSendDocument — operator projection of one {@code NotificationRequested} outbox row.
 * 操作员看到的一条外部通知发送记录：投影已有出箱行，不是新的持久台账。
 *
 * @param parameters template parameters from the event body; empty if the body cannot be read
 *                   事件体中的模板参数；正文无法解析时为空
 * @param deadLetterId current non-discarded dead letter for this event, if any
 *                     该事件当前未丢弃的死信标识（如有）
 */
public record NotificationSendDocument(
        String eventId,
        String tenantId,
        String templateId,
        String channel,
        String recipientSubjectId,
        String recipientRealm,
        String recipientDestination,
        Map<String, String> parameters,
        String status,
        int attemptCount,
        String failureReason,
        Instant occurredAt,
        Instant publishedAt,
        String deadLetterId,
        Instant deadLetteredAt) {}
