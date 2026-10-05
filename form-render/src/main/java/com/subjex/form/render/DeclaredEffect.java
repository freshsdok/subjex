package com.subjex.form.render;

import java.util.Map;

/**
 * DeclaredEffect — 已声明副作用：目录中的键加上字符串参数。
 * <p>
 * Params are validated against the checked-in catalog at render time.
 * 参数在渲染时对照检入目录校验。
 */
public record DeclaredEffect(SideEffectKey key, Map<String, String> params) {

    public DeclaredEffect {
        params = Map.copyOf(params);
    }

    /** Catalog key string — 目录键字符串。 */
    public String keyName() {
        return key.key();
    }
}
