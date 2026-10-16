package com.subjex.platform.app.tenant;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.JdbcTenantAdmin;
import com.subjex.platform.app.security.OperatorPrincipal;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * TenantQuotaEndpoint — TN-1 quota usage GET + per-tenant limit PUT ({@code tenant.manage} on write).
 * 配额用量查询与按租户上限写入（写操作需 tenant.manage；读走 admin.read）。
 */
@RestController
public class TenantQuotaEndpoint {

    public static final String PATH = JsonApi.BASE + "/tenants/{tenantId}/quota";

    private final TenantQuotaService quotas;
    private final JdbcTenantAdmin tenants;

    public TenantQuotaEndpoint(TenantQuotaService quotas, JdbcTenantAdmin tenants) {
        this.quotas = Objects.requireNonNull(quotas, "quotas");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
    }

    @GetMapping(PATH)
    public QuotaDocument usage(@PathVariable("tenantId") String tenantId) {
        requireKnownTenant(tenantId);
        try {
            return toDocument(quotas.usage(tenantId));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @PutMapping(PATH)
    public QuotaDocument putLimits(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("tenantId") String tenantId,
            @RequestBody QuotaOverrideRequest body) {
        if (operator == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        requireKnownTenant(tenantId);
        try {
            Integer daily = body == null ? null : body.dailySubmitLimit();
            Integer storage = body == null ? null : body.storageRowLimit();
            return toDocument(quotas.putOverride(operator, tenantId, daily, storage));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private void requireKnownTenant(String tenantId) {
        if (tenants.find(tenantId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "tenant missing");
        }
    }

    private static QuotaDocument toDocument(TenantQuotaService.QuotaUsage usage) {
        return new QuotaDocument(
                usage.tenantId(),
                usage.dailySubmitCount(),
                usage.storageRowCount(),
                usage.effectiveDailySubmitLimit(),
                usage.effectiveStorageRowLimit(),
                usage.globalDailySubmitLimit(),
                usage.globalStorageRowLimit(),
                usage.overrideDailySubmitLimit(),
                usage.overrideStorageRowLimit());
    }

    public record QuotaDocument(
            String tenantId,
            long dailySubmitCount,
            long storageRowCount,
            int effectiveDailySubmitLimit,
            int effectiveStorageRowLimit,
            int globalDailySubmitLimit,
            int globalStorageRowLimit,
            Integer overrideDailySubmitLimit,
            Integer overrideStorageRowLimit) {}

    /** Null field clears that override — 字段为 null 清除该项覆盖。 */
    public record QuotaOverrideRequest(Integer dailySubmitLimit, Integer storageRowLimit) {}
}
