package com.subjex.entity.declare;

import java.util.List;

/**
 * RenderedEntity — 已渲染实体：实体键、表名、声明版本、权限与租户标志、存储轨，以及校验过的字段列表。
 * <p>
 * {@code typeName} is the Java simple name a generator would write for this entity.
 * {@code version} is required (integer ≥ 1, fail-closed); drives draft migration file names.
 * {@code permission} is required (fail-closed); {@code tenantScoped} defaults to false when omitted.
 * When {@code tenantScoped} is true, the Flyway table must include physical {@code tenant_id VARCHAR(64) NOT NULL}
 * (generic CRUD stamps/filters it; {@link EntityMigrationGenerator} auto-appends the column when the YAML field list omits it).
 * {@code storageMode} defaults to {@link EntityStorageMode#HYBRID} when omitted (ADR 0002); set {@code table}
 * for the optional physical per-entity track ({@code GenericEntityStore}).
 * {@code typeName} 是生成器为这份实体写下的 Java 简单名。
 * {@code version} 必填（整数 ≥ 1，缺则拒绝）；驱动迁移草稿文件名。
 * {@code permission} 必填（缺则拒绝）；{@code tenantScoped} 省略时默认为 false。
 * {@code tenantScoped} 为 true 时表须含物理列 {@code tenant_id}（通用 CRUD 盖章/过滤；YAML 省略时迁移生成器自动追加）。
 * {@code storageMode} 省略时默认 hybrid（ADR 0002）；物理表轨显式 table。
 */
public record RenderedEntity(
        String entityKey,
        String tableName,
        int version,
        String permission,
        boolean tenantScoped,
        EntityStorageMode storageMode,
        String typeName,
        List<EntityField> fields) {

    public RenderedEntity {
        storageMode = storageMode == null ? EntityStorageMode.HYBRID : storageMode;
        fields = List.copyOf(fields);
    }

    /**
     * First field is treated as the primary key — 第一个字段视为主键。
     */
    public EntityField primaryKey() {
        return fields.get(0);
    }

    /**
     * True when rows live on shared entity_record — 行为共享 entity_record 时为 true。
     */
    public boolean hybridStorage() {
        return storageMode == EntityStorageMode.HYBRID;
    }
}
