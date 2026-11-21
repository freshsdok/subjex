package com.subjex.platform.contract.payment;

/**
 * PaymentNotFoundException — 指定租户下找不到该支付单。
 * <p>
 * Thrown when the payment id does not exist (after the calling tenant has already been validated).
 * 在调用租户已通过校验后，若支付单标识不存在则抛出。
 */
public final class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(String tenantId, String paymentId) {
        super("payment not found: tenant " + tenantId + " payment " + paymentId);
    }
}
