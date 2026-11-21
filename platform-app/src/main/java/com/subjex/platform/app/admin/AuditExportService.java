package com.subjex.platform.app.admin;

import com.subjex.platform.app.jdbc.JdbcAdminReader;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.contract.audit.AuditEntry;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.audit.AuditPort;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;

/**
 * AuditExportService — tenant-scoped NDJSON export of audit rows in a capped time window (WP9-1).
 * 在封顶时间窗内按租户导出审计行为 NDJSON。
 */
public final class AuditExportService {

    public static final int DEFAULT_LIMIT = 1000;
    public static final int MAX_LIMIT = 5000;
    /** Maximum inclusive window length — 含端点的最大时间窗. */
    public static final Duration MAX_WINDOW = Duration.ofDays(31);
    public static final String AUDIT_EXPORT = "audit.export";
    public static final String MEDIA_TYPE = "application/x-ndjson";

    private final JdbcAdminReader reader;
    private final OperatorTenantAccess tenantAccess;
    private final AuditPort audit;
    private final Clock clock;

    public AuditExportService(
            JdbcAdminReader reader, OperatorTenantAccess tenantAccess, AuditPort audit, Clock clock) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Export the operator's granted tenant in {@code [from, to]}. Missing permission or tenant grant is denied.
     * 导出操作员已获授权租户在 {@code [from, to]} 内的记录。缺权限或缺租户授权则拒绝。
     */
    public byte[] export(OperatorPrincipal operator, String tenantId, Instant from, Instant to, Integer limit) {
        Objects.requireNonNull(operator, "operator");
        requireExportPermission(operator);
        String tid = requireTenantId(tenantId);
        tenantAccess.requireGranted(operator, tid);
        Instant start = from;
        Instant end = to;
        if (start == null || end == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("time window exceeds P31D");
        }
        int cap = limit == null || limit <= 0 ? DEFAULT_LIMIT : limit;
        if (cap > MAX_LIMIT) {
            cap = MAX_LIMIT;
        }
        List<AuditRow> rows = reader.exportEntries(tid, start, end, cap);
        byte[] body = AuditNdjson.lines(rows.stream().map(AuditNdjson::fields).toList());
        audit.record(new AuditEntry(
                UUID.randomUUID().toString(),
                tid,
                operator.identityId(),
                AUDIT_EXPORT,
                start + "/" + end + "/" + rows.size(),
                AuditOutcome.ALLOWED,
                clock.instant()));
        return body;
    }

    private static void requireExportPermission(OperatorPrincipal operator) {
        if (!operator.permissionNames().contains(OperatorPermission.AUDIT_EXPORT.permissionName())) {
            throw new AccessDeniedException("audit.export required");
        }
    }

    private static String requireTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        return tenantId.trim();
    }
}
