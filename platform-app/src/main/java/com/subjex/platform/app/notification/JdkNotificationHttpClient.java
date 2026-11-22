package com.subjex.platform.app.notification;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * JdkNotificationHttpClient — JDK HTTP transport for the vendor-shaped notification template.
 * 厂商形通知模板的 JDK HTTP 传输。
 * <p>
 * Used only when configuration explicitly selects a complete {@code adapter=http}. Tests must
 * inject a double instead of this client so they never call a real vendor network.
 * 仅在配置显式选中且配齐 {@code adapter=http} 时使用。测试必须注入替身，不得经本客户端呼叫真实厂商。
 * The response body is discarded; failures do not include the request body or API key.
 * 响应体丢弃；失败信息不含请求体或 API 密钥。
 */
public final class JdkNotificationHttpClient implements NotificationHttpClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;

    public JdkNotificationHttpClient() {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    JdkNotificationHttpClient(HttpClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public void post(String endpoint, String apiKey, String jsonBody) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new NotificationChannelSendFailedException();
        }
        if (apiKey == null || apiKey.isBlank() || jsonBody == null) {
            throw new NotificationChannelSendFailedException();
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("X-Api-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new NotificationChannelSendFailedException();
            }
        } catch (NotificationChannelSendFailedException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new NotificationChannelSendFailedException();
        } catch (IOException | RuntimeException ex) {
            throw new NotificationChannelSendFailedException();
        }
    }
}
