package com.subjex.platform.contract.config;

import java.util.Optional;

/**
 * ConfigSource — 配置来源：按配置键取出一个值。
 * <p>
 * A missing key is empty. Callers do not guess a value.
 * 没有这个键就是空。调用方不去猜一个值。
 */
public interface ConfigSource {

    /**
     * @param key configuration key, for example {@code platform.discovery.static.platform-app.host}
     *            配置键，例如 {@code platform.discovery.static.platform-app.host}
     * @return the value this source holds for the key / 这个来源为该键保存的值
     */
    Optional<String> lookup(String key);
}
