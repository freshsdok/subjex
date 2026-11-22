package com.subjex.platform.app.payment;

/**
 * PaymentAdapterSendFailedException — a configured payment adapter failed to deliver a charge or refund.
 * 已配置的支付适配器在扣费或退款投递时失败。
 * <p>
 * The message is a stable reason only. It must not include the body, account numbers, or API key.
 * 消息只含稳定原因码，不得包含请求体、账号或 API 密钥。
 */
public final class PaymentAdapterSendFailedException extends RuntimeException {

    public static final String REASON = "payment-adapter-send-failed";

    public PaymentAdapterSendFailedException() {
        super(REASON);
    }
}
