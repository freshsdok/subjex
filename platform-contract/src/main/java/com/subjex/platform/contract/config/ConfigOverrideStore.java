package com.subjex.platform.contract.config;

import java.util.Set;

/**
 * ConfigOverrideStore — 配置覆盖存放处：压过具名键的那一层，可写、可列出键。
 * <p>
 * platform-app stores overrides in the shared database so every process on that database sees the same value.
 * {@link MemoryConfigOverride} keeps them in one JVM and is for tests.
 * platform-app 把覆盖存进共享数据库，连同一个库的进程看到同一个值。{@link MemoryConfigOverride} 只在一个 JVM 里保存，用于测试。
 */
public interface ConfigOverrideStore extends ConfigSource {

    /**
     * Replace one named key — 压过一个具名键。
     */
    void override(String key, String value);

    /**
     * Keys currently overridden — 当前被覆盖的键。
     */
    Set<String> keys();
}
