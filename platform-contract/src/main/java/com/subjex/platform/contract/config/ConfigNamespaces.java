package com.subjex.platform.contract.config;

/**
 * ConfigNamespaces — 配置命名空间常量（Config-5b）。
 */
public final class ConfigNamespaces {

    /** Flat keys and local application config live here — 扁平行与本地应用配置落在此命名空间。 */
    public static final String DEFAULT = "default";

    private ConfigNamespaces() {}

    public static String require(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("config namespace is missing");
        }
        return namespace.trim();
    }
}
