package com.subjex.platform.contract.notification;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * NotificationCommand — 一次模板投递意图：模板标识 + 参数，不含内核文案。
 * <p>
 * Domain modules own the wording; the kernel only stores the template id and the parameter map.
 * {@code tenantId} may be blank so the port can fail closed (it must never default to {@code platform}).
 * 文案由域模块拥有；内核只保存模板标识与参数表。{@code tenantId} 允许为空以便端口失败关闭（绝不默认 {@code platform}）。
 */
public record NotificationCommand(
        String tenantId,
        String templateId,
        NotificationChannel channel,
        NotificationRecipient recipient,
        Map<String, String> parameters) {

    public NotificationCommand {
        if (tenantId != null) {
            tenantId = tenantId.trim();
        }
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("templateId required");
        }
        templateId = templateId.trim();
        channel = Objects.requireNonNull(channel, "channel");
        recipient = Objects.requireNonNull(recipient, "recipient");
        parameters = copyParameters(parameters);
    }

    private static Map<String, String> copyParameters(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("parameter key required");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("parameter value required: " + entry.getKey());
            }
            copy.put(entry.getKey().trim(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }
}
