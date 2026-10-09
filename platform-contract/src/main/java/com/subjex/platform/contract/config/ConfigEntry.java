package com.subjex.platform.contract.config;

import java.util.Objects;

/**
 * ConfigEntry — 配置条目：命名空间、键、生效值、来源层，以及覆盖修订号。
 * <p>
 * Local (non-override) rows use revision {@code 0}. Override rows carry the store revision (≥ 1).
 * 非覆盖行修订号为 0；覆盖行带存放处修订号（≥ 1）。
 */
public record ConfigEntry(String namespace, String key, String value, ConfigOrigin origin, long revision) {

    public ConfigEntry {
        namespace = ConfigNamespaces.require(namespace);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("config key is missing");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("config value is missing");
        }
        Objects.requireNonNull(origin, "origin");
        if (revision < 0) {
            throw new IllegalArgumentException("config revision must not be negative");
        }
    }

    /**
     * Flat constructor (default namespace, revision 0) — 扁平构造（默认命名空间、修订 0）。
     * Prefer the full constructor when namespace/revision are known.
     * 已知命名空间/修订时优先用完整构造。
     */
    public ConfigEntry(String key, String value, ConfigOrigin origin) {
        this(ConfigNamespaces.DEFAULT, key, value, origin, 0L);
    }
}
