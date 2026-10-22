package com.subjex.platform.contract.config;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * ConfigOverrideStore — 配置覆盖存放处：按命名空间压过具名键，可写、可列出键/历史、可查修订号。
 * <p>
 * platform-app stores overrides in the shared database so every process on that database sees the same value.
 * {@link MemoryConfigOverride} keeps them in one JVM and is for tests. Flat {@link #lookup(String)} /
 * {@link #override(String, String)} / {@link #keys()} use {@link ConfigNamespaces#DEFAULT}.
 * platform-app 把覆盖存进共享库。扁平方法走默认命名空间。
 */
public interface ConfigOverrideStore extends ConfigSource {

    /**
     * Replace one named key in the default namespace — 在默认命名空间压过一个具名键。
     */
    default void override(String key, String value) {
        put(ConfigNamespaces.DEFAULT, key, value);
    }

    /**
     * Replace one named key; returns the new revision (≥ 1).
     * 压过一个具名键；返回新修订号（≥ 1）。
     */
    long put(String namespace, String key, String value);

    @Override
    default Optional<String> lookup(String key) {
        return lookup(ConfigNamespaces.DEFAULT, key);
    }

    Optional<String> lookup(String namespace, String key);

    default Set<String> keys() {
        return keys(ConfigNamespaces.DEFAULT);
    }

    /** Keys currently overridden in the namespace — 该命名空间下当前被覆盖的键。 */
    Set<String> keys(String namespace);

    /** Revision of an override row, empty when missing — 覆盖行修订号，无行时为空。 */
    Optional<Long> revision(String namespace, String key);

    /**
     * Append-only history for one key, oldest revision first (Config-6a).
     * 某一键的只追加历史，修订号升序（Config-6a）。
     * Default empty for stores that do not retain history.
     * 不保留历史的存放处默认空列表。
     */
    default List<ConfigOverrideHistoryEntry> history(String namespace, String key) {
        return List.of();
    }
}
