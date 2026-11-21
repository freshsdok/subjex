package com.subjex.platform.app.notification;

import com.subjex.platform.contract.notification.NotificationChannel;
import com.subjex.platform.contract.notification.NotificationRecipient;
import java.time.Instant;
import java.util.Map;

/**
 * NotificationDispatchRecord — 一次模拟投递的可检查记录（模板标识 + 参数，不是内核文案）。
 * <p>
 * Held in memory for tests and the local/dev mock adapters. Do not treat this as an audit log.
 * 供测试与本地/开发模拟适配器在内存中检查。不要把它当成审计日志。
 */
public record NotificationDispatchRecord(
        String eventId,
        String tenantId,
        String templateId,
        NotificationChannel channel,
        NotificationRecipient recipient,
        Map<String, String> parameters,
        Instant recordedAt) {}
