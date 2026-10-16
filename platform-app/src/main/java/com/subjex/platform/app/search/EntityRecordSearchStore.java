package com.subjex.platform.app.search;

import java.util.List;

/**
 * EntityRecordSearchStore — read-only search over shared {@code entity_record}.
 * Write path stays on Generic/Hybrid stores unchanged. Full Elasticsearch is Later.
 * 只读搜 entity_record；写路径不变；全文 ES 属后续。
 */
public interface EntityRecordSearchStore {

    int DEFAULT_LIMIT = 50;
    int MAX_LIMIT = 100;

    /**
     * Substring search on {@code record_id} and attrs text; optional entityKey and/or
     * field equality (PG {@code attrs @>} / GIN when enabled).
     * 在 record_id 与 attrs 文本上子串匹配；可选 entityKey；可选字段等值（PG 走 @>+GIN）。
     *
     * @param tenantId required tenant stamp (empty string = platform / non-scoped rows)
     * @param entityKey optional; null/blank = all entity keys for tenant
     * @param q optional free-text; blank allowed when field+value provided
     * @param field optional declared-style field name for equality
     * @param value equality value paired with field
     */
    List<EntityRecordSearchHit> search(
            String tenantId, String entityKey, String q, String field, String value, int limit);
}
