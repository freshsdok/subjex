package com.subjex.platform.contract.payment;

import java.util.Map;
import java.util.Objects;

/**
 * PaymentChargePayload — generic provider payload the client can use after start-charge.
 * 发起扣款后返回给客户端的通用供应载荷。不是某一商业 SDK 的结账产品。
 * <p>
 * {@code params} is an opaque map from the adapter (mock test fields, or an HTTP-template receipt).
 * The kernel does not invent WeChat Pay or Alipay checkout fields.
 * {@code params} 是适配器给出的不透明映射（模拟测试字段，或 HTTP 模板回执）。
 * 内核不编造微信支付或支付宝结账字段。
 */
public record PaymentChargePayload(String providerId, Map<String, Object> params) {

    public PaymentChargePayload {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId required");
        }
        providerId = providerId.trim();
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
