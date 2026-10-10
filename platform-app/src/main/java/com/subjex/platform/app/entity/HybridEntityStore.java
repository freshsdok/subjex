package com.subjex.platform.app.entity;

import com.subjex.entity.declare.RenderedEntity;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HybridEntityStore — 混合轨实体存储（ADR 0002）：共享 {@code entity_record} 核心列 + attrs JSON。
 * <p>
 * Sibling of {@link GenericEntityStore}. Large payloads: {@link EntityBlobStore}. List supports
 * record_id order and equality filter on declared fields (PK in SQL; other fields scanned in-memory, capped).
 * 与 {@link GenericEntityStore} 并列。大载荷见 {@link EntityBlobStore}。列表支持 record_id 排序与声明字段等值过滤。
 */
public interface HybridEntityStore {

    void save(RenderedEntity entity, Map<String, Object> values, String tenantId);

    Optional<Map<String, Object>> findById(RenderedEntity entity, String id, String tenantId);

    /**
     * List with optional equality filter; order by record_id —
     * 可选等值过滤列表；按 record_id 排序。
     *
     * @param ascending record_id ASC when true, DESC when false
     * @param filterField declared field name, or null
     * @param filterValue raw filter string; required with filterField
     */
    List<Map<String, Object>> list(
            RenderedEntity entity,
            int limit,
            String tenantId,
            boolean ascending,
            String filterField,
            String filterValue);

    /** Convenience: record_id ASC, no filter — 便捷：record_id 升序、无过滤。 */
    default List<Map<String, Object>> list(RenderedEntity entity, int limit, String tenantId) {
        return list(entity, limit, tenantId, true, null, null);
    }

    boolean deleteById(RenderedEntity entity, String id, String tenantId);
}
