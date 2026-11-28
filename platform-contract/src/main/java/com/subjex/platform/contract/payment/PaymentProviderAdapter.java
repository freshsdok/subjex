package com.subjex.platform.contract.payment;

/**
 * PaymentProviderAdapter — 单一供应商的支付 SPI：内核不写死商业厂商，后续适配器可插入。
 * <p>
 * Phase 1 ships a mock/dev adapter. Production must fail closed when no real adapter is configured
 * ({@link #configured()} is {@code false}): do not mark a payment succeeded as a silent no-op.
 * Implementations must not call a commercial API from the mock, and must not log secrets.
 * 首期提供模拟/开发适配器。生产未配置真实适配器时必须失败关闭，不得把未支付静默写成成功。
 * 模拟实现不得调用商业接口，也不得在日志中打印密钥。
 */
public interface PaymentProviderAdapter {

    /** Provider identifier this adapter serves (for example {@code MOCK}) / 本适配器负责的供应商标识（例如 {@code MOCK}） */
    String providerId();

    /**
     * Whether this adapter may actually apply a result (production requires a real vendor bean).
     * 是否真正可入账（生产必须换成真实厂商实现）。
     */
    boolean configured();

    /**
     * Accept a payment-result callback for an existing document. Must throw when not {@link #configured()}.
     * 接受已有单据的支付结果回调。未配置时必须抛错，不得空操作。
     */
    void acceptPaymentResult(PaymentDocument current, PaymentResultCallback callback);

    /**
     * Accept a refund intent for an existing document. Must throw when not {@link #configured()}.
     * 接受已有单据的退款意图。未配置时必须抛错，不得空操作。
     */
    void acceptRefund(PaymentDocument current, RefundCommand command);

    /**
     * Start charge for an existing pending document and return a generic client payload.
     * Must throw when not {@link #configured()}. Must not mark the document succeeded.
     * 对已有待支付单据发起扣款并返回通用客户端载荷。未配置时必须抛错。不得把单据写成成功。
     */
    PaymentChargePayload startCharge(PaymentDocument current);
}
