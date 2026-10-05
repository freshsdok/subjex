package com.subjex.form.render;

/**
 * FieldKind — 字段种类：表单字段允许的两种值。
 * <p>
 * {@code text} becomes a string. {@code integer} becomes a whole number.
 * {@code text} 成为字符串。{@code integer} 成为整数。
 */
public enum FieldKind {
    TEXT,
    INTEGER;

    static FieldKind parse(String kind) {
        if ("text".equals(kind)) {
            return TEXT;
        }
        if ("integer".equals(kind)) {
            return INTEGER;
        }
        throw new FormDefinitionRejected("field kind is not text or integer");
    }
}
