package com.subjex.platform.app.jdbc;

import com.subjex.platform.contract.config.ConfigNamespaces;
import com.subjex.platform.contract.config.ConfigOverrideStore;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcConfigOverride — JDBC 配置覆盖：按命名空间存在共享库 {@code config_override}，带修订号。
 * <p>
 * Every process on the same database shares rows. Flat methods use {@link ConfigNamespaces#DEFAULT}.
 * 连同一库的进程共享行。扁平方法走默认命名空间。
 */
public final class JdbcConfigOverride implements ConfigOverrideStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcConfigOverride(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public long put(String namespace, String key, String value) {
        String ns = ConfigNamespaces.require(namespace);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("config key is missing");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("config value is missing");
        }
        int updated = jdbc.update(
                """
                UPDATE config_override
                SET config_value = ?, revision = revision + 1, overridden_at = ?
                WHERE namespace = ? AND config_key = ?
                """,
                value,
                PlatformTables.timestamp(clock.instant()),
                ns,
                key);
        if (updated == 1) {
            return requireRevision(ns, key);
        }
        try {
            jdbc.update(
                    """
                    INSERT INTO config_override (namespace, config_key, config_value, revision, overridden_at)
                    VALUES (?, ?, ?, 1, ?)
                    """,
                    ns,
                    key,
                    value,
                    PlatformTables.timestamp(clock.instant()));
            return 1L;
        } catch (DuplicateKeyException raced) {
            jdbc.update(
                    """
                    UPDATE config_override
                    SET config_value = ?, revision = revision + 1, overridden_at = ?
                    WHERE namespace = ? AND config_key = ?
                    """,
                    value,
                    PlatformTables.timestamp(clock.instant()),
                    ns,
                    key);
            return requireRevision(ns, key);
        }
    }

    @Override
    public Optional<String> lookup(String namespace, String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        String ns = ConfigNamespaces.require(namespace);
        return jdbc.queryForList(
                        "SELECT config_value FROM config_override WHERE namespace = ? AND config_key = ?",
                        String.class,
                        ns,
                        key)
                .stream()
                .findFirst();
    }

    @Override
    public Set<String> keys(String namespace) {
        String ns = ConfigNamespaces.require(namespace);
        return Set.copyOf(jdbc.queryForList(
                "SELECT config_key FROM config_override WHERE namespace = ?", String.class, ns));
    }

    @Override
    public Optional<Long> revision(String namespace, String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        String ns = ConfigNamespaces.require(namespace);
        return jdbc.queryForList(
                        "SELECT revision FROM config_override WHERE namespace = ? AND config_key = ?",
                        Long.class,
                        ns,
                        key)
                .stream()
                .findFirst();
    }

    private long requireRevision(String namespace, String key) {
        return revision(namespace, key)
                .orElseThrow(() -> new IllegalStateException(
                        "config override revision missing for " + namespace + "/" + key));
    }
}
