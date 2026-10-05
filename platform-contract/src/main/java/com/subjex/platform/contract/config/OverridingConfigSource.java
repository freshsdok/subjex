package com.subjex.platform.contract.config;

import java.util.Objects;
import java.util.Optional;

/**
 * OverridingConfigSource — 分层配置来源：覆盖层有这个键就用覆盖层，否则用底层。
 * <p>
 * The base is local application configuration. The override is in-memory. Neither layer is a configuration server.
 * 底层是本地应用配置。覆盖层在内存里。两层都不是配置服务器。
 */
public final class OverridingConfigSource implements ConfigSource {

    private final ConfigSource override;
    private final ConfigSource base;

    public OverridingConfigSource(ConfigSource override, ConfigSource base) {
        this.override = Objects.requireNonNull(override, "override");
        this.base = Objects.requireNonNull(base, "base");
    }

    @Override
    public Optional<String> lookup(String key) {
        return override.lookup(key).or(() -> base.lookup(key));
    }
}
