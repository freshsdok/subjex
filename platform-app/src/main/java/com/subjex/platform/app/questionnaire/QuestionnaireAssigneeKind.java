package com.subjex.platform.app.questionnaire;

import java.util.Locale;

/**
 * QuestionnaireAssigneeKind — who a questionnaire is assigned to (individual or department).
 * 问卷分配对象：个人主体或部门（组织）。不新增组织产品。
 */
public enum QuestionnaireAssigneeKind {
    SUBJECT,
    ORGANIZATION;

    public static QuestionnaireAssigneeKind require(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("assigneeKind is required");
        }
        try {
            return QuestionnaireAssigneeKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("assigneeKind must be SUBJECT or ORGANIZATION");
        }
    }
}
