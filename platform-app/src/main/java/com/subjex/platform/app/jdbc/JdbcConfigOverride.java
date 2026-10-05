package com.subjex.platform.app.jdbc;

import com.subjex.platform.contract.config.ConfigOverrideStore;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcConfigOverride — JDBC 配置覆盖：压过具名键的值存在共享库的 {@code config_override} 表里。
 * <p>
 * Every process on the same database, and the same process after a restart, reads the same override.
 * Local application config is still the base layer. This is not a configuration server.
 * 连同一个库的每个进程、以及重启后的同一进程，读到同一个覆盖值。本地应用配置仍是底层。这不是配置服务器。
 */
public final class JdbcConfigOverride implements ConfigOverrideStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcConfigOverride(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void override(String key, String value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("config key is missing");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("config value is missing");
        }
        int updated = update(key, value);
        if (updated == 0) {
            try {
                jdbc.update(
                        "INSERT INTO config_override (config_key, config_value, overridden_at) VALUES (?, ?, ?)",
                        key, value, PlatformTables.timestamp(clock.instant()));
            } catch (DuplicateKeyException raced) {
                update(key, value);
            }
        }
    }

    @Override
    public Optional<String> lookup(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return jdbc.queryForList(
                "SELECT config_value FROM config_override WHERE config_key = ?", String.class, key)
                .stream().findFirst();
    }

    @Override
    public Set<String> keys() {
        return Set.copyOf(jdbc.queryForList("SELECT config_key FROM config_override", String.class));
    }

    private int update(String key, String value) {
        return jdbc.update(
                "UPDATE config_override SET config_value = ?, overridden_at = ? WHERE config_key = ?",
                value, PlatformTables.timestamp(clock.instant()), key);
    }
}
