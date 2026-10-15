package com.subjex.entity.declare;

import java.util.List;

/**
 * EntityField — 实体字段：渲染后的一个字段，约束已经校验过。
 * <p>
 * {@code maxLength} belongs to text / enum / subjectRef / organizationRef / entityRef.
 * Integer, boolean, and date fields do not use maxLength.
 * {@code enumValues} is required for enum. {@code refEntityKey} is optional on entityRef.
 * {@code promote} (HY-2): hybrid-only; when true, value lives on a typed {@code entity_record} column
 * ({@code hp_<entity>_<field>}) instead of only inside {@code attrs} JSON.
 * {@code maxLength} 属于 text / enum / subjectRef / organizationRef / entityRef。
 * integer / boolean / date 不使用 maxLength。enum 必须带 enumValues；entityRef 可带 refEntityKey。
 * {@code promote}（HY-2）：仅 hybrid；为 true 时值落在 {@code entity_record} 类型列而非仅 attrs。
 */
public record EntityField(
        String name,
        EntityFieldKind kind,
        boolean required,
        Integer maxLength,
        List<String> enumValues,
        String refEntityKey,
        boolean promote) {

    public EntityField {
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    }

    /**
     * Convenience for kinds without enum/ref/promote metadata — 无 enum/ref/promote 元数据时的便捷构造。
     */
    public EntityField(String name, EntityFieldKind kind, boolean required, Integer maxLength) {
        this(name, kind, required, maxLength, List.of(), null, false);
    }

    /**
     * SQL column name in snake_case (physical-table track) — snake_case 物理表列名。
     */
    public String columnName() {
        return EntityRenderer.toSnakeCase(name);
    }

    /**
     * Physical column on shared {@code entity_record} for hybrid promote (HY-2) —
     * hybrid 升格在共享表上的物理列名（HY-2）。
     * <p>
     * Format: {@code hp_<entityKey_underscored>_<field_snake>} to avoid cross-entity collisions.
     * 格式：{@code hp_<实体下划线>_<字段蛇形>}，避免跨实体列名冲突。
     */
    public String hybridPromotedColumnName(String entityKey) {
        if (entityKey == null || entityKey.isBlank()) {
            throw new IllegalArgumentException("entityKey required for promoted column name");
        }
        return "hp_" + entityKey.trim().replace('-', '_') + "_" + columnName();
    }
}
