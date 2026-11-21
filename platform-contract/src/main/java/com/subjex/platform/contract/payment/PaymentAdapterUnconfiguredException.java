package com.subjex.platform.contract.payment;

/**
 * PaymentAdapterUnconfiguredException — 该供应商没有可用的真实适配器，生产必须失败关闭。
 * <p>
 * Thrown instead of marking a payment succeeded or a refund applied as a silent no-op.
 * 不得把支付成功或退款入账当成静默空操作，应当抛出本异常。
 */
public final class PaymentAdapterUnconfiguredException extends RuntimeException {

    public static final String REASON = "payment-adapter-not-configured";

    public PaymentAdapterUnconfiguredException(String providerId) {
        super("payment adapter not configured for " + providerId);
    }

    public String reason() {
        return REASON;
    }
}
