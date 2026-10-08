package com.subjex.entity.declare;

/**
 * EntityFieldKind — 实体字段种类：允许的声明值。
 * <p>
 * {@code text} becomes a string column. {@code integer} becomes a whole-number column.
 * {@code boolean} is JDBC BOOLEAN. {@code enum} / {@code date} / refs are VARCHAR.
 * {@code userRef} / {@code orgRef} / {@code entityRef} are reserved references (VARCHAR; pickers later).
 * {@code text} 成为字符串列。{@code integer} 成为整数列。
 * {@code boolean} 为 JDBC BOOLEAN。{@code enum} / {@code date} / 引用为 VARCHAR。
 * {@code userRef} / {@code orgRef} / {@code entityRef} 预留引用（VARCHAR；选人组件后置）。
 */
public enum EntityFieldKind {
    TEXT,
    INTEGER,
    BOOLEAN,
    ENUM,
    DATE,
    USER_REF,
    ORG_REF,
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
        if ("userRef".equals(kind)) {
            return USER_REF;
        }
        if ("orgRef".equals(kind)) {
            return ORG_REF;
        }
        if ("entityRef".equals(kind)) {
            return ENTITY_REF;
        }
        throw new EntityDefinitionRejected(
                "field kind is not text, integer, boolean, enum, date, userRef, orgRef, or entityRef");
    }

    /**
     * Whether this kind may carry {@code maxLength} in YAML — 该种类是否允许 YAML 带 maxLength。
     */
    boolean allowsMaxLength() {
        return this == TEXT || this == ENUM || this == USER_REF || this == ORG_REF || this == ENTITY_REF;
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
        if (this == USER_REF || this == ORG_REF || this == ENTITY_REF || this == ENUM) {
            return 64;
        }
        return 255;
    }
}
