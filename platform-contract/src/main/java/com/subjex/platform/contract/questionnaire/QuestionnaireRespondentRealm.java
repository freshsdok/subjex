package com.subjex.platform.contract.questionnaire;

/**
 * QuestionnaireRespondentRealm — 答卷人领域（与站内信 realm 概念对齐但表分离）。
 * <p>
 * ANON subjects are opaque ids from collect tokens — not inbox subjects.
 * 匿名主体来自采集令牌，不复用站内信 subject。
 */
public enum QuestionnaireRespondentRealm {
    END_USER,
    OPERATOR,
    ANON
}
