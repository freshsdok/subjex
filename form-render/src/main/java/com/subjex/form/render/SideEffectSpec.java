package com.subjex.form.render;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * SideEffectSpec — 副作用规格：目录中一项的键、标题与参数名约束。
 */
public record SideEffectSpec(
        SideEffectKey key, String titleEn, String titleZh, Set<String> requiredParams, Set<String> optionalParams) {

    public SideEffectSpec {
        requiredParams = Set.copyOf(requiredParams);
        optionalParams = Set.copyOf(optionalParams);
    }

    /** Every allowed param name — 允许的全部参数名。 */
    public Set<String> allowedParams() {
        Set<String> allowed = new LinkedHashSet<>(requiredParams);
        allowed.addAll(optionalParams);
        return Set.copyOf(allowed);
    }
}
