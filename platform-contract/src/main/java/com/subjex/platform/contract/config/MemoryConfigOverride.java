package com.subjex.platform.contract.config;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MemoryConfigOverride — 内存配置覆盖：第二个配置来源，只在本进程的内存里压过具名键。
 * <p>
 * platform-app keeps these overrides and serves them over HTTP. It does not listen by itself.
 * platform-app 把这些覆盖留在内存里，再用 HTTP 提供出去。它自己不监听网络。
 */
public final class MemoryConfigOverride implements ConfigSource {

    private final ConcurrentHashMap<String, String> entries = new ConcurrentHashMap<>();

    /**
     * Replace one named key — 压过一个具名键。
     */
    public void override(String key, String value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("config key is missing");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("config value is missing");
        }
        entries.put(key, value);
    }

    @Override
    public Optional<String> lookup(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(key));
    }

    /**
     * Keys currently held in memory — 当前内存里的键。
     */
    public Set<String> keys() {
        return Set.copyOf(entries.keySet());
    }
}
