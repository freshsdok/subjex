package com.subjex.platform.app.jdbc;

import com.subjex.platform.app.admin.AuditRow;
import com.subjex.platform.contract.task.DeadLetter;
import com.subjex.platform.contract.task.TaskRecord;
import com.subjex.platform.contract.tenant.TenantRecord;
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
                SELECT dead_letter_id, tenant_id, origin_kind, origin_id, failure_reason, recorded_at
                FROM dead_letter
                ORDER BY recorded_at
                """,
                (row, rowNumber) -> PlatformTables.mapDeadLetter(row));
    }

    /**
     * Newest audit entries first, with the actor's login when it has one — 最新的审计条目在前，能连上登录名时带上登录名。
     */
    public List<AuditRow> auditEntries(int limit) {
        return jdbc.query(
                """
                SELECT e.audit_entry_id, e.occurred_at, e.tenant_id, e.actor_identity_id, a.login_name,
                       e.action_name, e.action_target, e.outcome
                FROM audit_entry e
                LEFT JOIN subject_identity i ON i.identity_id = e.actor_identity_id
                LEFT JOIN account a ON a.account_id = i.account_id
                ORDER BY e.occurred_at DESC, e.audit_entry_id
                """,
                rows -> {
                    ArrayList<AuditRow> found = new ArrayList<>();
                    while (rows.next() && found.size() < limit) {
                        found.add(PlatformTables.mapAuditRow(rows));
                    }
                    return found;
                });
    }
}
