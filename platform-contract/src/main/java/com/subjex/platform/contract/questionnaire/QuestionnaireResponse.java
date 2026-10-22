package com.subjex.platform.contract.questionnaire;

import java.time.Instant;
import java.util.List;

/**
 * QuestionnaireResponse — 一次答卷（含答案）；操作员预览不计入生产统计。
 * <p>
 * {@link #countsForStats()} is false when {@code respondentRealm == OPERATOR} (ADR 0013 §D5 preview).
 * 操作员预览（OPERATOR）不计入生产统计。
 *
 * @param responseId id / 答卷 id
 * @param questionnaireId questionnaire / 问卷 id
 * @param revision definition revision at submit / 提交时定义 revision
 * @param tenantId tenant / 租户
 * @param respondentRealm realm / 答卷人领域
 * @param respondentSubjectId subject id / 主体 id
 * @param submittedAt when / 提交时间
 * @param answers answers / 答案列表
 */
public record QuestionnaireResponse(
        String responseId,
        String questionnaireId,
        int revision,
        String tenantId,
        QuestionnaireRespondentRealm respondentRealm,
        String respondentSubjectId,
        Instant submittedAt,
        List<QuestionnaireAnswer> answers) {

    /**
     * Whether this row counts toward production aggregates — 是否计入生产汇总。
     */
    public boolean countsForStats() {
        return respondentRealm != QuestionnaireRespondentRealm.OPERATOR;
    }
}
