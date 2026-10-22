package com.subjex.platform.app.jdbc;

import com.subjex.platform.contract.config.ConfigNamespaces;
import com.subjex.platform.contract.config.ConfigOverrideHistoryEntry;
import com.subjex.platform.contract.config.ConfigOverrideStore;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcConfigOverride — JDBC 配置覆盖：按命名空间存在共享库 {@code config_override}，带修订号。
 * <p>
 * Every process on the same database shares rows. Flat methods use {@link ConfigNamespaces#DEFAULT}.
 * Config-6a: each successful put also appends {@code config_override_history}.
 * 连同一库的进程共享行。扁平方法走默认命名空间。Config-6a：每次成功 put 追加历史行。
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
        Instant at = clock.instant();
        Timestamp stamped = PlatformTables.timestamp(at);
        int updated = jdbc.update(
                """
                UPDATE config_override
                SET config_value = ?, revision = revision + 1, overridden_at = ?
                WHERE namespace = ? AND config_key = ?
                """,
                value,
                stamped,
                ns,
                key);
        long revision;
        if (updated == 1) {
            revision = requireRevision(ns, key);
        } else {
            try {
                jdbc.update(
                        """
                        INSERT INTO config_override (namespace, config_key, config_value, revision, overridden_at)
                        VALUES (?, ?, ?, 1, ?)
                        """,
                        ns,
                        key,
                        value,
                        stamped);
                revision = 1L;
            } catch (DuplicateKeyException raced) {
                jdbc.update(
                        """
                        UPDATE config_override
                        SET config_value = ?, revision = revision + 1, overridden_at = ?
                        WHERE namespace = ? AND config_key = ?
                        """,
                        value,
                        stamped,
                        ns,
                        key);
                revision = requireRevision(ns, key);
            }
        }
        // Config-6a: audit row for this revision — 本修订号的审计行。
        appendHistory(ns, key, value, revision, stamped);
        return revision;
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


    @Override
    public List<ConfigOverrideHistoryEntry> history(String namespace, String key) {
        if (key == null || key.isBlank()) {
            return List.of();
        }
        String ns = ConfigNamespaces.require(namespace);
        return jdbc.query(
                """
                SELECT namespace, config_key, config_value, revision, overridden_at
                FROM config_override_history
                WHERE namespace = ? AND config_key = ?
                ORDER BY revision ASC
                """,
                (rs, rowNum) -> new ConfigOverrideHistoryEntry(
                        rs.getString("namespace"),
                        rs.getString("config_key"),
                        rs.getString("config_value"),
                        rs.getLong("revision"),
                        rs.getTimestamp("overridden_at").toInstant()),
                ns,
                key);
    }

    private void appendHistory(String namespace, String key, String value, long revision, Timestamp at) {
        jdbc.update(
                """
                INSERT INTO config_override_history
                    (namespace, config_key, config_value, revision, overridden_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                namespace,
                key,
                value,
                revision,
                at);
    }

    private long requireRevision(String namespace, String key) {
        return revision(namespace, key)
                .orElseThrow(() -> new IllegalStateException(
                        "config override revision missing for " + namespace + "/" + key));
    }
}
