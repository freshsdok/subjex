package com.subjex.platform.app.tenant;

import com.subjex.platform.app.jdbc.PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorDirectory;
import com.subjex.platform.app.security.JdbcTenantAdmin;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.tenant.TenantRecord;
import com.subjex.platform.contract.tenant.TenantState;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * TenantHostService — bind unique custom hostnames to a business tenant and resolve Host.
 * 将唯一自定义主机名绑定到业务租户，并按 Host 解析。
 * <p>
 * Unknown hosts do not invent a tenant. Platform / loopback hosts stay header-based.
 * 未知主机不会被编造成某个租户。平台/回环主机仍只认请求头。
 */
public final class TenantHostService {

    public static final String AUDIT_BIND = "tenant.host.bind";
    public static final String AUDIT_UNBIND = "tenant.host.unbind";

    public enum Kind {
        PLATFORM,
        BOUND,
        UNKNOWN
    }

    public record Binding(String hostName, String tenantId, Instant createdAt) {}

    public record Resolution(Kind kind, String hostName, String tenantId) {}

    private final JdbcTemplate jdbc;
    private final JdbcTenantAdmin tenants;
    private final OperatorActionAudit audit;
    private final Clock clock;
    private final Set<String> reservedHosts;

    public TenantHostService(
            JdbcTemplate jdbc,
            JdbcTenantAdmin tenants,
            OperatorActionAudit audit,
            Clock clock,
            String reservedHostsList) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.reservedHosts = TenantHostNames.reservedHosts(reservedHostsList);
    }

    public List<Binding> list(String tenantId) {
        TenantRecord row = requireBusinessTenant(tenantId);
        return jdbc.query(
                """
                SELECT host_name, tenant_id, created_at
                FROM tenant_host
                WHERE tenant_id = ?
                ORDER BY host_name
                """,
                (rs, n) -> new Binding(
                        rs.getString("host_name"),
                        rs.getString("tenant_id"),
                        rs.getTimestamp("created_at").toInstant()),
                row.tenantId());
    }

    public Binding bind(OperatorPrincipal operator, String tenantId, String rawHost) {
        Objects.requireNonNull(operator, "operator");
        TenantRecord row = requireBusinessTenant(tenantId);
        String host = requireBindableHost(rawHost);
        Optional<Binding> existing = findBinding(host);
        if (existing.isPresent()) {
            if (existing.get().tenantId().equals(row.tenantId())) {
                return existing.get();
            }
            throw new TenantHostAlreadyBoundException("host already bound");
        }
        Instant now = clock.instant();
        try {
            jdbc.update(
                    """
                    INSERT INTO tenant_host (host_name, tenant_id, created_at, created_by_identity_id)
                    VALUES (?, ?, ?, ?)
                    """,
                    host,
                    row.tenantId(),
                    PlatformTables.timestamp(now),
                    operator.identityId());
        } catch (DataIntegrityViolationException ex) {
            Optional<Binding> raced = findBinding(host);
            if (raced.isPresent() && raced.get().tenantId().equals(row.tenantId())) {
                return raced.get();
            }
            throw new TenantHostAlreadyBoundException("host already bound");
        }
        Binding bound = new Binding(host, row.tenantId(), now);
        audit.record(operator, AUDIT_BIND, row.tenantId() + ":" + host, AuditOutcome.ALLOWED);
        return bound;
    }

    public boolean unbind(OperatorPrincipal operator, String tenantId, String rawHost) {
        Objects.requireNonNull(operator, "operator");
        TenantRecord row = requireBusinessTenant(tenantId);
        String host = TenantHostNames.normalize(rawHost);
        if (host == null) {
            throw new IllegalArgumentException("invalid host");
        }
        int deleted = jdbc.update(
                "DELETE FROM tenant_host WHERE tenant_id = ? AND host_name = ?", row.tenantId(), host);
        if (deleted > 0) {
            audit.record(operator, AUDIT_UNBIND, row.tenantId() + ":" + host, AuditOutcome.ALLOWED);
            return true;
        }
        return false;
    }

    /**
     * Classify a already-normalized hostname — 对已规范化的主机名分类。
     */
    public Resolution resolveNormalized(String host) {
        if (host == null || host.isBlank() || TenantHostNames.isReservedPlatformHost(host, reservedHosts)) {
            return new Resolution(Kind.PLATFORM, host, null);
        }
        Optional<Binding> binding = findBinding(host);
        if (binding.isEmpty()) {
            return new Resolution(Kind.UNKNOWN, host, null);
        }
        TenantRecord row = tenants.find(binding.get().tenantId()).orElse(null);
        if (row == null || row.tenantState() != TenantState.ACTIVE) {
            throw new TenantHostClosedException("host tenant is not available");
        }
        if (JdbcOperatorDirectory.OPERATOR_TENANT.equals(row.tenantId())) {
            throw new TenantHostClosedException("host tenant is not available");
        }
        return new Resolution(Kind.BOUND, host, row.tenantId());
    }

    public Set<String> reservedHosts() {
        return reservedHosts;
    }

    private Optional<Binding> findBinding(String host) {
        List<Binding> rows = jdbc.query(
                """
                SELECT host_name, tenant_id, created_at
                FROM tenant_host
                WHERE host_name = ?
                """,
                (rs, n) -> new Binding(
                        rs.getString("host_name"),
                        rs.getString("tenant_id"),
                        rs.getTimestamp("created_at").toInstant()),
                host);
        if (rows.size() > 1) {
            // Unique key should prevent this; fail closed rather than pick a tenant.
            // 唯一键本应禁止这种情况；失败关闭，不挑一个租户。
            throw new TenantHostAmbiguousException("ambiguous Host");
        }
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private TenantRecord requireBusinessTenant(String tenantId) {
        TenantRecord row = tenants.find(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("tenant missing"));
        if (JdbcOperatorDirectory.OPERATOR_TENANT.equals(row.tenantId())) {
            throw new IllegalArgumentException("cannot bind a host to reserved tenant: platform");
        }
        return row;
    }

    private String requireBindableHost(String rawHost) {
        String host = TenantHostNames.normalize(rawHost);
        if (host == null || !TenantHostNames.isValidDnsHostname(host)) {
            throw new IllegalArgumentException("invalid host");
        }
        if (TenantHostNames.isReservedPlatformHost(host, reservedHosts)) {
            throw new IllegalArgumentException("host is reserved for the platform");
        }
        return host;
    }
}
