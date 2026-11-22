package com.subjex.platform.app.notification;

/**
 * NotificationHttpClient — transport hook for {@link HttpNotificationChannelAdapter}.
 * {@link HttpNotificationChannelAdapter} 的传输钩子。
 * <p>
 * Implementations must not log the request body or the API key. Tests inject a double;
 * they must not call a real vendor network.
 * 实现不得把请求体或 API 密钥写入日志。测试注入替身，不得呼叫真实厂商网络。
 */
public interface NotificationHttpClient {

    /**
     * POST {@code jsonBody} to {@code endpoint} authenticated with {@code apiKey}.
     * 使用 API 密钥向端点 POST JSON 请求体。
     */
    void post(String endpoint, String apiKey, String jsonBody);
}
