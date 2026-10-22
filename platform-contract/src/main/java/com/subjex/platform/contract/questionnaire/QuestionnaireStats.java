package com.subjex.platform.contract.questionnaire;

import java.util.List;

/**
 * QuestionnaireStats — 问卷薄汇总（非 BI）：生产答卷数 + 可聚合题型计数。
 * <p>
 * OPERATOR preview rows are never included (ADR 0013 §D5/§D6).
 * 不含操作员预览行。
 *
 * @param questionnaireId id / 问卷 id
 * @param tenantId tenant / 租户
 * @param revision current definition revision (item labels) / 当前定义 revision（题目标签）
 * @param responseCount production response count / 生产答卷数
 * @param items aggregates for SINGLE/MULTI/RATING only / 仅单选/多选/评分
 */
public record QuestionnaireStats(
        String questionnaireId,
        String tenantId,
        int revision,
        long responseCount,
        List<QuestionnaireItemStats> items) {}
