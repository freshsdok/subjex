package com.subjex.platform.app.search;

/**
 * One read-only search hit over {@code entity_record} (hybrid/document rows).
 * 只读搜索命中（entity_record 混合/文档行）。
 */
public record EntityRecordSearchHit(
        String tenantId,
        String entityKey,
        String recordId,
        String recordState,
        /** Truncated attrs JSON for display — 截断后的 attrs JSON 供展示。 */
        String attrsPreview,
        String updatedAt) {}
