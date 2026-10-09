package com.subjex.platform.app.delivery;

import com.subjex.platform.app.jdbc.PlatformTables;
import com.subjex.platform.contract.delivery.DeliveryCircuitBreakerPort;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcDeliveryCircuitBreakerPort — JDBC 共享投递熔断：状态存在 {@code delivery_circuit_breaker}。
 * <p>
 * Processes sharing the database share one open/closed streak per destination key.
 * Default wiring still uses in-process {@link com.subjex.platform.contract.delivery.DeliveryCircuitBreaker};
 * select this with {@code platform.delivery.circuit-breaker.backend=jdbc}.
 * 共用库的进程按目的键共享开合状态；默认仍进程内；{@code jdbc} 后端选用本实现。
 */
public final class JdbcDeliveryCircuitBreakerPort implements DeliveryCircuitBreakerPort {

    private final JdbcTemplate jdbc;
    private final String destinationKey;
    private final int failureThreshold;
    private final Duration openCooldown;
    private final Clock clock;

    public JdbcDeliveryCircuitBreakerPort(
            JdbcTemplate jdbc,
            String destinationKey,
            int failureThreshold,
            Duration openCooldown,
            Clock clock) {
        if (destinationKey == null || destinationKey.isBlank()) {
            throw new IllegalArgumentException("destination key is required");
        }
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failure threshold must be at least 1");
        }
        if (openCooldown == null || openCooldown.isNegative()) {
            throw new IllegalArgumentException("open cooldown must not be negative");
        }
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.destinationKey = destinationKey.trim();
        this.failureThreshold = failureThreshold;
        this.openCooldown = openCooldown;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean allowCall() {
        ensureRow();
        Instant openedAt = loadOpenedAt();
        if (openedAt == null) {
            return true;
        }
        Instant readyAt = openedAt.plus(openCooldown);
        return !clock.instant().isBefore(readyAt);
    }

    @Override
    public void recordSuccess() {
        ensureRow();
        jdbc.update(
                """
                UPDATE delivery_circuit_breaker
                SET consecutive_failures = 0, opened_at = NULL
                WHERE destination_key = ?
                """,
                destinationKey);
    }

    @Override
    public void recordFailure() {
        ensureRow();
        Instant now = clock.instant();
        jdbc.update(
                """
                UPDATE delivery_circuit_breaker
                SET consecutive_failures = consecutive_failures + 1,
                    opened_at = CASE
                        WHEN consecutive_failures + 1 >= ? THEN ?
                        ELSE opened_at
                    END
                WHERE destination_key = ?
                """,
                failureThreshold,
                PlatformTables.timestamp(now),
                destinationKey);
    }

    @Override
    public boolean isOpen() {
        Instant openedAt = loadOpenedAt();
        if (openedAt == null) {
            return false;
        }
        Instant readyAt = openedAt.plus(openCooldown);
        return clock.instant().isBefore(readyAt);
    }

    @Override
    public boolean isTripped() {
        return loadOpenedAt() != null;
    }

    private void ensureRow() {
        try {
            jdbc.update(
                    """
                    INSERT INTO delivery_circuit_breaker (destination_key, consecutive_failures, opened_at)
                    VALUES (?, 0, NULL)
                    """,
                    destinationKey);
        } catch (DuplicateKeyException ignored) {
            // already shared
        }
    }

    private Instant loadOpenedAt() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT opened_at FROM delivery_circuit_breaker WHERE destination_key = ?",
                destinationKey);
        if (rows.isEmpty()) {
            return null;
        }
        Object raw = rows.get(0).get("opened_at");
        if (raw == null) {
            return null;
        }
        if (raw instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (raw instanceof Instant instant) {
            return instant;
        }
        throw new IllegalStateException("unexpected opened_at type: " + raw.getClass().getName());
    }
}
