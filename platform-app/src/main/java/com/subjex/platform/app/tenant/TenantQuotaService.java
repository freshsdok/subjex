package com.subjex.platform.app.tenant;

import com.subjex.platform.app.jdbc.PlatformTables;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * TenantQuotaService — 租户硬顶：日提交数与存储行数；超限 429 + 审计（不计费）。
 * <p>
 * TN-1: storage includes {@code entity_record} + {@code entity_blob}; per-tenant overrides
 * override global defaults when set. Globals remain the floor when no override row exists.
 * TN-1：存储计入实体行与附件元数据；有覆盖行时用租户上限，否则用全局默认。
 */
public final class TenantQuotaService {

    public static final String REASON_DAILY_SUBMIT = "tenant-quota-daily-submit";
    public static final String REASON_STORAGE_ROWS = "tenant-quota-storage-rows";
    public static final String AUDIT_QUOTA = "tenant.quota";
    public static final String AUDIT_OVERRIDE = "tenant.quota.override";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final OperatorActionAudit audit;
    private final int globalDailySubmitLimit;
    private final int globalStorageRowLimit;

    public TenantQuotaService(
            JdbcTemplate jdbc,
            Clock clock,
            OperatorActionAudit audit,
            int dailySubmitLimit,
            int storageRowLimit) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.audit = Objects.requireNonNull(audit, "audit");
        if (dailySubmitLimit < 1) {
            throw new IllegalArgumentException("dailySubmitLimit must be at least 1");
        }
        if (storageRowLimit < 1) {
            throw new IllegalArgumentException("storageRowLimit must be at least 1");
        }
        this.globalDailySubmitLimit = dailySubmitLimit;
        this.globalStorageRowLimit = storageRowLimit;
    }

    /**
     * Fail-closed check before accepting a tenant-scoped submit —
     * 租户作用域提交前失败关闭检查。
     */
    public void requireWithinQuota(OperatorPrincipal operator, String tenantId) {
        String tid = requireTenant(tenantId);
        EffectiveLimits limits = effectiveLimits(tid);
        long storage = countStorageRows(tid);
        if (storage >= limits.storageRowLimit()) {
            audit.record(operator, AUDIT_QUOTA, tid + ":" + REASON_STORAGE_ROWS, AuditOutcome.REFUSED);
            throw new TenantQuotaExceeded(REASON_STORAGE_ROWS);
        }
        long daily = countDailySubmits(tid);
        if (daily >= limits.dailySubmitLimit()) {
            audit.record(operator, AUDIT_QUOTA, tid + ":" + REASON_DAILY_SUBMIT, AuditOutcome.REFUSED);
            throw new TenantQuotaExceeded(REASON_DAILY_SUBMIT);
        }
    }

    /**
     * Usage snapshot for admin (TN-1) — 管理端用量快照。
     */
    public QuotaUsage usage(String tenantId) {
        String tid = requireTenant(tenantId);
        EffectiveLimits limits = effectiveLimits(tid);
        Optional<OverrideRow> override = findOverride(tid);
        return new QuotaUsage(
                tid,
                countDailySubmits(tid),
                countStorageRows(tid),
                limits.dailySubmitLimit(),
                limits.storageRowLimit(),
                globalDailySubmitLimit,
                globalStorageRowLimit,
                override.map(OverrideRow::dailySubmitLimit).orElse(null),
                override.map(OverrideRow::storageRowLimit).orElse(null));
    }

    /**
     * Upsert per-tenant limits; null field clears that override (falls back to global) —
     * 写入租户上限；字段为 null 表示清除该项覆盖、回退全局。
     */
    public QuotaUsage putOverride(OperatorPrincipal operator, String tenantId, Integer dailySubmitLimit, Integer storageRowLimit) {
        String tid = requireTenant(tenantId);
        Integer daily = normalizeLimit(dailySubmitLimit, "dailySubmitLimit");
        Integer storage = normalizeLimit(storageRowLimit, "storageRowLimit");
        Instant now = clock.instant();
        String actor = operator == null ? null : operator.identityId();
        if (daily == null && storage == null) {
            jdbc.update("DELETE FROM tenant_quota_override WHERE tenant_id = ?", tid);
        } else {
            int updated = jdbc.update(
                    """
                    UPDATE tenant_quota_override
                    SET daily_submit_limit = ?, storage_row_limit = ?, updated_at = ?, updated_by_identity_id = ?
                    WHERE tenant_id = ?
                    """,
                    daily,
                    storage,
                    PlatformTables.timestamp(now),
                    actor,
                    tid);
            if (updated == 0) {
                jdbc.update(
                        """
                        INSERT INTO tenant_quota_override
                            (tenant_id, daily_submit_limit, storage_row_limit, updated_at, updated_by_identity_id)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        tid,
                        daily,
                        storage,
                        PlatformTables.timestamp(now),
                        actor);
            }
        }
        if (operator != null) {
            audit.record(
                    operator,
                    AUDIT_OVERRIDE,
                    tid + ":daily=" + daily + ",storage=" + storage,
                    AuditOutcome.ALLOWED);
        }
        return usage(tid);
    }

    long countDailySubmits(String tenantId) {
        Instant dayStart = LocalDate.now(clock).atStartOfDay().toInstant(ZoneOffset.UTC);
        Long tasks = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM platform_task
                WHERE tenant_id = ? AND created_at >= ?
                """,
                Long.class,
                tenantId,
                PlatformTables.timestamp(dayStart));
        Long forms = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM form_submission
                WHERE tenant_id = ? AND submitted_at >= ?
                """,
                Long.class,
                tenantId,
                PlatformTables.timestamp(dayStart));
        return nz(tasks) + nz(forms);
    }

    long countStorageRows(String tenantId) {
        Long tasks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_task WHERE tenant_id = ?", Long.class, tenantId);
        Long forms = jdbc.queryForObject(
                "SELECT COUNT(*) FROM form_submission WHERE tenant_id = ?", Long.class, tenantId);
        Long outbox = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE tenant_id = ?", Long.class, tenantId);
        Long entities = jdbc.queryForObject(
                "SELECT COUNT(*) FROM entity_record WHERE tenant_id = ?", Long.class, tenantId);
        Long blobs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM entity_blob WHERE tenant_id = ?", Long.class, tenantId);
        return nz(tasks) + nz(forms) + nz(outbox) + nz(entities) + nz(blobs);
    }

    EffectiveLimits effectiveLimits(String tenantId) {
        Optional<OverrideRow> override = findOverride(tenantId);
        int daily = override.map(OverrideRow::dailySubmitLimit)
                .filter(v -> v != null && v >= 1)
                .orElse(globalDailySubmitLimit);
        int storage = override.map(OverrideRow::storageRowLimit)
                .filter(v -> v != null && v >= 1)
                .orElse(globalStorageRowLimit);
        return new EffectiveLimits(daily, storage);
    }

    private Optional<OverrideRow> findOverride(String tenantId) {
        return jdbc.query(
                        """
                        SELECT daily_submit_limit, storage_row_limit
                        FROM tenant_quota_override WHERE tenant_id = ?
                        """,
                        rs -> {
                            if (!rs.next()) {
                                return Optional.<OverrideRow>empty();
                            }
                            Integer daily = (Integer) rs.getObject("daily_submit_limit");
                            Integer storage = (Integer) rs.getObject("storage_row_limit");
                            return Optional.of(new OverrideRow(daily, storage));
                        },
                        tenantId);
    }

    private static Integer normalizeLimit(Integer value, String label) {
        if (value == null) {
            return null;
        }
        if (value < 1) {
            throw new IllegalArgumentException(label + " must be at least 1 when set");
        }
        return value;
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }

    private static String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId required for quota");
        }
        return tenantId.trim();
    }

    record EffectiveLimits(int dailySubmitLimit, int storageRowLimit) {}

    private record OverrideRow(Integer dailySubmitLimit, Integer storageRowLimit) {}

    /**
     * Admin-facing usage + limit view (TN-1) — 管理端用量与上限视图。
     */
    public record QuotaUsage(
            String tenantId,
            long dailySubmitCount,
            long storageRowCount,
            int effectiveDailySubmitLimit,
            int effectiveStorageRowLimit,
            int globalDailySubmitLimit,
            int globalStorageRowLimit,
            Integer overrideDailySubmitLimit,
            Integer overrideStorageRowLimit) {}
}
