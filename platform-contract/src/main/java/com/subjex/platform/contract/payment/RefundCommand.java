package com.subjex.platform.contract.payment;

import java.util.Objects;

/**
 * RefundCommand — 退款命令（含部分退款）：必须与原单同币种，金额严格为正且不超过剩余可退额。
 * <p>
 * When {@code idempotencyToken} is present, a replay with the same fingerprint returns the original
 * refund line and must not increase totals. {@code tenantId} may be blank so the port can fail closed.
 * 若带 {@code idempotencyToken}，相同指纹的回放返回原退款行，且不得增加已退总额。允许 {@code tenantId} 为空以便端口失败关闭。
 */
public record RefundCommand(
        String tenantId,
        String paymentId,
        PaymentAmount amount,
        String idempotencyToken) {

    public RefundCommand {
        if (tenantId != null) {
            tenantId = tenantId.trim();
        }
        if (paymentId != null) {
            paymentId = paymentId.trim();
        }
        amount = Objects.requireNonNull(amount, "amount");
        if (idempotencyToken != null && idempotencyToken.isBlank()) {
            idempotencyToken = null;
        } else if (idempotencyToken != null) {
            idempotencyToken = idempotencyToken.trim();
        }
    }
}
