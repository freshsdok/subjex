package com.subjex.platform.app.ratelimit;

import com.subjex.platform.contract.ratelimit.RateLimitPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SingleProcessRateLimit — 单进程限流：{@link RateLimitPort} 的进程内实现（默认）。
 * <p>
 * Counts inside this JVM for one window. Shared across replicas when
 * {@code platform.rate-limit.backend=jdbc} via {@link JdbcRateLimitPort}.
 * 在本 JVM 的一个时间窗里计数。多副本共享时选 {@code jdbc} 后端见 {@link JdbcRateLimitPort}。
 */
public final class SingleProcessRateLimit implements RateLimitPort, RateLimitWindowView {

    private final int permits;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public SingleProcessRateLimit(int permits, Duration window, Clock clock) {
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be at least 1");
        }
        this.permits = permits;
        this.window = window;
        this.clock = clock;
    }

    @Override
    public synchronized boolean permit(String tenantId, String actionName) {
        if (tenantId == null || tenantId.isBlank() || actionName == null || actionName.isBlank()) {
            throw new IllegalArgumentException("tenant and action are required");
        }
        String key = tenantId + "/" + actionName;
        Instant now = clock.instant();
        Window current = windows.get(key);
        if (current == null || !current.startedAt().plus(window).isAfter(now)) {
            windows.put(key, new Window(now, 1));
            return true;
        }
        if (current.count() >= permits) {
            return false;
        }
        windows.put(key, new Window(current.startedAt(), current.count() + 1));
        return true;
    }

    @Override
    public int permits() {
        return permits;
    }

    @Override
    public Duration window() {
        return window;
    }

    @Override
    public synchronized List<WindowRow> activeWindows(Instant now) {
        Instant at = now == null ? clock.instant() : now;
        List<WindowRow> rows = new ArrayList<>();
        for (var entry : windows.entrySet()) {
            Window current = entry.getValue();
            if (current == null || !current.startedAt().plus(window).isAfter(at)) {
                continue;
            }
            rows.add(WindowRow.fromBucket(entry.getKey(), current.startedAt(), current.count(), permits, window));
        }
        rows.sort(Comparator.comparing(WindowRow::bucketKey));
        return List.copyOf(rows);
    }

    private record Window(Instant startedAt, int count) {
    }
}
