package com.subjex.platform.contract.payment;

import java.util.Objects;

/**
 * PaymentChargeSession — start-charge result: the unchanged document plus a provider payload.
 * 发起扣款的结果：单据状态不变，外加供应载荷。成功入账仍走 {@link PaymentPort#applyPaymentResult}。
 */
public record PaymentChargeSession(PaymentDocument payment, PaymentChargePayload charge) {

    public PaymentChargeSession {
        payment = Objects.requireNonNull(payment, "payment");
        charge = Objects.requireNonNull(charge, "charge");
    }
}
