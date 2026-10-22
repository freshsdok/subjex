package com.subjex.platform.contract.questionnaire;

/**
 * QuestionType — MVP 题型（ADR 0013 §D4）；矩阵/NPS/文件等后置。
 * <p>
 * Linear surveys only in MVP — no branching engine yet.
 * MVP 仅线性问卷，无跳题引擎。
 */
public enum QuestionType {
    SINGLE_CHOICE,
    MULTI_CHOICE,
    TEXT_SHORT,
    TEXT_LONG,
    RATING
}
