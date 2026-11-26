package com.subjex.platform.app.payment;

import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PaymentExpiryWorker — thin sweep of overdue PENDING payments on the existing scheduler hook.
 * 在已有定时调度钩子上薄清扫已到期的待支付单据。不是一套新的任务平台。
 */
@Component
public final class PaymentExpiryWorker {

    private final JdbcPaymentPort payments;
    private final int batchSize;

    public PaymentExpiryWorker(
            JdbcPaymentPort payments, @Value("${platform.payment.expire-batch-size:100}") int batchSize) {
        this.payments = Objects.requireNonNull(payments, "payments");
        if (batchSize < 1) {
            throw new IllegalArgumentException("payment expire batch size must be at least 1");
        }
        this.batchSize = batchSize;
    }

    /**
     * Expire PENDING payments whose 30-minute clock has passed — 将已过 30 分钟时钟的待支付单据标为过期.
     */
    @Scheduled(fixedDelayString = "${platform.payment.expire-interval-ms:60000}")
    public void expireOverdue() {
        payments.expireOverdue(batchSize);
    }
}
