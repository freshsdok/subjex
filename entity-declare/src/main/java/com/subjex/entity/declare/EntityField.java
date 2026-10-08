package com.subjex.entity.declare;

import java.util.List;

/**
 * EntityField — 实体字段：渲染后的一个字段，约束已经校验过。
 * <p>
 * {@code maxLength} belongs to text / enum / userRef / orgRef / entityRef.
 * Integer, boolean, and date fields do not use maxLength.
 * {@code enumValues} is required for enum. {@code refEntityKey} is optional on entityRef.
 * {@code maxLength} 属于 text / enum / userRef / orgRef / entityRef。
 * integer / boolean / date 不使用 maxLength。enum 必须带 enumValues；entityRef 可带 refEntityKey。
 */
public record EntityField(
        String name,
        EntityFieldKind kind,
        boolean required,
        Integer maxLength,
        List<String> enumValues,
        String refEntityKey) {

    public EntityField {
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    }

    /**
     * Convenience for kinds without enum/ref metadata — 无 enum/ref 元数据时的便捷构造。
     */
    public EntityField(String name, EntityFieldKind kind, boolean required, Integer maxLength) {
        this(name, kind, required, maxLength, List.of(), null);
    }

    /**
     * SQL column name in snake_case — snake_case 形式的 SQL 列名。
     */
    public String columnName() {
        return EntityRenderer.toSnakeCase(name);
    }
}
