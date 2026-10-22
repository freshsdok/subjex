package com.subjex.platform.contract.questionnaire;

/**
 * QuestionnaireStatus — 问卷生命周期：草稿 / 已发布 / 已关闭。
 * <p>
 * Published edits that change items must bump definition revision (ADR 0013 §D3).
 * 已发布后改题须提升定义 revision。
 */
public enum QuestionnaireStatus {
    DRAFT,
    PUBLISHED,
    CLOSED
}
