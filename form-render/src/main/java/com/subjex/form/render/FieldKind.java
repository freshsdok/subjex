package com.subjex.form.render;

/**
 * FieldKind — 字段种类：表单字段允许的声明值。
 * <p>
 * {@code text} / {@code enum} / {@code date} / {@code userRef} become strings.
 * {@code integer} is a whole number. {@code boolean} is true/false.
 * {@code enum} requires {@code enumValues} on the field.
 * {@code userRef} is a reserved subject id (VARCHAR; picker in console).
 * {@code text}/{@code enum}/{@code date}/{@code userRef} 为字符串。{@code integer} 为整数。
 * {@code boolean} 为真假。{@code enum} 字段须带 {@code enumValues}。
 * {@code userRef} 预留主体 ID（VARCHAR；控制台选人）。
 */
public enum FieldKind {
    TEXT,
    INTEGER,
    BOOLEAN,
    DATE,
    ENUM,
    USER_REF;

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
        if ("userRef".equals(kind)) {
            return USER_REF;
        }
        throw new FormDefinitionRejected(
                "field kind is not text, integer, boolean, date, enum, or userRef");
    }


    /** YAML / API wire name — 声明与 API 落库名。 */
    public String wireName() {
        return switch (this) {
            case TEXT -> "text";
            case INTEGER -> "integer";
            case BOOLEAN -> "boolean";
            case DATE -> "date";
            case ENUM -> "enum";
            case USER_REF -> "userRef";
        };
    }

    /** Whether this kind may carry maxLength — 是否允许 maxLength。 */
    boolean allowsMaxLength() {
        return this == TEXT || this == ENUM || this == DATE || this == USER_REF;
    }

    /** Whether this kind may carry integer min/max — 是否允许 minimum/maximum。 */
    boolean allowsIntegerBounds() {
        return this == INTEGER;
    }
}
