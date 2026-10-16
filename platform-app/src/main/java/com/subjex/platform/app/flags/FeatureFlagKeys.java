package com.subjex.platform.app.flags;

/**
 * Well-known feature flag keys (logical; storage may be {@code tenantId/key}).
 * 已知功能开关逻辑键（存储可为 tenantId/key）。
 */
public final class FeatureFlagKeys {

    /** Gate {@code GET /api/v1/search/records} — 门禁实体搜索读路径。 */
    public static final String SEARCH_RECORDS = "search.records";

    private FeatureFlagKeys() {}
}
