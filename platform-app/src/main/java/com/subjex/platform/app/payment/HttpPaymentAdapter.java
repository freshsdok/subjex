package com.subjex.platform.app.payment;

import com.subjex.platform.contract.payment.PaymentDocument;
import com.subjex.platform.contract.payment.PaymentProviderAdapter;
import com.subjex.platform.contract.payment.PaymentResultCallback;
import com.subjex.platform.contract.payment.RefundCommand;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * HttpPaymentAdapter — one vendor-shaped payment template (generic HTTP POST).
 * 厂商形支付模板（通用 HTTP POST），同一类可挂到 {@code MOCK} / {@code WECHAT_PAY} / {@code ALIPAY} 标识上。
 * <p>
 * This is not a WeChat Pay or Alipay SDK. Reserved provider names can select this template when
 * configuration is explicit and complete. Callers must not log the JSON body, API key, or merchant id.
 * 这不是微信支付或支付宝 SDK。预留供应商标识仅在配置显式且配齐时选用本模板。
 * 调用方不得把 JSON 请求体、API 密钥或商户号写入日志。
 */
public final class HttpPaymentAdapter implements PaymentProviderAdapter {

    static final String ACTION_PAYMENT_RESULT = "payment-result";
    static final String ACTION_REFUND = "refund";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String providerId;
    private final PaymentHttpClient client;
    private final String endpoint;
    private final String apiKey;
    private final String merchantId;

    public HttpPaymentAdapter(
            String providerId,
            PaymentHttpClient client,
            String endpoint,
            String apiKey,
            String merchantId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId required");
        }
        this.providerId = providerId.trim();
        this.client = Objects.requireNonNull(client, "client");
        this.endpoint = requireText(endpoint, "endpoint");
        this.apiKey = requireText(apiKey, "apiKey");
        this.merchantId = requireText(merchantId, "merchantId");
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public void acceptPaymentResult(PaymentDocument current, PaymentResultCallback callback) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(callback, "callback");
        post(jsonBody(ACTION_PAYMENT_RESULT, current, callback, null));
    }

    @Override
    public void acceptRefund(PaymentDocument current, RefundCommand command) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(command, "command");
        post(jsonBody(ACTION_REFUND, current, null, command));
    }

    private void post(String body) {
        try {
            client.post(endpoint, apiKey, body);
        } catch (PaymentAdapterSendFailedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Drop the cause so a transport message cannot leak the body, merchant id, or API key.
            // 丢掉原因，避免传输层报文把请求体、商户号或密钥带进异常。
            throw new PaymentAdapterSendFailedException();
        }
    }

    String jsonBody(
            String action, PaymentDocument current, PaymentResultCallback callback, RefundCommand refund) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("providerId", providerId);
        payload.put("merchantId", merchantId);
        payload.put("paymentId", current.paymentId());
        payload.put("tenantId", current.tenantId());
        payload.put("amountMinor", current.amount().amountMinor());
        payload.put("currency", current.amount().currency());
        if (callback != null) {
            payload.put("resultingStatus", callback.resultingStatus().name());
            payload.put("providerNotificationId", callback.providerNotificationId());
            if (callback.providerReference() != null) {
                payload.put("providerReference", callback.providerReference());
            }
        }
        if (refund != null) {
            payload.put("refundAmountMinor", refund.amount().amountMinor());
            payload.put("refundCurrency", refund.amount().currency());
            if (refund.idempotencyToken() != null) {
                payload.put("refundIdempotencyToken", refund.idempotencyToken());
            }
        }
        try {
            return JSON.writeValueAsString(payload);
        } catch (JacksonException ex) {
            throw new PaymentAdapterSendFailedException();
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
