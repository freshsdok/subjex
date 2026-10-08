package com.subjex.form.render;

/**
 * FieldKind — 字段种类：表单字段允许的声明值。
 * <p>
 * {@code text} / {@code enum} / {@code date} become strings. {@code integer} is a whole number.
 * {@code boolean} is true/false. {@code enum} requires {@code enumValues} on the field.
 * {@code text}/{@code enum}/{@code date} 为字符串。{@code integer} 为整数。
 * {@code boolean} 为真假。{@code enum} 字段须带 {@code enumValues}。
 */
public enum FieldKind {
    TEXT,
    INTEGER,
    BOOLEAN,
    DATE,
    ENUM;

    static FieldKind parse(String kind) {
        if ("text".equals(kind)) {
            return TEXT;
        }
        if ("integer".equals(kind)) {
            return INTEGER;
        }
        if ("boolean".equals(kind)) {
            return BOOLEAN;
        }
        if ("date".equals(kind)) {
            return DATE;
        }
        if ("enum".equals(kind)) {
            return ENUM;
        }
        throw new FormDefinitionRejected("field kind is not text, integer, boolean, date, or enum");
    }

    /** Whether this kind may carry maxLength — 是否允许 maxLength。 */
    boolean allowsMaxLength() {
        return this == TEXT || this == ENUM || this == DATE;
    }

    /** Whether this kind may carry integer min/max — 是否允许 minimum/maximum。 */
    boolean allowsIntegerBounds() {
        return this == INTEGER;
    }
}
