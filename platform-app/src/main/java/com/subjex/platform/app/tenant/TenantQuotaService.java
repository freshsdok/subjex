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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * TenantQuotaService — 租户硬顶：日提交数与存储行数；超限 429 + 审计（不计费）。
 */
public final class TenantQuotaService {

    public static final String REASON_DAILY_SUBMIT = "tenant-quota-daily-submit";
    public static final String REASON_STORAGE_ROWS = "tenant-quota-storage-rows";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final OperatorActionAudit audit;
    private final int dailySubmitLimit;
    private final int storageRowLimit;

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
        this.dailySubmitLimit = dailySubmitLimit;
        this.storageRowLimit = storageRowLimit;
    }

    /**
     * Fail-closed check before accepting a tenant-scoped submit —
     * 租户作用域提交前失败关闭检查。
     */
    public void requireWithinQuota(OperatorPrincipal operator, String tenantId) {
        String tid = requireTenant(tenantId);
        long storage = countStorageRows(tid);
        if (storage >= storageRowLimit) {
            audit.record(operator, "tenant.quota", tid + ":" + REASON_STORAGE_ROWS, AuditOutcome.REFUSED);
            throw new TenantQuotaExceeded(REASON_STORAGE_ROWS);
        }
        long daily = countDailySubmits(tid);
        if (daily >= dailySubmitLimit) {
            audit.record(operator, "tenant.quota", tid + ":" + REASON_DAILY_SUBMIT, AuditOutcome.REFUSED);
            throw new TenantQuotaExceeded(REASON_DAILY_SUBMIT);
        }
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
        return (tasks == null ? 0 : tasks) + (forms == null ? 0 : forms);
    }

    long countStorageRows(String tenantId) {
        Long tasks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_task WHERE tenant_id = ?", Long.class, tenantId);
        Long forms = jdbc.queryForObject(
                "SELECT COUNT(*) FROM form_submission WHERE tenant_id = ?", Long.class, tenantId);
        Long outbox = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE tenant_id = ?", Long.class, tenantId);
        return (tasks == null ? 0 : tasks)
                + (forms == null ? 0 : forms)
                + (outbox == null ? 0 : outbox);
    }

    private static String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId required for quota");
        }
        return tenantId.trim();
    }
}
