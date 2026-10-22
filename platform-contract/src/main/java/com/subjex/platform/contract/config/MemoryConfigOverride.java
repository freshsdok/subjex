package com.subjex.platform.contract.config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MemoryConfigOverride — 内存配置覆盖：按命名空间在本进程内存里压过具名键（测试用）。
 * Config-6a: retains append-only history in-process.
 * Config-6a：进程内保留只追加历史。
 */
public final class MemoryConfigOverride implements ConfigOverrideStore {

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Versioned>> byNamespace =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<ConfigOverrideHistoryEntry>> historyRows =
            new ConcurrentHashMap<>();

    @Override
    public long put(String namespace, String key, String value) {
        String ns = ConfigNamespaces.require(namespace);
        requireKeyValue(key, value);
        ConcurrentHashMap<String, Versioned> bucket =
                byNamespace.computeIfAbsent(ns, ignored -> new ConcurrentHashMap<>());
        Versioned next = bucket.compute(key, (k, previous) -> {
            long revision = previous == null ? 1L : previous.revision() + 1L;
            return new Versioned(value, revision);
        });
        historyRows
                .computeIfAbsent(ns + "\0" + key, ignored -> new CopyOnWriteArrayList<>())
                .add(new ConfigOverrideHistoryEntry(ns, key, value, next.revision(), Instant.now()));
        return next.revision();
    }

    @Override
    public List<ConfigOverrideHistoryEntry> history(String namespace, String key) {
        if (key == null || key.isBlank()) {
            return List.of();
        }
        String ns = ConfigNamespaces.require(namespace);
        CopyOnWriteArrayList<ConfigOverrideHistoryEntry> rows = historyRows.get(ns + "\0" + key);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<ConfigOverrideHistoryEntry> copy = new ArrayList<>(rows);
        return Collections.unmodifiableList(copy);
    }

    @Override
    public Optional<String> lookup(String namespace, String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        ConcurrentHashMap<String, Versioned> bucket = byNamespace.get(ConfigNamespaces.require(namespace));
        if (bucket == null) {
            return Optional.empty();
        }
        Versioned found = bucket.get(key);
        return found == null ? Optional.empty() : Optional.of(found.value());
    }

    @Override
    public Set<String> keys(String namespace) {
        ConcurrentHashMap<String, Versioned> bucket = byNamespace.get(ConfigNamespaces.require(namespace));
        return bucket == null ? Set.of() : Set.copyOf(bucket.keySet());
    }

    @Override
    public Optional<Long> revision(String namespace, String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        ConcurrentHashMap<String, Versioned> bucket = byNamespace.get(ConfigNamespaces.require(namespace));
        if (bucket == null) {
            return Optional.empty();
        }
        Versioned found = bucket.get(key);
        return found == null ? Optional.empty() : Optional.of(found.revision());
    }

    private static void requireKeyValue(String key, String value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("config key is missing");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("config value is missing");
        }
    }

    private record Versioned(String value, long revision) {}
}
