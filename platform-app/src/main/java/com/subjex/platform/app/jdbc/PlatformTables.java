package com.subjex.platform.app.jdbc;

import com.subjex.platform.app.admin.AuditRow;
import com.subjex.platform.contract.task.DeadLetter;
import com.subjex.platform.contract.task.HumanConfirmation;
import com.subjex.platform.contract.task.OriginKind;
import com.subjex.platform.contract.task.OutboxEvent;
import com.subjex.platform.contract.task.OutboxState;
import com.subjex.platform.contract.task.TaskKind;
import com.subjex.platform.contract.task.TaskRecord;
import com.subjex.platform.contract.task.TaskState;
import com.subjex.platform.contract.tenant.TenantRecord;
import com.subjex.platform.contract.tenant.TenantState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * PlatformTables — 平台表：行与契约记录之间的 JDBC 对应。
 * <p>
 * This is not an entity model. Each method reads columns of one table. Business reads and writes stay on this path.
 * 这不是实体模型。每个方法只读一张表的列。业务读写留在这条路径上。
 */
public final class PlatformTables {

    private PlatformTables() {
    }

    public static TaskRecord mapTask(ResultSet row) throws SQLException {
        return new TaskRecord(
                row.getString("task_id"),
                row.getString("tenant_id"),
                TaskKind.valueOf(row.getString("task_kind")),
                TaskState.valueOf(row.getString("task_state")),
                row.getString("step_name"),
                row.getInt("attempt_count"),
                row.getInt("max_attempt"),
                row.getString("model_id"),
                row.getString("input_digest"),
                enumOrNull(HumanConfirmation.class, row.getString("human_confirmation")),
                row.getString("failure_reason"),
                instant(row, "created_at"));
    }

    public static OutboxEvent mapOutbox(ResultSet row) throws SQLException {
        return new OutboxEvent(
                row.getString("event_id"),
                row.getString("tenant_id"),
                row.getString("event_name"),
                row.getString("event_body"),
                OutboxState.valueOf(row.getString("event_state")),
                row.getString("trace_id"),
                row.getInt("attempt_count"),
                row.getString("failure_reason"),
                instant(row, "occurred_at"),
                instant(row, "published_at"));
    }

    public static DeadLetter mapDeadLetter(ResultSet row) throws SQLException {
        return new DeadLetter(
                row.getString("dead_letter_id"),
                row.getString("tenant_id"),
                OriginKind.valueOf(row.getString("origin_kind")),
                row.getString("origin_id"),
                row.getString("failure_reason"),
                instant(row, "recorded_at"));
    }

    public static TenantRecord mapTenant(ResultSet row) throws SQLException {
        return new TenantRecord(
                row.getString("tenant_id"),
                row.getString("tenant_name"),
                TenantState.valueOf(row.getString("tenant_state")));
    }

    public static AuditRow mapAuditRow(ResultSet row) throws SQLException {
        Integer declarationVersion = (Integer) row.getObject("declaration_version");
        return new AuditRow(
                row.getString("audit_entry_id"),
                instant(row, "occurred_at"),
                row.getString("tenant_id"),
                row.getString("actor_identity_id"),
                row.getString("login_name"),
                row.getString("action_name"),
                row.getString("action_target"),
                row.getString("outcome"),
                row.getString("entity_key"),
                declarationVersion,
                row.getString("resolution_source"));
    }

    public static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp timestamp = row.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
