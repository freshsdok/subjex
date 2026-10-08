package com.subjex.form.render;

import java.util.List;

/**
 * RenderedForm — 已渲染表单：表单键、标题、声明版本、权限与租户标志、领域动作、校验过的字段，以及可选的提交副作用。
 * <p>
 * {@code recordName} is the Java record simple name a generator would write for this form.
 * {@code version} is required (integer ≥ 1, fail-closed); stored on each submission for history correlation.
 * {@code permission} is required (fail-closed); {@code tenantScoped} defaults to false when omitted.
 * {@code domainAction} is required (fail-closed); must be a catalog key whose required fields exist on the form.
 * {@code entityKey} is optional; required when {@code domainAction} is {@code entity.record.upsert} (fail-closed at render).
 * {@code effects} are optional catalog keys run after a successful submit; unknown keys are rejected at render.
 * {@code recordName} 是生成器为这份表单写下的 Java 记录简单名。
 * {@code version} 必填（整数 ≥ 1，缺则拒绝）；每次提交会写入历史以便对照。
 * {@code permission} 必填（缺则拒绝）；{@code tenantScoped} 省略时默认为 false。
 * {@code domainAction} 必填（缺则拒绝）；须为目录键，且目录要求的字段名须出现在表单上。
 * {@code entityKey} 可选；当领域动作为 {@code entity.record.upsert} 时必填（渲染时失败关闭）。
 * {@code effects} 可选，提交成功后按目录键执行；未知键在渲染时拒绝。
 */
public record RenderedForm(
        String formKey,
        String titleEn,
        String titleZh,
        int version,
        String permission,
        boolean tenantScoped,
        DomainActionKey domainAction,
        String entityKey,
        String recordName,
        List<FormField> fields,
        List<DeclaredEffect> effects) {

    public RenderedForm {
        fields = List.copyOf(fields);
        effects = List.copyOf(effects);
    }
}
