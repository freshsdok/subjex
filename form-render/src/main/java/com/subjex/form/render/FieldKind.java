package com.subjex.form.render;

/**
 * FieldKind — 字段种类：表单字段允许的声明值。
 * <p>
 * {@code text} / {@code enum} / {@code date} / refs become strings.
 * {@code integer} is a whole number. {@code boolean} is true/false.
 * {@code enum} requires {@code enumValues} on the field.
 * Canonical refs (O8-5): {@code subjectRef} and {@code organizationRef} only.
 * Do not accept legacy {@code userRef} / {@code orgRef}.
 * Never invent {@code organizationUnitRef} / {@code tenantOrgUnitRef}.
 * <p>
 * {@code text}/{@code enum}/{@code date}/引用为字符串。{@code integer} 为整数。
 * {@code boolean} 为真假。{@code enum} 字段须带 {@code enumValues}。
 * 规范引用（O8-5）：仅 {@code subjectRef} / {@code organizationRef}。
 */
public enum FieldKind {
    TEXT,
    INTEGER,
    BOOLEAN,
    DATE,
    ENUM,
    SUBJECT_REF,
    ORGANIZATION_REF;

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
        if ("subjectRef".equals(kind)) {
            return SUBJECT_REF;
        }
        if ("organizationRef".equals(kind)) {
            return ORGANIZATION_REF;
        }
        if ("userRef".equals(kind) || "orgRef".equals(kind)) {
            throw new FormDefinitionRejected(
                    "field kind "
                            + kind
                            + " is not allowed; use subjectRef or organizationRef (O8-5)");
        }
        if ("organizationUnitRef".equals(kind) || "tenantOrgUnitRef".equals(kind)) {
            throw new FormDefinitionRejected(
                    "field kind "
                            + kind
                            + " is not allowed; use organizationRef (never organizationUnitRef/tenantOrgUnitRef)");
        }
        throw new FormDefinitionRejected(
                "field kind is not text, integer, boolean, date, enum, subjectRef, or organizationRef");
    }

    /** Canonical YAML / API wire name — 声明与 API 规范落库名。 */
    public String wireName() {
        return switch (this) {
            case TEXT -> "text";
            case INTEGER -> "integer";
            case BOOLEAN -> "boolean";
            case DATE -> "date";
            case ENUM -> "enum";
            case SUBJECT_REF -> "subjectRef";
            case ORGANIZATION_REF -> "organizationRef";
        };
    }

    /** Whether this kind may carry maxLength — 是否允许 maxLength。 */
    boolean allowsMaxLength() {
        return this == TEXT
                || this == ENUM
                || this == DATE
                || this == SUBJECT_REF
                || this == ORGANIZATION_REF;
    }

    /** Whether this kind may carry integer min/max — 是否允许 minimum/maximum。 */
    boolean allowsIntegerBounds() {
        return this == INTEGER;
    }
}
