package com.subjex.platform.app.config;

import com.subjex.platform.contract.config.ConfigEntry;
import com.subjex.platform.contract.config.ConfigListing;
import com.subjex.platform.contract.config.ConfigOverrideStore;
import com.subjex.platform.contract.discovery.PlatformServiceNames;
import com.subjex.platform.contract.discovery.StaticServiceFallback;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ConfigCatalog — 配置目录：只列出给人看的键，并说清生效值从哪一层来。
 * <p>
 * The watched keys are local discovery host and port, plus any stored override.
 * This is not every property in the Spring Environment.
 * 关注的键是本地发现主机和端口，再加上已存的覆盖。
 * 不是 Spring Environment 里的每一个属性。
 */
public final class ConfigCatalog {

    static final List<String> WATCHED_KEYS = List.of(
            StaticServiceFallback.hostKey(PlatformServiceNames.PLATFORM_APP),
            StaticServiceFallback.portKey(PlatformServiceNames.PLATFORM_APP));

    private final ConfigListing listing;
    private final ConfigOverrideStore overrides;

    public ConfigCatalog(ConfigListing listing, ConfigOverrideStore overrides) {
        this.listing = Objects.requireNonNull(listing, "listing");
        this.overrides = Objects.requireNonNull(overrides, "overrides");
    }

    public List<ConfigEntry> list() {
        return listing.rows(WATCHED_KEYS);
    }

    public Optional<ConfigEntry> entry(String key) {
        return listing.entry(key);
    }

    public void override(String key, String value) {
        overrides.override(key, value);
    }
}
