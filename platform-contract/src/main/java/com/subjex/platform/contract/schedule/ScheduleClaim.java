package com.subjex.platform.contract.schedule;

import java.time.Instant;

/**
 * ScheduleClaim — a due schedule row after a successful lease claim, including fire facts.
 * 成功领取租约后的到期调度行，含触发所需事实。
 */
public record ScheduleClaim(
        String scheduleId,
        String tenantId,
        String cronExpression,
        ScheduleActionType actionType,
        String taskStepName,
        String workflowDefinitionKey,
        String workflowEntityKey,
        String workflowRecordId,
        Instant dueAt,
        String createdBySubjectId,
        String createdByIdentityId) {}
