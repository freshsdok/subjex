package com.subjex.platform.app.jdbc;

import com.subjex.platform.app.admin.AuditQuery;
import com.subjex.platform.app.admin.AuditRow;
import com.subjex.platform.contract.task.DeadLetter;
import com.subjex.platform.contract.task.TaskRecord;
import com.subjex.platform.contract.tenant.TenantRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcAdminReader — JDBC 管理台读取：只读租户、任务、死信和审计条目。
 * <p>
 * The console does not update these rows. SQL is the read path; there is no entity model in between.
 * 管理台不更新这些行。SQL 就是读取路径，中间没有实体模型。
 */
public final class JdbcAdminReader {

    private final JdbcTemplate jdbc;

    public JdbcAdminReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TenantRecord> tenants() {
        return jdbc.query(
                "SELECT tenant_id, tenant_name, tenant_state FROM tenant ORDER BY tenant_id",
                (row, rowNumber) -> PlatformTables.mapTenant(row));
    }

    public List<TaskRecord> tasks() {
        return jdbc.query(
                """
                SELECT task_id, tenant_id, task_kind, task_state, step_name, attempt_count, max_attempt,
                       model_id, input_digest, human_confirmation, failure_reason, created_at
                FROM platform_task
                ORDER BY created_at
                """,
                (row, rowNumber) -> PlatformTables.mapTask(row));
    }

    public List<DeadLetter> deadLetters() {
        return jdbc.query(
                """
                SELECT dead_letter_id, tenant_id, origin_kind, origin_id, failure_reason, recorded_at, discarded_at
                FROM dead_letter
                WHERE discarded_at IS NULL
                ORDER BY recorded_at DESC
                """,
                (row, rowNumber) -> PlatformTables.mapDeadLetter(row));
    }

    /**
     * Newest audit entries first, with the actor's login when it has one — 最新的审计条目在前，能连上登录名时带上登录名。
     */
    public List<AuditRow> auditEntries(int limit) {
        return auditEntries(AuditQuery.unfiltered(limit));
    }

    /**
     * OBS-1 filtered audit query — 带筛选的审计查询。
     * {@code actionName} matches exact or prefix ({@code operator.auth} → {@code operator.auth.%}).
     */
    public List<AuditRow> auditEntries(AuditQuery query) {
        AuditQuery q = query == null ? AuditQuery.unfiltered(AuditQuery.DEFAULT_LIMIT) : query;
        StringBuilder sql = new StringBuilder(
                """
                SELECT e.audit_entry_id, e.occurred_at, e.tenant_id, e.actor_identity_id, a.login_name,
                       e.action_name, e.action_target, e.outcome,
                       e.entity_key, e.declaration_version, e.resolution_source
                FROM audit_entry e
                LEFT JOIN subject_identity i ON i.identity_id = e.actor_identity_id
                LEFT JOIN account a ON a.account_id = i.account_id
                WHERE 1=1
                """);
        ArrayList<Object> args = new ArrayList<>();
        if (q.tenantId() != null) {
            sql.append(" AND e.tenant_id = ?");
            args.add(q.tenantId());
        }
        if (q.actorLogin() != null) {
            sql.append(" AND a.login_name = ?");
            args.add(q.actorLogin());
        }
        if (q.actionName() != null) {
            // Exact match, or prefix when no trailing wildcard intent (contains no %).
            // 精确匹配；不含 % 时兼作前缀（action 或 action.%）。
            if (q.actionName().contains("%")) {
                sql.append(" AND e.action_name LIKE ?");
                args.add(q.actionName());
            } else {
                sql.append(" AND (e.action_name = ? OR e.action_name LIKE ?)");
                args.add(q.actionName());
                args.add(q.actionName() + ".%");
            }
        }
        if (q.from() != null) {
            sql.append(" AND e.occurred_at >= ?");
            args.add(PlatformTables.timestamp(q.from()));
        }
        if (q.to() != null) {
            sql.append(" AND e.occurred_at <= ?");
            args.add(PlatformTables.timestamp(q.to()));
        }
        sql.append(" ORDER BY e.occurred_at DESC, e.audit_entry_id");
        return jdbc.query(
                sql.toString(),
                args.toArray(),
                rows -> {
                    ArrayList<AuditRow> found = new ArrayList<>();
                    while (rows.next() && found.size() < q.limit()) {
                        found.add(PlatformTables.mapAuditRow(rows));
                    }
                    return found;
                });
    }
}
