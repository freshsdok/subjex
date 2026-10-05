package com.subjex.entity.declare;

/**
 * EntityFieldKind — 实体字段种类：允许的两种值。
 * <p>
 * {@code text} becomes a string column. {@code integer} becomes a whole-number column.
 * {@code text} 成为字符串列。{@code integer} 成为整数列。
 */
public enum EntityFieldKind {
    TEXT,
    INTEGER;

    static EntityFieldKind parse(String kind) {
        if ("text".equals(kind)) {
            return TEXT;
        }
        if ("integer".equals(kind)) {
            return INTEGER;
        }
        throw new EntityDefinitionRejected("field kind is not text or integer");
    }
}
