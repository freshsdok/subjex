package com.subjex.platform.contract.config;

import java.time.Instant;
import java.util.Objects;

/**
 * ConfigOverrideHistoryEntry — one append-only override put (Config-6a / ADR 0012).
 * 配置覆盖历史条目：一次只追加的覆盖写入（Config-6a / ADR 0012）。
 * <p>
 * Not the effective SoT — {@code config_override} still holds the live value.
 * 不是生效真相；生效值仍在 config_override。
 */
public record ConfigOverrideHistoryEntry(
        String namespace, String key, String value, long revision, Instant overriddenAt) {

    public ConfigOverrideHistoryEntry {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(overriddenAt, "overriddenAt");
        if (revision < 1L) {
            throw new IllegalArgumentException("revision must be >= 1");
        }
    }
}
