package com.subjex.platform.contract.questionnaire;

/**
 * QuestionnaireItem — 一道题（定义 revision 内有序）。
 *
 * @param itemId stable id within the questionnaire / 问卷内稳定题 id
 * @param sortOrder display order ascending / 展示序（升序）
 * @param questionType MVP type / MVP 题型
 * @param prompt question text / 题干
 * @param required whether answer required / 是否必答
 * @param optionsJson choices or rating max as JSON text; may be null for plain text /
 *     选项或评分上限的 JSON 文本；纯文本题可为 null
 */
public record QuestionnaireItem(
        String itemId,
        int sortOrder,
        QuestionType questionType,
        String prompt,
        boolean required,
        String optionsJson) {}
