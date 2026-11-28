package com.subjex.platform.app.schedule;

import com.subjex.platform.app.jdbc.PlatformTables;
import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.schedule.CreateScheduleCommand;
import com.subjex.platform.contract.schedule.ScheduleActionType;
import com.subjex.platform.contract.schedule.ScheduleClaim;
import com.subjex.platform.contract.schedule.ScheduleNotFoundException;
import com.subjex.platform.contract.schedule.SchedulePort;
import com.subjex.platform.contract.schedule.ScheduleRecord;
import com.subjex.platform.contract.tenant.TenantMissingException;
import com.subjex.platform.contract.tenant.TenantState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcSchedulePort — persist operator schedules and take short FOR UPDATE leases.
 * 持久化操作员调度，并以短时 FOR UPDATE 领取租约。
 */
public final class JdbcSchedulePort implements SchedulePort {

    /** Reserved tenant that is never a business home — 保留租户，不是业务归属. */
    public static final String RESERVED_TENANT = "platform";

    static final Duration DEFAULT_LEASE_TTL = Duration.ofMinutes(2);

    private static final String SELECT_ROW =
            """
            SELECT schedule_id, tenant_id, schedule_name, cron_expression, enabled, action_type,
                   task_step_name, workflow_definition_key, workflow_entity_key, workflow_record_id,
                   next_run_at, last_run_at, last_error, lease_owner, lease_expires_at,
                   created_by_subject_id, created_by_identity_id, created_at, updated_at
              FROM operator_schedule
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public JdbcSchedulePort(JdbcTemplate jdbc, TransactionTemplate transaction, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ScheduleRecord create(CreateScheduleCommand command, String workflowEntityKey, Instant nextRunAt) {
        Objects.requireNonNull(command, "command");
        String tenant = requireActiveBusinessTenant(command.tenantId());
        Instant now = clock.instant();
        String id = UUID.randomUUID().toString();
        transaction.executeWithoutResult(status -> jdbc.update(
                """
                INSERT INTO operator_schedule (
                    schedule_id, tenant_id, schedule_name, cron_expression, enabled, action_type,
                    task_step_name, workflow_definition_key, workflow_entity_key, workflow_record_id,
                    next_run_at, last_run_at, last_error, lease_owner, lease_expires_at,
                    created_by_subject_id, created_by_identity_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, ?, ?, ?, ?)
                """,
                id,
                tenant,
                command.name(),
                command.cronExpression(),
                command.enabled(),
                command.actionType().name(),
                command.taskStepName(),
                command.workflowDefinitionKey(),
                workflowEntityKey,
                command.workflowRecordId(),
                PlatformTables.timestamp(nextRunAt),
                command.createdBySubjectId(),
                command.createdByIdentityId(),
                PlatformTables.timestamp(now),
                PlatformTables.timestamp(now)));
        return find(tenant, id).orElseThrow(() -> new ScheduleNotFoundException(id));
    }

    @Override
    public List<ScheduleRecord> list(String tenantId, int limit) {
        String tenant = requireActiveBusinessTenant(tenantId);
        return jdbc.query(
                SELECT_ROW + " WHERE tenant_id = ? ORDER BY created_at DESC, schedule_id DESC LIMIT ?",
                this::mapRecord,
                tenant,
                limit);
    }

    @Override
    public Optional<ScheduleRecord> find(String tenantId, String scheduleId) {
        String tenant = requireActiveBusinessTenant(tenantId);
        if (scheduleId == null || scheduleId.isBlank()) {
            return Optional.empty();
        }
        List<ScheduleRecord> rows = jdbc.query(
                SELECT_ROW + " WHERE tenant_id = ? AND schedule_id = ?",
                this::mapRecord,
                tenant,
                scheduleId.trim());
        return rows.stream().findFirst();
    }

    @Override
    public ScheduleRecord enable(String tenantId, String scheduleId, Instant nextRunAt, Instant now) {
        String tenant = requireActiveBusinessTenant(tenantId);
        String id = requireId(scheduleId, "scheduleId");
        Objects.requireNonNull(nextRunAt, "nextRunAt");
        Objects.requireNonNull(now, "now");
        Integer updated = transaction.execute(status -> jdbc.update(
                """
                UPDATE operator_schedule
                   SET enabled = TRUE, next_run_at = ?, last_error = NULL,
                       lease_owner = NULL, lease_expires_at = NULL, updated_at = ?
                 WHERE tenant_id = ? AND schedule_id = ?
                """,
                PlatformTables.timestamp(nextRunAt),
                PlatformTables.timestamp(now),
                tenant,
                id));
        if (updated == null || updated == 0) {
            throw new ScheduleNotFoundException(id);
        }
        return find(tenant, id).orElseThrow(() -> new ScheduleNotFoundException(id));
    }

    @Override
    public ScheduleRecord disable(String tenantId, String scheduleId, Instant now) {
        String tenant = requireActiveBusinessTenant(tenantId);
        String id = requireId(scheduleId, "scheduleId");
        Objects.requireNonNull(now, "now");
        Integer updated = transaction.execute(status -> jdbc.update(
                """
                UPDATE operator_schedule
                   SET enabled = FALSE, lease_owner = NULL, lease_expires_at = NULL, updated_at = ?
                 WHERE tenant_id = ? AND schedule_id = ?
                """,
                PlatformTables.timestamp(now),
                tenant,
                id));
        if (updated == null || updated == 0) {
            throw new ScheduleNotFoundException(id);
        }
        return find(tenant, id).orElseThrow(() -> new ScheduleNotFoundException(id));
    }

    @Override
    public List<String> listDue(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        int cap = Math.max(1, Math.min(limit, 100));
        return jdbc.query(
                """
                SELECT schedule_id FROM operator_schedule
                 WHERE enabled = TRUE
                   AND next_run_at IS NOT NULL
                   AND next_run_at <= ?
                   AND (lease_owner IS NULL
                        OR lease_expires_at IS NULL
                        OR lease_expires_at <= ?)
                 ORDER BY next_run_at ASC, schedule_id ASC
                 LIMIT ?
                """,
                (row, n) -> row.getString("schedule_id"),
                PlatformTables.timestamp(now),
                PlatformTables.timestamp(now),
                cap);
    }

    @Override
    public Optional<ScheduleClaim> claim(String scheduleId, String owner, Instant now, Duration ttl) {
        if (scheduleId == null || scheduleId.isBlank() || owner == null || owner.isBlank()) {
            return Optional.empty();
        }
        Objects.requireNonNull(now, "now");
        Duration leaseTtl = ttl == null || ttl.isZero() || ttl.isNegative() ? DEFAULT_LEASE_TTL : ttl;
        Optional<Row> locked = findForUpdate(scheduleId.trim());
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        Row row = locked.get();
        if (!row.enabled()
                || row.nextRunAt() == null
                || row.nextRunAt().isAfter(now)) {
            return Optional.empty();
        }
        if (row.leaseOwner() != null
                && row.leaseExpiresAt() != null
                && row.leaseExpiresAt().isAfter(now)) {
            return Optional.empty();
        }
        Instant expires = now.plus(leaseTtl);
        jdbc.update(
                """
                UPDATE operator_schedule
                   SET lease_owner = ?, lease_expires_at = ?, updated_at = ?
                 WHERE schedule_id = ?
                """,
                owner.trim(),
                PlatformTables.timestamp(expires),
                PlatformTables.timestamp(now),
                scheduleId.trim());
        return Optional.of(new ScheduleClaim(
                row.scheduleId(),
                row.tenantId(),
                row.cronExpression(),
                row.actionType(),
                row.taskStepName(),
                row.workflowDefinitionKey(),
                row.workflowEntityKey(),
                row.workflowRecordId(),
                row.nextRunAt(),
                row.createdBySubjectId(),
                row.createdByIdentityId()));
    }

    @Override
    public boolean renewLease(String scheduleId, String owner, Instant now, Duration ttl) {
        if (scheduleId == null || scheduleId.isBlank() || owner == null || owner.isBlank()) {
            return false;
        }
        Objects.requireNonNull(now, "now");
        Duration leaseTtl = ttl == null || ttl.isZero() || ttl.isNegative() ? DEFAULT_LEASE_TTL : ttl;
        int updated = jdbc.update(
                """
                UPDATE operator_schedule
                   SET lease_expires_at = ?, updated_at = ?
                 WHERE schedule_id = ?
                   AND lease_owner = ?
                   AND lease_expires_at IS NOT NULL
                   AND lease_expires_at > ?
                """,
                PlatformTables.timestamp(now.plus(leaseTtl)),
                PlatformTables.timestamp(now),
                scheduleId.trim(),
                owner.trim(),
                PlatformTables.timestamp(now));
        return updated == 1;
    }

    @Override
    public boolean complete(String scheduleId, String owner, Instant now, Instant nextRunAt) {
        if (scheduleId == null || scheduleId.isBlank() || owner == null || owner.isBlank()) {
            return false;
        }
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(nextRunAt, "nextRunAt");
        findForUpdate(scheduleId.trim());
        int updated = jdbc.update(
                """
                UPDATE operator_schedule
                   SET last_run_at = ?, last_error = NULL, next_run_at = ?,
                       lease_owner = NULL, lease_expires_at = NULL, updated_at = ?
                 WHERE schedule_id = ?
                   AND lease_owner = ?
                   AND lease_expires_at IS NOT NULL
                   AND lease_expires_at > ?
                """,
                PlatformTables.timestamp(now),
                PlatformTables.timestamp(nextRunAt),
                PlatformTables.timestamp(now),
                scheduleId.trim(),
                owner.trim(),
                PlatformTables.timestamp(now));
        return updated == 1;
    }

    @Override
    public boolean releaseFailed(String scheduleId, String owner, Instant now, String error) {
        if (scheduleId == null || scheduleId.isBlank() || owner == null || owner.isBlank()) {
            return false;
        }
        Objects.requireNonNull(now, "now");
        findForUpdate(scheduleId.trim());
        int updated = jdbc.update(
                """
                UPDATE operator_schedule
                   SET last_error = ?, lease_owner = NULL, lease_expires_at = NULL, updated_at = ?
                 WHERE schedule_id = ?
                   AND lease_owner = ?
                """,
                trimError(error),
                PlatformTables.timestamp(now),
                scheduleId.trim(),
                owner.trim());
        return updated == 1;
    }

    private Optional<Row> findForUpdate(String scheduleId) {
        List<Row> rows = jdbc.query(SELECT_ROW + " WHERE schedule_id = ? FOR UPDATE", this::mapRow, scheduleId);
        return rows.stream().findFirst();
    }

    private ScheduleRecord mapRecord(ResultSet row, int index) throws SQLException {
        Row mapped = mapRow(row, index);
        return new ScheduleRecord(
                mapped.scheduleId(),
                mapped.tenantId(),
                mapped.name(),
                mapped.cronExpression(),
                ScheduleCron.ZONE_NAME,
                mapped.enabled(),
                mapped.actionType(),
                mapped.taskStepName(),
                mapped.workflowDefinitionKey(),
                mapped.workflowEntityKey(),
                mapped.workflowRecordId(),
                mapped.nextRunAt(),
                mapped.lastRunAt(),
                mapped.lastError(),
                mapped.createdBySubjectId(),
                mapped.createdAt(),
                mapped.updatedAt());
    }

    private Row mapRow(ResultSet row, int index) throws SQLException {
        return new Row(
                row.getString("schedule_id"),
                row.getString("tenant_id"),
                row.getString("schedule_name"),
                row.getString("cron_expression"),
                row.getBoolean("enabled"),
                ScheduleActionType.valueOf(row.getString("action_type")),
                row.getString("task_step_name"),
                row.getString("workflow_definition_key"),
                row.getString("workflow_entity_key"),
                row.getString("workflow_record_id"),
                instant(row, "next_run_at"),
                instant(row, "last_run_at"),
                row.getString("last_error"),
                row.getString("lease_owner"),
                instant(row, "lease_expires_at"),
                row.getString("created_by_subject_id"),
                row.getString("created_by_identity_id"),
                instant(row, "created_at"),
                instant(row, "updated_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp timestamp = row.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String requireActiveBusinessTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        String id = tenantId.trim();
        if (RESERVED_TENANT.equals(id)) {
            throw new IllegalArgumentException("reserved tenant " + RESERVED_TENANT + " is not a schedule home");
        }
        List<String> states = jdbc.query(
                "SELECT tenant_state FROM tenant WHERE tenant_id = ?",
                (row, n) -> row.getString("tenant_state"),
                id);
        if (states.isEmpty()) {
            throw new TenantNotFoundException(id);
        }
        if (!TenantState.ACTIVE.name().equals(states.get(0))) {
            throw new TenantDisabledException(id);
        }
        return id;
    }

    private static String requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static String trimError(String error) {
        if (error == null || error.isBlank()) {
            return "schedule fire failed";
        }
        String trimmed = error.trim();
        return trimmed.length() <= 512 ? trimmed : trimmed.substring(0, 512);
    }

    private record Row(
            String scheduleId,
            String tenantId,
            String name,
            String cronExpression,
            boolean enabled,
            ScheduleActionType actionType,
            String taskStepName,
            String workflowDefinitionKey,
            String workflowEntityKey,
            String workflowRecordId,
            Instant nextRunAt,
            Instant lastRunAt,
            String lastError,
            String leaseOwner,
            Instant leaseExpiresAt,
            String createdBySubjectId,
            String createdByIdentityId,
            Instant createdAt,
            Instant updatedAt) {}
}
