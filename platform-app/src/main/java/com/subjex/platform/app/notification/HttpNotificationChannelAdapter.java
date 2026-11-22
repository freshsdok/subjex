package com.subjex.platform.app.notification;

import com.subjex.platform.contract.notification.NotificationChannel;
import com.subjex.platform.contract.notification.NotificationChannelAdapter;
import com.subjex.platform.contract.notification.NotificationRecipient;
import com.subjex.platform.contract.notification.NotificationRequested;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * HttpNotificationChannelAdapter — one vendor-shaped notification template (generic HTTP POST).
 * 厂商形通知模板（通用 HTTP POST），同一类可服务任一渠道。
 * <p>
 * This class does not pick a commercial vendor. It is used only when configuration explicitly
 * selects {@code adapter=http} for the channel and the endpoint plus API key are complete.
 * The JSON body includes the destination, template, and parameters; callers must not log it.
 * 本类不选定商业厂商。仅当该渠道显式选择 {@code adapter=http} 且端点与密钥配齐时才会被选中。
 * JSON 请求体含目的地、模板与参数；调用方不得把它写入日志。
 */
public final class HttpNotificationChannelAdapter implements NotificationChannelAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final NotificationChannel channel;
    private final NotificationHttpClient client;
    private final String endpoint;
    private final String apiKey;
    private final String from;

    public HttpNotificationChannelAdapter(
            NotificationChannel channel,
            NotificationHttpClient client,
            String endpoint,
            String apiKey,
            String from) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.client = Objects.requireNonNull(client, "client");
        this.endpoint = requireText(endpoint, "endpoint");
        this.apiKey = requireText(apiKey, "apiKey");
        this.from = from == null || from.isBlank() ? null : from.trim();
    }

    @Override
    public NotificationChannel channel() {
        return channel;
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public void send(NotificationRequested request) {
        Objects.requireNonNull(request, "request");
        if (request.channel() != channel) {
            throw new IllegalArgumentException(
                    "adapter channel " + channel + " cannot send " + request.channel());
        }
        String body = jsonBody(request);
        try {
            client.post(endpoint, apiKey, body);
        } catch (NotificationChannelSendFailedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Drop the cause so a transport message cannot leak the body or API key.
            // 丢掉原因，避免传输层报文把请求体或密钥带进异常。
            throw new NotificationChannelSendFailedException();
        }
    }

    String jsonBody(NotificationRequested request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", channel.name());
        payload.put("templateId", request.templateId());
        payload.put("tenantId", request.tenantId());
        NotificationRecipient recipient = request.recipient();
        if (recipient != null) {
            if (recipient.destination() != null) {
                payload.put("destination", recipient.destination());
            }
            if (recipient.subjectId() != null) {
                payload.put("subjectId", recipient.subjectId());
            }
            if (recipient.realm() != null) {
                payload.put("realm", recipient.realm().name());
            }
        }
        if (from != null) {
            payload.put("from", from);
        }
        payload.put("parameters", request.parameters() == null ? Map.of() : request.parameters());
        try {
            return JSON.writeValueAsString(payload);
        } catch (JacksonException ex) {
            throw new NotificationChannelSendFailedException();
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    String endpoint() {
        return endpoint;
    }
}
