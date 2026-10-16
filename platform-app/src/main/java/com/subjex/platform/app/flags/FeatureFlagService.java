package com.subjex.platform.app.flags;

import com.subjex.platform.contract.config.ConfigCenterPort;
import com.subjex.platform.contract.config.ConfigEntry;
import com.subjex.platform.contract.config.ConfigNamespaces;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * FeatureFlagService — resolve boolean flags from config namespace {@code flags}.
 * Resolution: {@code tenantId/key} then platform {@code key}, else {@code defaultValue}.
 * 从 flags 命名空间解析布尔开关：租户键 → 平台键 → 默认值。
 */
public final class FeatureFlagService {

    private final ConfigCenterPort config;

    public FeatureFlagService(ConfigCenterPort config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Whether the flag is enabled for the tenant (null/blank tenant = platform key only).
     * 租户是否开启该开关（空租户只看平台键）。
     */
    public boolean isEnabled(String tenantId, String flagKey, boolean defaultValue) {
        String key = requireFlagKey(flagKey);
        if (tenantId != null && !tenantId.isBlank()) {
            Optional<Boolean> tenant = readEnabled(tenantId.trim() + "/" + key);
            if (tenant.isPresent()) {
                return tenant.get();
            }
        }
        Optional<Boolean> platform = readEnabled(key);
        return platform.orElse(defaultValue);
    }

    /** Storage key for tenant-scoped or platform flag — 租户或平台存储键。 */
    public static String storageKey(String tenantId, String flagKey) {
        String key = requireFlagKey(flagKey);
        if (tenantId == null || tenantId.isBlank()) {
            return key;
        }
        return tenantId.trim() + "/" + key;
    }

    /** Logical flag key from storage key (strip {@code tenantId/} when present) — 从存储键还原逻辑键。 */
    public static String logicalKey(String storageKey, String tenantId) {
        if (storageKey == null) {
            return "";
        }
        if (tenantId != null && !tenantId.isBlank()) {
            String prefix = tenantId.trim() + "/";
            if (storageKey.startsWith(prefix)) {
                return storageKey.substring(prefix.length());
            }
        }
        int slash = storageKey.indexOf('/');
        if (slash > 0) {
            return storageKey.substring(slash + 1);
        }
        return storageKey;
    }

    public static String requireFlagKey(String flagKey) {
        if (flagKey == null || flagKey.isBlank()) {
            throw new IllegalArgumentException("flagKey required");
        }
        String fk = flagKey.trim();
        if (fk.contains("/")) {
            throw new IllegalArgumentException("flagKey must not contain '/'");
        }
        return fk;
    }

    public static Optional<Boolean> parseEnabled(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> Optional.of(true);
            case "false", "0", "no", "off" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    private Optional<Boolean> readEnabled(String storageKey) {
        return config.get(ConfigNamespaces.FLAGS, storageKey).map(ConfigEntry::value).flatMap(FeatureFlagService::parseEnabled);
    }
}
