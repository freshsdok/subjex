package com.subjex.platform.app.payment;

import java.time.Duration;
import java.time.Instant;

/**
 * PaymentLifecycleRules — documented clocks for pending expiry and the refund window.
 * 待支付过期与退款窗口的已记录时钟。
 * <p>
 * Unpaid {@code PENDING} orders expire 30 minutes after {@code created_at}. Successful payments
 * stay refundable for 7 days after {@code succeeded_at} (the instant the document first became
 * {@code SUCCEEDED}). Partial refunds use that same success clock, not the last refund time.
 * 未支付的待支付单据自 {@code created_at} 起 30 分钟过期。成功支付自首次 {@code SUCCEEDED}
 * 的 {@code succeeded_at} 起 7 日内可退。部分退款也以该成功时间为准，不以最近一次退款重新起算。
 */
public final class PaymentLifecycleRules {

    /** Pending unpaid TTL, measured from {@code created_at} — 待支付有效期，自 {@code created_at} 起算. */
    public static final Duration PENDING_TTL = Duration.ofMinutes(30);

    /** Refund window, measured from {@code succeeded_at} — 退款窗口，自 {@code succeeded_at} 起算. */
    public static final Duration REFUND_WINDOW = Duration.ofDays(7);

    private PaymentLifecycleRules() {}

    /**
     * Expiry instant for a pending document: persisted {@code expires_at}, or {@code created_at} plus 30 minutes.
     * 待支付过期时刻：已落库的 {@code expires_at}，否则为 {@code created_at} 加 30 分钟。
     */
    public static Instant expiresAt(Instant createdAt, Instant storedExpiresAt) {
        if (storedExpiresAt != null) {
            return storedExpiresAt;
        }
        return createdAt == null ? null : createdAt.plus(PENDING_TTL);
    }

    /** Whether a pending document is past expiry at {@code now} — 待支付单据在 {@code now} 是否已过期. */
    public static boolean pendingExpired(Instant createdAt, Instant storedExpiresAt, Instant now) {
        Instant expires = expiresAt(createdAt, storedExpiresAt);
        return expires != null && now != null && !expires.isAfter(now);
    }

    /**
     * Refund deadline: {@code succeeded_at} plus 7 days. {@code null} means the window cannot be established (fail closed).
     * 退款截止：{@code succeeded_at} 加 7 天。无法确定成功时间时返回 {@code null}（失败关闭）。
     */
    public static Instant refundDeadline(Instant succeededAt) {
        return succeededAt == null ? null : succeededAt.plus(REFUND_WINDOW);
    }

    /** Whether a refund is still allowed at {@code now} — 在 {@code now} 是否仍可退款. */
    public static boolean refundAllowed(Instant succeededAt, Instant now) {
        Instant deadline = refundDeadline(succeededAt);
        return deadline != null && now != null && !now.isAfter(deadline);
    }
}
