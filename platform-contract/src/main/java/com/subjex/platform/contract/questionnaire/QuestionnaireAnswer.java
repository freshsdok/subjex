package com.subjex.platform.contract.questionnaire;

/**
 * QuestionnaireAnswer — 一题的答值（JSON 文本）。
 *
 * @param itemId item id for the bound revision / 绑定 revision 下的题 id
 * @param valueJson answer payload as JSON text / 答值 JSON 文本
 */
public record QuestionnaireAnswer(String itemId, String valueJson) {}
