package com.subjex.platform.contract.payment;

import java.util.Objects;

/**
 * CreatePaymentCommand — 创单命令：明确业务租户、金额与币种、可选业务引用与幂等令牌。
 * <p>
 * {@code tenantId} may be blank so the port can fail closed (it must never default to {@code platform}).
 * {@code reference} is an opaque domain key; the kernel does not interpret it.
 * 允许 {@code tenantId} 为空以便端口失败关闭（绝不默认 {@code platform}）。
 * {@code reference} 是不透明的域侧引用，内核不予解释。
 */
public record CreatePaymentCommand(
        String tenantId,
        PaymentAmount amount,
        String reference,
        String payerSubjectId,
        String payerRealm,
        String idempotencyToken,
        String providerId) {

    public CreatePaymentCommand {
        if (tenantId != null) {
            tenantId = tenantId.trim();
        }
        amount = Objects.requireNonNull(amount, "amount");
        reference = blankToNull(reference);
        payerSubjectId = blankToNull(payerSubjectId);
        payerRealm = blankToNull(payerRealm);
        idempotencyToken = blankToNull(idempotencyToken);
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId required");
        }
        providerId = providerId.trim();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
