package com.subjex.platform.app.jdbc;

import com.subjex.platform.contract.audit.AuditEntry;
import com.subjex.platform.contract.audit.AuditPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcAuditPort — JDBC 审计端口：{@link AuditPort} 的唯一实现。
 * <p>
 * One row in {@code audit_entry} is one recorded action. The actor column stores an identity id, not a secret.
 * Optional declaration linkage columns (entity_key / declaration_version / resolution_source) support RT-6.
 * {@code audit_entry} 里的一行就是一次记下的动作。操作者列存放身份标识，不存放密钥。
 * 可选声明关联列支持 RT-6。
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
                    (audit_entry_id, tenant_id, actor_identity_id, action_name, action_target, outcome, occurred_at,
                     entity_key, declaration_version, resolution_source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                entry.auditEntryId(),
                entry.tenantId(),
                entry.actorIdentityId(),
                entry.actionName(),
                entry.actionTarget(),
                entry.outcome().name(),
                PlatformTables.timestamp(entry.occurredAt()),
                entry.entityKey(),
                entry.declarationVersion(),
                entry.resolutionSource());
    }
}
