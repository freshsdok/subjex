package com.subjex.platform.app.payment;

/**
 * PaymentHttpClient — transport hook for {@link HttpPaymentAdapter}.
 * {@link HttpPaymentAdapter} 的传输钩子。
 * <p>
 * Implementations must not log the request body or the API key. Tests inject a double;
 * they must not call a real payment network.
 * 实现不得把请求体或 API 密钥写入日志。测试注入替身，不得呼叫真实支付网络。
 */
public interface PaymentHttpClient {

    /**
     * POST {@code jsonBody} to {@code endpoint} authenticated with {@code apiKey}.
     * 使用 API 密钥向端点 POST JSON 请求体。
     */
    void post(String endpoint, String apiKey, String jsonBody);
}
