package com.subjex.platform.contract.config;

import java.util.List;
import java.util.Optional;

/**
 * ConfigCenterPort — 配置中心端口：按命名空间列表 / 读取 / 写入覆盖（Item 5）。
 * <p>
 * Flat methods use {@link ConfigNamespaces#DEFAULT}. Put returns the effective override entry with revision.
 * See {@code docs/config/independent-config-center-plan.md}.
 * 扁平方法走默认命名空间；写入返回带修订号的覆盖条目。
 */
public interface ConfigCenterPort {

    default List<ConfigEntry> list() {
        return list(ConfigNamespaces.DEFAULT);
    }

    /** Watched keys plus stored overrides in the namespace — 命名空间内关注键 + 已存覆盖。 */
    List<ConfigEntry> list(String namespace);

    default Optional<ConfigEntry> get(String key) {
        return get(ConfigNamespaces.DEFAULT, key);
    }

    /** Effective entry for namespace+key if known — 命名空间+键的生效条目（若有）。 */
    Optional<ConfigEntry> get(String namespace, String key);

    default ConfigEntry put(String key, String value) {
        return put(ConfigNamespaces.DEFAULT, key, value);
    }

    /**
     * Store an override; returned entry has origin OVERRIDE and the new revision -
     * 写入覆盖；返回条目来源为 OVERRIDE 且带新修订号。
     */
    ConfigEntry put(String namespace, String key, String value);
}
