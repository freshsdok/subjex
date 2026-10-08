package com.subjex.form.render;

import java.util.List;

/**
 * FormField — 表单字段：渲染后的一个字段，约束已经校验过。
 * <p>
 * {@code minimum} and {@code maximum} belong to integer fields. {@code maxLength} belongs to text/enum/date.
 * {@code enumValues} is required for enum. {@code minimum}/{@code maximum} 属于整数；
 * {@code maxLength} 属于 text/enum/date；enum 必须带 {@code enumValues}。
 */
public record FormField(
        String name,
        FieldKind kind,
        boolean required,
        Integer minimum,
        Integer maximum,
        Integer maxLength,
        List<String> enumValues) {

    public FormField {
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    }
}
