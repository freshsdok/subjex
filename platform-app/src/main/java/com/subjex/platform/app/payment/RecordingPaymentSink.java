package com.subjex.platform.app.payment;

import com.subjex.platform.contract.payment.PaymentDocument;
import com.subjex.platform.contract.payment.PaymentResultCallback;
import com.subjex.platform.contract.payment.RefundCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * RecordingPaymentSink — 开发/测试用内存接收槽：记下模拟适配器看到的回调与退款意图，不调用厂商。
 * <p>
 * This is not a ledger and not a second outbox. The port still persists vouchers and outbox rows.
 * 这不是台账，也不是第二出箱。凭证与出箱行仍由端口落库。
 */
public final class RecordingPaymentSink {

    private final List<PaymentResultCallback> results = new ArrayList<>();
    private final List<RefundCommand> refunds = new ArrayList<>();

    public void acceptPaymentResult(PaymentDocument current, PaymentResultCallback callback) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(callback, "callback");
        results.add(callback);
    }

    public void acceptRefund(PaymentDocument current, RefundCommand command) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(command, "command");
        refunds.add(command);
    }

    public List<PaymentResultCallback> results() {
        return List.copyOf(results);
    }

    public List<RefundCommand> refunds() {
        return List.copyOf(refunds);
    }
}
