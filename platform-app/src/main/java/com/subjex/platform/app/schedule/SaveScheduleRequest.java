package com.subjex.platform.app.schedule;

/**
 * SaveScheduleRequest — JSON body to create one schedule.
 * 创建一条调度的 JSON 体。
 */
public record SaveScheduleRequest(
        String name,
        String cronExpression,
        Boolean enabled,
        String action,
        String taskStepName,
        String workflowDefinitionKey,
        String workflowRecordId) {}
