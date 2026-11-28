package com.subjex.platform.app.payment;

import com.subjex.platform.contract.payment.PaymentAdapterUnconfiguredException;
import com.subjex.platform.contract.payment.PaymentChargePayload;
import com.subjex.platform.contract.payment.PaymentDocument;
import com.subjex.platform.contract.payment.PaymentProviderAdapter;
import com.subjex.platform.contract.payment.PaymentResultCallback;
import com.subjex.platform.contract.payment.RefundCommand;
import java.util.Objects;

/**
 * FailClosedPaymentAdapter — 生产占位：未配置真实适配器时失败关闭，绝不把支付写成成功或退款入账。
 * <p>
 * {@link #configured()} is {@code false}. Calls must throw, never silently succeed.
 * {@link #configured()} 为 {@code false}。调用必须抛错，不得静默成功。
 */
public final class FailClosedPaymentAdapter implements PaymentProviderAdapter {

    private final String providerId;

    public FailClosedPaymentAdapter(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId required");
        }
        this.providerId = providerId.trim();
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public void acceptPaymentResult(PaymentDocument current, PaymentResultCallback callback) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(callback, "callback");
        throw new PaymentAdapterUnconfiguredException(providerId);
    }

    @Override
    public void acceptRefund(PaymentDocument current, RefundCommand command) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(command, "command");
        throw new PaymentAdapterUnconfiguredException(providerId);
    }

    @Override
    public PaymentChargePayload startCharge(PaymentDocument current) {
        Objects.requireNonNull(current, "current");
        throw new PaymentAdapterUnconfiguredException(providerId);
    }
}
