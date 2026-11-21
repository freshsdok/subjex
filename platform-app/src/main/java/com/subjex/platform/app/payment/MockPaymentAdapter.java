package com.subjex.platform.app.payment;

import com.subjex.platform.contract.payment.PaymentDocument;
import com.subjex.platform.contract.payment.PaymentProviderAdapter;
import com.subjex.platform.contract.payment.PaymentProviderIds;
import com.subjex.platform.contract.payment.PaymentResultCallback;
import com.subjex.platform.contract.payment.RefundCommand;
import java.util.Objects;

/**
 * MockPaymentAdapter — 开发/测试模拟适配器：记录意图，不调用商业接口，不打印密钥。
 * <p>
 * The port still applies status, vouchers, and outbox rows. A later vendor bean can replace this adapter.
 * 状态、凭证与出箱仍由端口入账。后续厂商 Bean 可以替换本适配器。
 */
public final class MockPaymentAdapter implements PaymentProviderAdapter {

    private final RecordingPaymentSink sink;

    public MockPaymentAdapter(RecordingPaymentSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public String providerId() {
        return PaymentProviderIds.MOCK;
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public void acceptPaymentResult(PaymentDocument current, PaymentResultCallback callback) {
        sink.acceptPaymentResult(current, callback);
    }

    @Override
    public void acceptRefund(PaymentDocument current, RefundCommand command) {
        sink.acceptRefund(current, command);
    }
}
