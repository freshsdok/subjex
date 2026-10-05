package com.subjex.form.render;

/**
 * FormField — 表单字段：渲染后的一个字段，约束已经校验过。
 * <p>
 * {@code minimum} and {@code maximum} belong to integer fields. {@code maxLength} belongs to text fields.
 * {@code minimum} 和 {@code maximum} 属于整数字段。{@code maxLength} 属于文本字段。
 */
public record FormField(
        String name,
        FieldKind kind,
        boolean required,
        Integer minimum,
        Integer maximum,
        Integer maxLength) {}
