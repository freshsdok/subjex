package com.subjex.platform.contract.notification;

import java.util.Map;

/**
 * NotificationRequested — 写入现有 {@code outbox_event} 的通知请求事件体（模板标识 + 参数，不是任务通知）。
 * <p>
 * Wire name is {@link #EVENT_NAME}. The body carries a template id and a parameter map, not
 * hard-coded domain prose. This is not {@code TaskRecorded} and must not be produced via
 * {@code TaskMessagePort.submit}.
 * 线路名为 {@link #EVENT_NAME}。事件体携带模板标识与参数，不含写死的域文案。
 * 这不是 {@code TaskRecorded}，也不得经 {@code TaskMessagePort.submit} 产生。
 */
public record NotificationRequested(
        String eventId,
        String tenantId,
        String templateId,
        NotificationChannel channel,
        NotificationRecipient recipient,
        Map<String, String> parameters) {

    /** Wire name stored in {@code outbox_event.event_name} — 写入 {@code outbox_event.event_name} 的线路名. */
    public static final String EVENT_NAME = "NotificationRequested";

    public static NotificationRequested from(String eventId, String tenantId, NotificationCommand command) {
        return new NotificationRequested(
                eventId,
                tenantId,
                command.templateId(),
                command.channel(),
                command.recipient(),
                command.parameters());
    }
}
