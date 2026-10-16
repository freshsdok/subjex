package com.subjex.platform.app.search;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.flags.FeatureFlagKeys;
import com.subjex.platform.app.flags.FeatureFlagService;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * EntitySearchEndpoint — read-only search over {@code entity_record} (Search read path MVP).
 * Does not mutate records. Full Elasticsearch / cross-store search is Later.
 * 只读搜 entity_record；不写库；全文 ES 属后续。
 */
@RestController
public class EntitySearchEndpoint {

    public static final String PATH = JsonApi.BASE + "/search/records";

    private final EntityRecordSearchStore store;
    private final TenantGuard tenantGuard;
    private final OperatorTenantAccess tenantAccess;
    private final FeatureFlagService featureFlags;

    public EntitySearchEndpoint(
            EntityRecordSearchStore store,
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess,
            FeatureFlagService featureFlags) {
        this.store = Objects.requireNonNull(store, "store");
        this.tenantGuard = Objects.requireNonNull(tenantGuard, "tenantGuard");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.featureFlags = Objects.requireNonNull(featureFlags, "featureFlags");
    }

    /**
     * Search hybrid/document rows for a tenant. Gate: {@code page.read} + tenant grant.
     * 按租户搜混合/文档行；门禁 page.read + 租户授权。
     */
    @GetMapping(PATH)
    public SearchDocument search(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "entityKey", required = false) String entityKey,
            @RequestParam(value = "field", required = false) String field,
            @RequestParam(value = "value", required = false) String value,
            @RequestParam(value = "limit", required = false) Integer limit) {
        String tid = firstNonBlank(tenantHeader, tenantIdParam);
        if (tid == null) {
            throw new IllegalArgumentException("tenantId or X-Tenant-Id required");
        }
        tenantGuard.requireTenant(tid);
        tenantAccess.requireGranted(operator, tid);
        if (!featureFlags.isEnabled(tid, FeatureFlagKeys.SEARCH_RECORDS, true)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "feature flag " + FeatureFlagKeys.SEARCH_RECORDS + " disabled");
        }
        int capped = capLimit(limit);
        List<EntityRecordSearchHit> hits = store.search(tid, entityKey, q, field, value, capped);
        return new SearchDocument(hits, tid, "entity_record-substring+optional-attrs-equality; ES Later");
    }

    private static int capLimit(Integer limit) {
        if (limit == null) {
            return EntityRecordSearchStore.DEFAULT_LIMIT;
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return Math.min(limit, EntityRecordSearchStore.MAX_LIMIT);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }

    public record SearchDocument(List<EntityRecordSearchHit> hits, String tenantId, String mode) {}
}
