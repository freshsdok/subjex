package com.subjex.platform.contract.questionnaire;

import java.util.Map;

/**
 * QuestionnaireItemStats — 单题薄汇总（选择题/评分桶计数）。
 *
 * @param itemId item id / 题 id
 * @param questionType type / 题型
 * @param prompt prompt / 题干
 * @param counts option or rating-bucket → count (production only) / 选项或评分桶→次数（仅生产）
 */
public record QuestionnaireItemStats(
        String itemId, QuestionType questionType, String prompt, Map<String, Long> counts) {}
