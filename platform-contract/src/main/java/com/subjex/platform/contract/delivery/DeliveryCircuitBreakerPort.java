package com.subjex.platform.contract.delivery;

import java.time.Duration;
import java.time.Instant;

/**
 * DeliveryCircuitBreakerPort — 出箱投递熔断端口：允许调用 / 记成功失败 / 是否打开。
 * <p>
 * Scale-4a extraction. Default is in-process {@link DeliveryCircuitBreaker}; platform-app may share
 * state via JDBC ({@code platform.delivery.circuit-breaker.backend=jdbc}, Scale-4c).
 * 默认进程内实现；platform-app 可用 JDBC 共享状态（Scale-4c）。
 */
public interface DeliveryCircuitBreakerPort {

    /** @return {@code true} when the selected transport may be called / 所选传输可调用时为 true */
    boolean allowCall();

    void recordSuccess();

    void recordFailure();

    /** Open and still inside cooldown (calls refused) — 已打开且仍在冷却（拒呼）。 */
    boolean isOpen();

    /** Tripped (may be half-open probe window) — 已跳闸（半开探测窗口内仍可为 true）。 */
    boolean isTripped();

    /**
     * Current open / closed / half-open state for operator observation (not a metrics product).
     * 供操作员查看的当前开合状态（不是指标产品）。
     */
    BreakerSnapshot snapshot();

    /**
     * Readable breaker state. {@code state} is {@code closed}, {@code open}, or {@code half-open}.
     * 可读熔断状态。{@code state} 为 closed、open 或 half-open。
     */
    record BreakerSnapshot(
            String state,
            int consecutiveFailures,
            int failureThreshold,
            Duration openCooldown,
            Instant openedAt,
            Instant cooldownEndsAt) {
        public static final String CLOSED = "closed";
        public static final String OPEN = "open";
        public static final String HALF_OPEN = "half-open";
    }
}
