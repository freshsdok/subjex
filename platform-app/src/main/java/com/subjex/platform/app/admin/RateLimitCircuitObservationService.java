package com.subjex.platform.app.admin;

import com.subjex.platform.app.ratelimit.RateLimitWindowView;
import com.subjex.platform.contract.delivery.DeliveryCircuitBreakerPort;
import com.subjex.platform.contract.delivery.DeliveryCircuitBreakerPort.BreakerSnapshot;
import com.subjex.platform.contract.ratelimit.RateLimitPort;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * RateLimitCircuitObservationService — read current rate-limit windows and delivery-breaker state.
 * 读取当前限额时间窗与投递熔断开合；挂在已有实现上，不另做指标产品，也不读 Grafana。
 */
public final class RateLimitCircuitObservationService {

    public static final int MAX_WINDOWS = 200;

    private final RateLimitWindowView rateLimit;
    private final DeliveryCircuitBreakerPort circuit;
    private final String rateLimitBackend;
    private final String circuitBackend;
    private final String destinationKey;
    private final Clock clock;

    public RateLimitCircuitObservationService(
            RateLimitPort rateLimit,
            DeliveryCircuitBreakerPort circuit,
            String rateLimitBackend,
            String circuitBackend,
            String destinationKey,
            Clock clock) {
        if (!(rateLimit instanceof RateLimitWindowView view)) {
            throw new IllegalStateException("wired RateLimitPort must expose current window counters");
        }
        this.rateLimit = view;
        this.circuit = Objects.requireNonNull(circuit, "circuit");
        this.rateLimitBackend = normalizeBackend(rateLimitBackend);
        this.circuitBackend = normalizeBackend(circuitBackend);
        this.destinationKey = destinationKey == null || destinationKey.isBlank() ? "outbox" : destinationKey.trim();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ObservationDocument snapshot() {
        Instant now = clock.instant();
        List<RateLimitWindowView.WindowRow> all = rateLimit.activeWindows(now);
        boolean truncated = all.size() > MAX_WINDOWS;
        List<RateLimitWindowView.WindowRow> windows =
                truncated ? List.copyOf(all.subList(0, MAX_WINDOWS)) : all;
        BreakerSnapshot breaker = circuit.snapshot();
        return new ObservationDocument(
                now,
                new RateLimitObservation(
                        rateLimitBackend,
                        isProcessLocal(rateLimitBackend),
                        rateLimit.permits(),
                        rateLimit.window().toSeconds(),
                        windows,
                        truncated,
                        false),
                new CircuitObservation(
                        circuitBackend,
                        isProcessLocal(circuitBackend),
                        destinationKey,
                        breaker.state(),
                        breaker.consecutiveFailures(),
                        breaker.failureThreshold(),
                        breaker.openCooldown().toSeconds(),
                        breaker.openedAt(),
                        breaker.cooldownEndsAt()));
    }

    private static String normalizeBackend(String raw) {
        String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if ("process".equals(value) || "jdbc".equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("backend must be process or jdbc, got: " + raw);
    }

    private static boolean isProcessLocal(String backend) {
        return "process".equals(backend);
    }

    public record ObservationDocument(
            Instant collectedAt, RateLimitObservation rateLimit, CircuitObservation circuit) {}

    /**
     * {@code rejectionCountRecorded} is always false: denials are not stored.
     * 拒绝次数始终为 false：库里没有拒绝记账。
     */
    public record RateLimitObservation(
            String backend,
            boolean processLocal,
            int permits,
            long windowSeconds,
            List<RateLimitWindowView.WindowRow> windows,
            boolean truncated,
            boolean rejectionCountRecorded) {}

    public record CircuitObservation(
            String backend,
            boolean processLocal,
            String destinationKey,
            String state,
            int consecutiveFailures,
            int failureThreshold,
            long openCooldownSeconds,
            Instant openedAt,
            Instant cooldownEndsAt) {}
}
