package com.subjex.platform.app.questionnaire;

import java.time.Instant;
import java.util.Objects;

/**
 * QuestionnaireAssignment — one tenant-scoped assignment of a questionnaire.
 * 一份问卷在某租户下的一条分配。
 *
 * @param assignmentId assignment id — 分配标识
 * @param tenantId business tenant — 业务租户
 * @param questionnaireId questionnaire in that tenant — 该租户下的问卷
 * @param assigneeKind {@link QuestionnaireAssigneeKind#SUBJECT} or {@link QuestionnaireAssigneeKind#ORGANIZATION}
 * @param assigneeId subject id or organization id — 主体标识或组织标识
 * @param createdAt when recorded — 记录时间
 * @param createdBySubjectId acting operator subject, if known — 操作员主体（若有）
 */
public record QuestionnaireAssignment(
        String assignmentId,
        String tenantId,
        String questionnaireId,
        QuestionnaireAssigneeKind assigneeKind,
        String assigneeId,
        Instant createdAt,
        String createdBySubjectId) {

    public QuestionnaireAssignment {
        Objects.requireNonNull(assignmentId, "assignmentId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(questionnaireId, "questionnaireId");
        Objects.requireNonNull(assigneeKind, "assigneeKind");
        Objects.requireNonNull(assigneeId, "assigneeId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
