package com.subjex.entity.declare;

/**
 * EntityFieldKind — 实体字段种类：允许的声明值。
 * <p>
 * {@code text} becomes a string column. {@code integer} becomes a whole-number column.
 * {@code boolean} is JDBC BOOLEAN. {@code enum} / {@code date} / refs are VARCHAR.
 * Canonical refs (O8-5): {@code subjectRef} / {@code organizationRef} / {@code entityRef} only.
 * Never invent {@code organizationUnitRef} / {@code tenantOrgUnitRef}; do not accept
 * legacy {@code userRef} / {@code orgRef}.
 * <p>
 * {@code text} 成为字符串列。{@code integer} 成为整数列。
 * {@code boolean} 为 JDBC BOOLEAN。{@code enum} / {@code date} / 引用为 VARCHAR。
 * 规范引用（O8-5）：仅 {@code subjectRef} / {@code organizationRef} / {@code entityRef}。
 */
public enum EntityFieldKind {
    TEXT,
    INTEGER,
    BOOLEAN,
    ENUM,
    DATE,
    SUBJECT_REF,
    ORGANIZATION_REF,
    ENTITY_REF;

    static EntityFieldKind parse(String kind) {
        if ("text".equals(kind)) {
            return TEXT;
        }
        if ("integer".equals(kind)) {
            return INTEGER;
        }
        if ("boolean".equals(kind)) {
            return BOOLEAN;
        }
        if ("enum".equals(kind)) {
            return ENUM;
        }
        if ("date".equals(kind)) {
            return DATE;
        }
        if ("subjectRef".equals(kind)) {
            return SUBJECT_REF;
        }
        if ("organizationRef".equals(kind)) {
            return ORGANIZATION_REF;
        }
        if ("entityRef".equals(kind)) {
            return ENTITY_REF;
        }
        if ("userRef".equals(kind) || "orgRef".equals(kind)) {
            throw new EntityDefinitionRejected(
                    "field kind "
                            + kind
                            + " is not allowed; use subjectRef or organizationRef (O8-5)");
        }
        if ("organizationUnitRef".equals(kind) || "tenantOrgUnitRef".equals(kind)) {
            throw new EntityDefinitionRejected(
                    "field kind "
                            + kind
                            + " is not allowed; use organizationRef (never organizationUnitRef/tenantOrgUnitRef)");
        }
        throw new EntityDefinitionRejected(
                "field kind is not text, integer, boolean, enum, date, subjectRef, organizationRef, or entityRef");
    }

    /**
     * Canonical YAML / API wire name — 声明与 API 规范落库名。
     */
    public String wireName() {
        return switch (this) {
            case TEXT -> "text";
            case INTEGER -> "integer";
            case BOOLEAN -> "boolean";
            case ENUM -> "enum";
            case DATE -> "date";
            case SUBJECT_REF -> "subjectRef";
            case ORGANIZATION_REF -> "organizationRef";
            case ENTITY_REF -> "entityRef";
        };
    }

    /**
     * Whether this kind may carry {@code maxLength} in YAML — 该种类是否允许 YAML 带 maxLength。
     */
    boolean allowsMaxLength() {
        return this == TEXT
                || this == ENUM
                || this == SUBJECT_REF
                || this == ORGANIZATION_REF
                || this == ENTITY_REF;
    }

    /**
     * Default VARCHAR length when maxLength is omitted — 省略 maxLength 时的默认 VARCHAR 长度。
     * <p>
     * Refs and enum default to 64. Text defaults to 255. Date is always VARCHAR(10) (ISO).
     * 引用与 enum 默认 64。text 默认 255。date 固定 VARCHAR(10)（ISO）。
     */
    int defaultVarcharLength() {
        if (this == DATE) {
            return 10;
        }
        if (this == SUBJECT_REF
                || this == ORGANIZATION_REF
                || this == ENTITY_REF
                || this == ENUM) {
            return 64;
        }
        return 255;
    }
}
