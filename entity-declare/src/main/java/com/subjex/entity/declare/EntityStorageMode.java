package com.subjex.entity.declare;

/**
 * EntityStorageMode — 实体行存储轨（ADR 0002）：hybrid 共享表 vs table 一实体一表。
 * <p>
 * Declared as optional YAML {@code storageMode}. When omitted, defaults to {@link #HYBRID} for new
 * zero-code entities. Existing strong-constraint samples set {@code storageMode: table} explicitly.
 * 声明可选键 {@code storageMode}；省略时默认 {@link #HYBRID}（零代码默认轨）。
 * 强约束样例须显式 {@code storageMode: table}。
 */
public enum EntityStorageMode {

    /** Shared entity_record core + attrs JSON — 共享 entity_record 核心列 + attrs JSON。 */
    HYBRID,

    /** Physical per-entity table via GenericEntityStore — 实体物理表（GenericEntityStore）。 */
    TABLE;

    /**
     * Parse YAML token; blank/null → HYBRID (zero-code default) —
     * 解析 YAML；空白/null → HYBRID（零代码默认）。
     */
    public static EntityStorageMode parseOptional(String raw) {
        if (raw == null || raw.isBlank()) {
            return HYBRID;
        }
        String token = raw.trim().toLowerCase();
        return switch (token) {
            case "hybrid" -> HYBRID;
            case "table" -> TABLE;
            default -> throw new EntityDefinitionRejected(
                    "storageMode must be hybrid or table, got: " + raw.trim());
        };
    }
}
