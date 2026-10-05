package com.subjex.platform.contract.config;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * LocalApplicationConfig — 本地应用配置：默认的配置来源，读本进程已经装载的配置。
 * <p>
 * The function is the process configuration lookup, usually the application environment.
 * Blank values are treated as absent. This is not a remote configuration server.
 * 这个函数就是进程的配置查询，通常是应用环境。
 * 空白值视为没有。这不是远程配置服务器。
 */
public final class LocalApplicationConfig implements ConfigSource {

    private final Function<String, String> properties;

    public LocalApplicationConfig(Function<String, String> properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public Optional<String> lookup(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        String value = properties.apply(key);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }
}
