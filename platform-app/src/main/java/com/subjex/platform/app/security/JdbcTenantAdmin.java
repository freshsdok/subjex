package com.subjex.platform.app.security;

import com.subjex.platform.app.jdbc.PlatformTables;
import com.subjex.platform.contract.tenant.TenantRecord;
import com.subjex.platform.contract.tenant.TenantState;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcTenantAdmin — 租户管理：列表/读取、创建、改显示名、禁用/启用（软禁用）。
 * <p>
 * Disable sets {@code tenant_state=SUSPENDED}; enable restores {@code ACTIVE}. The reserved tenant
 * {@code platform} cannot be mutated. Soft-disable keeps rows and grants.
 * 禁用将状态标为 SUSPENDED；启用恢复 ACTIVE。保留租户 {@code platform} 不可改。软禁用保留行与授权。
 */
public final class JdbcTenantAdmin {

    private final JdbcTemplate jdbc;

    public JdbcTenantAdmin(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public List<TenantRecord> list(String searchQuery) {
        if (searchQuery == null || searchQuery.isBlank()) {
            return jdbc.query(
                    "SELECT tenant_id, tenant_name, tenant_state FROM tenant ORDER BY tenant_id",
                    (row, n) -> PlatformTables.mapTenant(row));
        }
        String needle = "%" + searchQuery.trim().toLowerCase(Locale.ROOT) + "%";
        return jdbc.query(
                """
                SELECT tenant_id, tenant_name, tenant_state FROM tenant
                WHERE LOWER(tenant_id) LIKE ? OR LOWER(tenant_name) LIKE ?
                ORDER BY tenant_id
                """,
                (row, n) -> PlatformTables.mapTenant(row),
                needle,
                needle);
    }

    public Optional<TenantRecord> find(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        List<TenantRecord> rows = jdbc.query(
                "SELECT tenant_id, tenant_name, tenant_state FROM tenant WHERE tenant_id = ?",
                (row, n) -> PlatformTables.mapTenant(row),
                tenantId.trim());
        return rows.stream().findFirst();
    }

    public TenantRecord require(String tenantId) {
        return find(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("unknown tenant: " + tenantId));
    }

    public TenantRecord create(String tenantId, String tenantName) {
        String id = requireTenantId(tenantId);
        refuseReserved(id);
        String name = requireTenantName(tenantName == null || tenantName.isBlank() ? id : tenantName);
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Integer.class, id);
        if (existing != null && existing > 0) {
            throw new IllegalArgumentException("tenant already exists: " + id);
        }
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, ?)",
                id,
                name,
                TenantState.ACTIVE.name());
        return new TenantRecord(id, name, TenantState.ACTIVE);
    }

    public TenantRecord rename(String tenantId, String tenantName) {
        String id = requireTenantId(tenantId);
        refuseReserved(id);
        TenantRecord current = require(id);
        String name = requireTenantName(tenantName);
        jdbc.update("UPDATE tenant SET tenant_name = ? WHERE tenant_id = ?", name, id);
        return new TenantRecord(current.tenantId(), name, current.tenantState());
    }

    public TenantRecord disable(String tenantId) {
        return setState(tenantId, TenantState.SUSPENDED);
    }

    public TenantRecord enable(String tenantId) {
        return setState(tenantId, TenantState.ACTIVE);
    }

    /**
     * Refuse writes when the tenant exists but is not ACTIVE (soft-disabled).
     * 租户存在但非 ACTIVE（软禁用）时拒绝写入。
     */
    public void requireActiveForTaskWrite(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenant id is required");
        }
        Optional<TenantRecord> found = find(tenantId);
        if (found.isEmpty()) {
            return;
        }
        if (found.get().tenantState() != TenantState.ACTIVE) {
            throw new TenantDisabledException(tenantId.trim());
        }
    }


    private TenantRecord setState(String tenantId, TenantState state) {
        String id = requireTenantId(tenantId);
        refuseReserved(id);
        TenantRecord current = require(id);
        jdbc.update("UPDATE tenant SET tenant_state = ? WHERE tenant_id = ?", state.name(), id);
        return new TenantRecord(current.tenantId(), current.tenantName(), state);
    }

    private static void refuseReserved(String tenantId) {
        if (JdbcOperatorDirectory.OPERATOR_TENANT.equals(tenantId)) {
            throw new IllegalArgumentException("cannot mutate reserved tenant: " + tenantId);
        }
    }

    static String requireTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenant id is required");
        }
        String id = tenantId.trim();
        if (id.length() > 64) {
            throw new IllegalArgumentException("tenant id must be at most 64 characters");
        }
        if (id.chars().anyMatch(Character::isWhitespace) || id.contains("/")) {
            throw new IllegalArgumentException("tenant id cannot contain spaces or slashes");
        }
        return id;
    }

    static String requireTenantName(String tenantName) {
        if (tenantName == null || tenantName.isBlank()) {
            throw new IllegalArgumentException("tenant name is required");
        }
        String name = tenantName.trim();
        if (name.length() > 256) {
            throw new IllegalArgumentException("tenant name must be at most 256 characters");
        }
        return name;
    }
}
