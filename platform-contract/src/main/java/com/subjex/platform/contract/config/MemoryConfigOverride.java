package com.subjex.platform.contract.config;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MemoryConfigOverride — 内存配置覆盖：按命名空间在本进程内存里压过具名键（测试用）。
 */
public final class MemoryConfigOverride implements ConfigOverrideStore {

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Versioned>> byNamespace =
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
        return next.revision();
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
