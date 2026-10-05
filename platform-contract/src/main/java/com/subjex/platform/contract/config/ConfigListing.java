package com.subjex.platform.contract.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ConfigListing — 配置名单：把底层和覆盖层收成一行一行，每行写明谁赢了。
 * <p>
 * An override wins when it has the key. Otherwise the base wins. Keys only in the override are listed too.
 * This is not a dump of every framework property. The caller passes the keys a person should see.
 * 覆盖层有这个键就用覆盖层，否则用底层。只在覆盖层出现的键也会列出。
 * 这不是把框架里每个属性都倒出来。调用方给出给人看的那些键。
 */
public final class ConfigListing {

    private final ConfigSource override;
    private final ConfigSource base;

    public ConfigListing(ConfigSource override, ConfigSource base) {
        this.override = Objects.requireNonNull(override, "override");
        this.base = Objects.requireNonNull(base, "base");
    }

    public Optional<String> effective(String key) {
        return override.lookup(key).or(() -> base.lookup(key));
    }

    public Optional<ConfigEntry> entry(String key) {
        Optional<String> overridden = override.lookup(key);
        if (overridden.isPresent()) {
            return Optional.of(new ConfigEntry(key, overridden.get(), ConfigOrigin.OVERRIDE));
        }
        return base.lookup(key).map(value -> new ConfigEntry(key, value, ConfigOrigin.LOCAL));
    }

    /**
     * Rows for the given keys, plus any extra keys the override holds.
     * 给定键的行，再加上覆盖层里多出来的键。
     */
    public List<ConfigEntry> rows(Iterable<String> knownKeys) {
        List<ConfigEntry> rows = new ArrayList<>();
        for (String key : knownKeys) {
            entry(key).ifPresent(rows::add);
        }
        if (override instanceof MemoryConfigOverride memory) {
            for (String key : memory.keys()) {
                if (rows.stream().noneMatch(row -> row.key().equals(key))) {
                    entry(key).ifPresent(rows::add);
                }
            }
        }
        rows.sort(Comparator.comparing(ConfigEntry::key));
        return List.copyOf(rows);
    }
}
