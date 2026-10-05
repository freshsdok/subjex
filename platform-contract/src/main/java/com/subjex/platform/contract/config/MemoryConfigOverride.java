package com.subjex.platform.contract.config;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MemoryConfigOverride — 内存配置覆盖：第二个配置来源，只在本进程的内存里压过具名键。
 * <p>
 * Tests use it to replace one key. It does not listen on the network.
 * 测试用它替换一个键。它不在网络上监听。
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
}
