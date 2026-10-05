package com.subjex.form.render;

import java.util.List;

/**
 * RenderedForm — 已渲染表单：表单键、标题、声明版本、权限与租户标志、校验过的字段，以及可选的提交副作用。
 * <p>
 * {@code recordName} is the Java record simple name a generator would write for this form.
 * {@code version} is required (integer ≥ 1, fail-closed); stored on each submission for history correlation.
 * {@code permission} is required (fail-closed); {@code tenantScoped} defaults to false when omitted.
 * {@code effects} are optional catalog keys run after a successful submit; unknown keys are rejected at render.
 * {@code recordName} 是生成器为这份表单写下的 Java 记录简单名。
 * {@code version} 必填（整数 ≥ 1，缺则拒绝）；每次提交会写入历史以便对照。
 * {@code permission} 必填（缺则拒绝）；{@code tenantScoped} 省略时默认为 false。
 * {@code effects} 可选，提交成功后按目录键执行；未知键在渲染时拒绝。
 */
public record RenderedForm(
        String formKey,
        String titleEn,
        String titleZh,
        int version,
        String permission,
        boolean tenantScoped,
        String recordName,
        List<FormField> fields,
        List<DeclaredEffect> effects) {

    public RenderedForm {
        fields = List.copyOf(fields);
        effects = List.copyOf(effects);
    }
}
