package com.subjex.platform.app.config;

import com.subjex.platform.contract.config.ConfigCenterPort;
import com.subjex.platform.contract.config.ConfigEntry;
import com.subjex.platform.contract.config.ConfigListing;
import com.subjex.platform.contract.config.ConfigNamespaces;
import com.subjex.platform.contract.config.ConfigOrigin;
import com.subjex.platform.contract.config.ConfigOverrideStore;
import com.subjex.platform.contract.discovery.PlatformServiceNames;
import com.subjex.platform.contract.discovery.StaticServiceFallback;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ConfigCatalog — 配置目录：按命名空间列出关注的键与覆盖，并带修订号（Config-5b）。
 * <p>
 * Default namespace keeps today’s flat keys + local application fallthrough. Other namespaces are
 * override-only. Implements {@link ConfigCenterPort}.
 * 默认命名空间保留扁平键与本地回落；其它命名空间仅覆盖层。
 */
public final class ConfigCatalog implements ConfigCenterPort {

    static final List<String> WATCHED_KEYS = List.of(
            StaticServiceFallback.hostKey(PlatformServiceNames.PLATFORM_APP),
            StaticServiceFallback.portKey(PlatformServiceNames.PLATFORM_APP));

    private final ConfigListing listing;
    private final ConfigOverrideStore overrides;

    public ConfigCatalog(ConfigListing listing, ConfigOverrideStore overrides) {
        this.listing = Objects.requireNonNull(listing, "listing");
        this.overrides = Objects.requireNonNull(overrides, "overrides");
    }

    @Override
    public List<ConfigEntry> list(String namespace) {
        String ns = ConfigNamespaces.require(namespace);
        List<ConfigEntry> rows = new ArrayList<>();
        if (ConfigNamespaces.DEFAULT.equals(ns)) {
            for (ConfigEntry row : listing.rows(WATCHED_KEYS)) {
                rows.add(enrich(ns, row));
            }
        }
        for (String key : overrides.keys(ns)) {
            if (rows.stream().noneMatch(row -> row.key().equals(key) && row.namespace().equals(ns))) {
                get(ns, key).ifPresent(rows::add);
            }
        }
        rows.sort(Comparator.comparing(ConfigEntry::key));
        return List.copyOf(rows);
    }

    /** @deprecated prefer {@link #get(String)} — 请用 {@link #get(String)} */
    public Optional<ConfigEntry> entry(String key) {
        return get(key);
    }

    @Override
    public Optional<ConfigEntry> get(String namespace, String key) {
        String ns = ConfigNamespaces.require(namespace);
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        Optional<String> overridden = overrides.lookup(ns, key);
        if (overridden.isPresent()) {
            long revision = overrides.revision(ns, key).orElse(1L);
            return Optional.of(new ConfigEntry(ns, key, overridden.get(), ConfigOrigin.OVERRIDE, revision));
        }
        if (!ConfigNamespaces.DEFAULT.equals(ns)) {
            return Optional.empty();
        }
        return listing.entry(key).map(row -> enrich(ns, row));
    }

    /** @deprecated prefer {@link #put(String, String)} — 请用 {@link #put(String, String)} */
    public void override(String key, String value) {
        put(key, value);
    }

    @Override
    public ConfigEntry put(String key, String value) {
        return put(ConfigNamespaces.DEFAULT, key, value);
    }

    @Override
    public ConfigEntry put(String namespace, String key, String value) {
        String ns = ConfigNamespaces.require(namespace);
        long revision = overrides.put(ns, key, value);
        return new ConfigEntry(ns, key, value, ConfigOrigin.OVERRIDE, revision);
    }

    private ConfigEntry enrich(String namespace, ConfigEntry row) {
        if (row.origin() == ConfigOrigin.OVERRIDE) {
            long revision = overrides.revision(namespace, row.key()).orElse(row.revision() > 0 ? row.revision() : 1L);
            return new ConfigEntry(namespace, row.key(), row.value(), ConfigOrigin.OVERRIDE, revision);
        }
        return new ConfigEntry(namespace, row.key(), row.value(), row.origin(), 0L);
    }
}
