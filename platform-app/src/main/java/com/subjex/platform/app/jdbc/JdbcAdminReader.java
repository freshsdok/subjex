package com.subjex.platform.app.jdbc;

import com.subjex.platform.contract.task.DeadLetter;
import com.subjex.platform.contract.task.TaskRecord;
import com.subjex.platform.contract.tenant.TenantRecord;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcAdminReader — JDBC 管理台读取：只读租户、任务和死信。
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
}
