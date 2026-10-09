package com.subjex.platform.app.ratelimit;

import com.subjex.platform.app.jdbc.PlatformTables;
import com.subjex.platform.contract.ratelimit.RateLimitPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcRateLimitPort — JDBC 共享限流：{@link RateLimitPort} 把固定时间窗计数存在 {@code rate_limit_window}。
 * <p>
 * Every platform-app process pointed at the same database shares one budget per tenant/action.
 * Default wiring still uses {@link SingleProcessRateLimit}; select this with
 * {@code platform.rate-limit.backend=jdbc}.
 * 指向同一库的每个 platform-app 进程按租户/动作共用一份额度。
 * 默认装配仍是进程内实现；用 {@code platform.rate-limit.backend=jdbc} 选用本实现。
 */
public final class JdbcRateLimitPort implements RateLimitPort {

    private final JdbcTemplate jdbc;
    private final int permits;
    private final Duration window;
    private final Clock clock;

    public JdbcRateLimitPort(JdbcTemplate jdbc, int permits, Duration window, Clock clock) {
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be at least 1");
        }
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.permits = permits;
        this.window = window;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean permit(String tenantId, String actionName) {
        if (tenantId == null || tenantId.isBlank() || actionName == null || actionName.isBlank()) {
            throw new IllegalArgumentException("tenant and action are required");
        }
        String key = tenantId + "/" + actionName;
        Instant now = clock.instant();
        // Active when startedAt.plus(window).isAfter(now) ⇔ window_started_at > now.minus(window)
        Instant activeAfter = now.minus(window);

        if (tryBump(key, activeAfter) == 1) {
            return true;
        }
        try {
            jdbc.update(
                    "INSERT INTO rate_limit_window (bucket_key, window_started_at, permit_count) VALUES (?, ?, 1)",
                    key,
                    PlatformTables.timestamp(now));
            return true;
        } catch (DuplicateKeyException raced) {
            if (tryReset(key, now, activeAfter) == 1) {
                return true;
            }
            return tryBump(key, activeAfter) == 1;
        }
    }

    private int tryBump(String key, Instant activeAfter) {
        return jdbc.update(
                """
                UPDATE rate_limit_window
                SET permit_count = permit_count + 1
                WHERE bucket_key = ?
                  AND window_started_at > ?
                  AND permit_count < ?
                """,
                key,
                PlatformTables.timestamp(activeAfter),
                permits);
    }

    private int tryReset(String key, Instant now, Instant activeAfter) {
        return jdbc.update(
                """
                UPDATE rate_limit_window
                SET window_started_at = ?, permit_count = 1
                WHERE bucket_key = ?
                  AND window_started_at <= ?
                """,
                PlatformTables.timestamp(now),
                key,
                PlatformTables.timestamp(activeAfter));
    }
}
