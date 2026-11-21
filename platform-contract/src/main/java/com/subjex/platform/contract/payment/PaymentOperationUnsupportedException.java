package com.subjex.platform.contract.payment;

/**
 * PaymentOperationUnsupportedException — 本切片未实现的支付操作：必须失败关闭，不得空操作。
 * <p>
 * Thrown for payment-result callback and refund until WP6-2 implements them.
 * 在 WP6-2 实现回调与退款之前抛出。
 */
public final class PaymentOperationUnsupportedException extends RuntimeException {

    public PaymentOperationUnsupportedException(String operation) {
        super("payment operation is unsupported until WP6-2: " + operation);
    }
}
