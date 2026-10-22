package com.subjex.platform.contract.questionnaire;

import java.time.Instant;
import java.util.List;

/**
 * QuestionnaireDefinition — 问卷定义头 + 当前 revision 下的题目列表。
 *
 * @param questionnaireId id / 问卷 id
 * @param tenantId tenant fail-closed / 租户（失败关闭）
 * @param title title / 标题
 * @param status lifecycle / 生命周期
 * @param revision monotonic per published edit / 发布后改题递增
 * @param collectMode how respondents authenticate / 采集模式
 * @param items ordered items for this revision / 本 revision 题目（有序）
 * @param createdAt created / 创建时间
 * @param updatedAt updated / 更新时间
 */
public record QuestionnaireDefinition(
        String questionnaireId,
        String tenantId,
        String title,
        QuestionnaireStatus status,
        int revision,
        QuestionnaireCollectMode collectMode,
        List<QuestionnaireItem> items,
        Instant createdAt,
        Instant updatedAt) {}
