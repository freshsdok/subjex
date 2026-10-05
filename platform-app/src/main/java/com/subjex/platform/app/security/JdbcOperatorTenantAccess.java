package com.subjex.platform.app.security;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcOperatorTenantAccess — JDBC 实现的操作员—租户授权。
 */
public final class JdbcOperatorTenantAccess implements OperatorTenantAccess {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcOperatorTenantAccess(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
    }

    @Override
    public boolean isGranted(OperatorPrincipal operator, String tenantId) {
        if (operator == null || tenantId == null || tenantId.isBlank()) {
            return false;
        }
        String tenant = tenantId.trim();
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM operator_tenant_grant
                WHERE subject_id = ? AND (tenant_id = ? OR tenant_id = ?)
                """,
                Integer.class,
                operator.subjectId(),
                tenant,
                ALL_TENANTS);
        return count != null && count > 0;
    }

    @Override
    public void requireGranted(OperatorPrincipal operator, String tenantId) {
        if (!isGranted(operator, tenantId)) {
            throw new OperatorTenantNotGrantedException(tenantId == null ? "" : tenantId);
        }
    }

    @Override
    public List<String> listGrants(String subjectId) {
        return jdbc.queryForList(
                "SELECT tenant_id FROM operator_tenant_grant WHERE subject_id = ? ORDER BY tenant_id",
                String.class,
                subjectId);
    }

    @Override
    public void replaceGrants(String subjectId, List<String> tenantIds) {
        Objects.requireNonNull(subjectId, "subjectId");
        Set<String> unique = new LinkedHashSet<>();
        if (tenantIds != null) {
            for (String tenantId : tenantIds) {
                if (tenantId != null && !tenantId.isBlank()) {
                    unique.add(tenantId.trim());
                }
            }
        }
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM operator_tenant_grant WHERE subject_id = ?", subjectId);
            for (String tenantId : unique) {
                jdbc.update(
                        "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                        subjectId,
                        tenantId);
            }
        });
    }

    @Override
    public void ensureGrant(String subjectId, String tenantId) {
        Objects.requireNonNull(subjectId, "subjectId");
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenant id is required");
        }
        String tenant = tenantId.trim();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM operator_tenant_grant WHERE subject_id = ? AND tenant_id = ?",
                Integer.class,
                subjectId,
                tenant);
        if (count == null || count == 0) {
            jdbc.update(
                    "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                    subjectId,
                    tenant);
        }
    }
}
