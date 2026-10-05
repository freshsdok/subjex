package com.subjex.platform.contract.config;

import java.util.Objects;

/**
 * ConfigEntry — 配置条目：一个键、生效值，以及它从哪一层来。
 * <p>
 * This is one row on the human page and one object on the shared HTTP entry.
 * 这是人看的页面上的一行，也是共享 HTTP 条目上的一个对象。
 */
public record ConfigEntry(String key, String value, ConfigOrigin origin) {

    public ConfigEntry {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("config key is missing");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("config value is missing");
        }
        Objects.requireNonNull(origin, "origin");
    }
}
