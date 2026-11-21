package com.subjex.platform.contract.payment;

/**
 * PaymentPort — 统一支付端口：创单、查单、幂等支付结果回调与退款（含部分退款）。
 * <p>
 * Domain modules create a payment document, apply a provider (or mock) result, refund, and query
 * status here. The port must not call {@link com.subjex.platform.contract.task.TaskMessagePort#submit}
 * and must not invent a second outbox. Status changes write {@code PaymentStatusChanged} onto the
 * existing {@code outbox_event} bus for {@code DeliveryPort} delivery. This port does not auto-advance
 * workflows or enqueue notifications.
 * 域模块在此创单、应用厂商（或模拟）结果、退款并查询单据状态。不得调用 {@code TaskMessagePort.submit}，
 * 也不得另起出箱。状态变化在已有 {@code outbox_event} 上写 {@code PaymentStatusChanged}，经 {@code DeliveryPort}
 * 投递。本端口不自动推进工作流，也不入队通知。
 * <p>
 * Every call requires an explicit business tenant; reserved {@code platform} is never a default.
 * Missing, unknown, or non-ACTIVE tenants fail closed. Cross-tenant callback, refund, or query is denied.
 * 每次调用必须带明确业务租户；保留租户 {@code platform} 绝不是默认值。缺失/未知/非 ACTIVE 失败关闭。禁止跨租户回调、退款或查单。
 */
public interface PaymentPort {

    /**
     * Persist a new payment document and a create voucher; return the current document.
     * 落下一张新支付单及创单凭证，并返回当前单据。
     */
    PaymentDocument createOrder(CreatePaymentCommand command);

    /**
     * Return the payment document for this tenant and payment id (source of truth).
     * 按租户与支付单标识返回单据（状态真相源）。
     */
    PaymentDocument queryOrder(String tenantId, String paymentId);

    /**
     * Apply a provider payment result. Duplicate same-id + same-fingerprint callbacks are a no-op.
     * 应用厂商支付结果。同一通知标识且指纹一致的重复回调为空操作（回放已入账单据）。
     */
    PaymentDocument applyPaymentResult(PaymentResultCallback callback);

    /**
     * Apply a refund, including partial. Duplicate same-token + same-fingerprint refunds are a no-op.
     * 申请退款（含部分退款）。同一退款令牌且指纹一致的重复申请为空操作。
     */
    PaymentDocument refund(RefundCommand command);
}
