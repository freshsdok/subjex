package com.subjex.gateway;

import com.subjex.platform.contract.ratelimit.RateLimitPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GatewayRateLimit — 网关限流：{@link RateLimitPort} 在入口进程里的实现。
 * <p>
 * Counts inside this JVM for one window. It is not a second port and it is not Redis.
 * 在本 JVM 的一个时间窗里计数。它不是第二个端口，也不是 Redis。
 */
public final class GatewayRateLimit implements RateLimitPort {

    private final int permits;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public GatewayRateLimit(int permits, Duration window, Clock clock) {
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be at least 1");
        }
        this.permits = permits;
        this.window = window;
        this.clock = clock;
    }

    @Override
    public synchronized boolean permit(String clientId, String actionName) {
        if (clientId == null || clientId.isBlank() || actionName == null || actionName.isBlank()) {
            throw new IllegalArgumentException("client and action are required");
        }
        String key = clientId + "/" + actionName;
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

    private record Window(Instant startedAt, int count) {}
}
