package com.subjex.entity.declare;

/**
 * EntityStorageMode — 实体行存储轨（ADR 0002 / DOC-1）：hybrid | document | table。
 * <p>
 * Declared as optional YAML {@code storageMode}. When omitted, defaults to {@link #HYBRID}.
 * {@link #DOCUMENT} = full-JSON on shared {@code entity_record} (no hot-field promote).
 * {@link #TABLE} = physical per-entity table (optional dual-track).
 * 声明可选键 {@code storageMode}；省略默认 {@link #HYBRID}。
 * {@link #DOCUMENT} 为共享表全 JSON（不可升格）；{@link #TABLE} 为一实体一表可选轨。
 */
public enum EntityStorageMode {

    /** Shared entity_record core + attrs JSON; optional {@code promote} columns (HY-2) —
     * 共享 entity_record + attrs；可选升格列（HY-2）。 */
    HYBRID,

    /** Shared entity_record with fields almost only in attrs JSON (DOC-1); no promote —
     * 共享 entity_record，字段几乎只进 attrs（DOC-1）；不可升格。 */
    DOCUMENT,

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
            case "document" -> DOCUMENT;
            case "table" -> TABLE;
            default -> throw new EntityDefinitionRejected(
                    "storageMode must be hybrid, document, or table, got: " + raw.trim());
        };
    }

    /**
     * True when rows live on shared {@code entity_record} (hybrid or document) —
     * 行为共享 {@code entity_record}（hybrid 或 document）时为 true。
     */
    public boolean sharedRecord() {
        return this == HYBRID || this == DOCUMENT;
    }
}
