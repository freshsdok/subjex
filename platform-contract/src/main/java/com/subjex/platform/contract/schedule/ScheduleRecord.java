package com.subjex.platform.contract.schedule;

import java.time.Instant;

/**
 * ScheduleRecord — one operator schedule row as seen by the operator API.
 * 操作员 API 看到的一条调度行。
 * <p>
 * {@code timezone} is always {@code Asia/Shanghai}. Instants are stored in UTC.
 * 时区固定为 Asia/Shanghai。时间瞬时按 UTC 存储。
 */
public record ScheduleRecord(
        String scheduleId,
        String tenantId,
        String name,
        String cronExpression,
        String timezone,
        boolean enabled,
        ScheduleActionType actionType,
        String taskStepName,
        String workflowDefinitionKey,
        String workflowEntityKey,
        String workflowRecordId,
        Instant nextRunAt,
        Instant lastRunAt,
        String lastError,
        String createdBySubjectId,
        Instant createdAt,
        Instant updatedAt) {}
