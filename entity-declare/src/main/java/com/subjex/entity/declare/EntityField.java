package com.subjex.entity.declare;

/**
 * EntityField — 实体字段：渲染后的一个字段，约束已经校验过。
 * <p>
 * {@code maxLength} belongs to text fields. Integer fields do not use maxLength in this stage.
 * {@code maxLength} 属于文本字段。本阶段整数字段不使用 maxLength。
 */
public record EntityField(String name, EntityFieldKind kind, boolean required, Integer maxLength) {

    /**
     * SQL column name in snake_case — snake_case 形式的 SQL 列名。
     */
    public String columnName() {
        return EntityRenderer.toSnakeCase(name);
    }
}
