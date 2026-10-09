package com.subjex.platform.contract.delivery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * DeliveryCircuitBreaker — 出箱投递熔断器：连续失败达到阈值后暂时不再调用所选传输。
 * <p>
 * The breaker wraps <strong>only the selected</strong> {@link DeliveryPort}.
 * A Kafka failure must not open the socket path (and the reverse), because one process only wires one transport.
 * A success clears the streak. Once open, calls are refused until {@code openCooldown} elapses, then one probe
 * is allowed (half-open) so background relay can recover after a transient outage.
 * 熔断器只包所选传输。一次成功清掉连续失败；打开后冷却期内拒呼，结束后允许一次半开探测。
 * Implements {@link DeliveryCircuitBreakerPort} (Scale-4a). Shared JDBC backend is Scale-4c.
 */
public final class DeliveryCircuitBreaker implements DeliveryCircuitBreakerPort {

    private final int failureThreshold;
    private final Duration openCooldown;
    private final Clock clock;
    private int consecutiveFailures;
    private boolean open;
    private Instant openedAt;

    public DeliveryCircuitBreaker(int failureThreshold) {
        this(failureThreshold, Duration.ofSeconds(30), Clock.systemUTC());
    }

    public DeliveryCircuitBreaker(int failureThreshold, Duration openCooldown, Clock clock) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failure threshold must be at least 1");
        }
        if (openCooldown == null || openCooldown.isNegative()) {
            throw new IllegalArgumentException("open cooldown must not be negative");
        }
        this.failureThreshold = failureThreshold;
        this.openCooldown = openCooldown;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized boolean allowCall() {
        if (!open) {
            return true;
        }
        Instant readyAt = openedAt.plus(openCooldown);
        return !clock.instant().isBefore(readyAt);
    }

    @Override
    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        open = false;
        openedAt = null;
    }

    @Override
    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            open = true;
            openedAt = clock.instant();
        }
    }

    @Override
    public synchronized boolean isOpen() {
        return open && !allowCall();
    }

    /** Whether the breaker has tripped (may still be in half-open probe window). / 是否已跳闸（半开探测窗口内仍为 true）。 */
    @Override
    public synchronized boolean isTripped() {
        return open;
    }
}
