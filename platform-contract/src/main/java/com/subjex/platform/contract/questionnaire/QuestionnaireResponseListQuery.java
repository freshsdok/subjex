package com.subjex.platform.contract.questionnaire;

/**
 * QuestionnaireResponseListQuery — 答卷列表查询。
 * <p>
 * Default {@code includePreview=false} excludes OPERATOR preview rows from production listings.
 * 默认排除操作员预览行。
 *
 * @param includePreview when true, include OPERATOR preview responses / true 时含预览
 * @param limit max rows (clamped like definition list) / 条数上限
 */
public record QuestionnaireResponseListQuery(boolean includePreview, int limit) {

    public static QuestionnaireResponseListQuery production(int limit) {
        return new QuestionnaireResponseListQuery(false, limit);
    }

    public static QuestionnaireResponseListQuery includingPreview(int limit) {
        return new QuestionnaireResponseListQuery(true, limit);
    }
}
