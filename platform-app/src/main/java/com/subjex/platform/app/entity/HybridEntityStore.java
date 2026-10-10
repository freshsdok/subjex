package com.subjex.platform.app.entity;

import com.subjex.entity.declare.RenderedEntity;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HybridEntityStore — 混合轨实体存储（ADR 0002）：共享 {@code entity_record} 核心列 + attrs JSON。
 * <p>
 * Sibling of {@link GenericEntityStore} (physical per-entity tables). Callers route by
 * {@link com.subjex.entity.declare.RenderedEntity#storageMode()}. Large payloads: {@link EntityBlobStore} (ES-2).
 * 与 {@link GenericEntityStore}（一实体一表）并列。按 storageMode 路由；大载荷见 {@link EntityBlobStore}（ES-2）。
 */
public interface HybridEntityStore {

    /**
     * Upsert one hybrid row (attrs from declared fields) — upsert 一行（声明字段写入 attrs）。
     *
     * @param tenantId required when entity is tenantScoped; otherwise use platform sentinel (empty)
     */
    void save(RenderedEntity entity, Map<String, Object> values, String tenantId);

    /** Find by primary-key string — 按主键字符串查找。 */
    Optional<Map<String, Object>> findById(RenderedEntity entity, String id, String tenantId);

    /**
     * List up to {@code limit} rows for this entityKey, ordered by record_id ASC —
     * 按 entityKey 列出最多 limit 行，按 record_id 升序。
     */
    List<Map<String, Object>> list(RenderedEntity entity, int limit, String tenantId);

    /** Delete by primary key; returns whether a row was removed — 按主键删除；返回是否删到行。 */
    boolean deleteById(RenderedEntity entity, String id, String tenantId);
}
