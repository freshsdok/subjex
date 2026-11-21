package com.subjex.entity.declare;

/**
 * FieldSensitivity — minimal sensitivity vocabulary for a declared entity field (WP8-2 / ADR 0020).
 * 实体字段的最小敏感级别词表。缺省为 {@link #NORMAL}；不得把既有数据悄悄改标为敏感。
 */
public enum FieldSensitivity {
    NORMAL,
    SENSITIVE;

    /**
     * Parse a declaration value; blank or missing becomes {@link #NORMAL}.
     * 解析声明值；空或缺省为 {@link #NORMAL}。
     */
    public static FieldSensitivity parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NORMAL;
        }
        String key = raw.trim();
        for (FieldSensitivity value : values()) {
            if (value.name().equals(key)) {
                return value;
            }
        }
        throw new EntityDefinitionRejected(
                "sensitivity must be NORMAL or SENSITIVE, not " + raw);
    }
}
