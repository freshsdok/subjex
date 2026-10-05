package com.subjex.platform.contract.config;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MemoryConfigOverride — 内存配置覆盖：第二个配置来源，只在本进程的内存里压过具名键。
 * <p>
 * For tests and single-JVM use. platform-app stores overrides in the database instead. It does not listen by itself.
 * 用于测试和单 JVM 场景。platform-app 改为把覆盖存进数据库。它自己不监听网络。
 */
public final class MemoryConfigOverride implements ConfigOverrideStore {

    private final ConcurrentHashMap<String, String> entries = new ConcurrentHashMap<>();

    /**
     * Replace one named key — 压过一个具名键。
     */
    @Override
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
    @Override
    public Set<String> keys() {
        return Set.copyOf(entries.keySet());
    }
}
