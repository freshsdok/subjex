package com.subjex.platform.contract.schedule;

/**
 * CreateScheduleCommand — facts required to create one operator schedule.
 * 创建一条操作员调度所需的事实。
 */
public record CreateScheduleCommand(
        String tenantId,
        String name,
        String cronExpression,
        boolean enabled,
        ScheduleActionType actionType,
        String taskStepName,
        String workflowDefinitionKey,
        String workflowRecordId,
        String createdBySubjectId,
        String createdByIdentityId) {}
