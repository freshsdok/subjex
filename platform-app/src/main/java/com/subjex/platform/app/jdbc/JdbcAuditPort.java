package com.subjex.platform.app.jdbc;

import com.subjex.platform.contract.audit.AuditEntry;
import com.subjex.platform.contract.audit.AuditPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcAuditPort — JDBC 审计端口：{@link AuditPort} 的唯一实现。
 * <p>
 * One row in {@code audit_entry} is one recorded action. The actor column stores an identity id, not a secret.
 * {@code audit_entry} 里的一行就是一次记下的动作。操作者列存放身份标识，不存放密钥。
 */
public final class JdbcAuditPort implements AuditPort {

    private final JdbcTemplate jdbc;

    public JdbcAuditPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(AuditEntry entry) {
        jdbc.update(
                """
                INSERT INTO audit_entry
                    (audit_entry_id, tenant_id, actor_identity_id, action_name, action_target, outcome, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                entry.auditEntryId(),
                entry.tenantId(),
                entry.actorIdentityId(),
                entry.actionName(),
                entry.actionTarget(),
                entry.outcome().name(),
                PlatformTables.timestamp(entry.occurredAt()));
    }
}
