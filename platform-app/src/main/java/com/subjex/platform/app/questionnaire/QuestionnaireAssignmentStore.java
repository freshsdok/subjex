package com.subjex.platform.app.questionnaire;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * QuestionnaireAssignmentStore — persist tenant-scoped questionnaire assignments.
 * 持久化租户内问卷分配，不改问卷编排模型。
 */
public interface QuestionnaireAssignmentStore {

    List<QuestionnaireAssignment> list(String tenantId, String questionnaireId);

    /**
     * Assignments for many questionnaires in one tenant (empty if ids is empty).
     * 一批问卷在同一租户下的分配；标识为空则空列表。
     */
    List<QuestionnaireAssignment> list(String tenantId, Collection<String> questionnaireIds);

    Optional<QuestionnaireAssignment> find(String tenantId, String questionnaireId, String assignmentId);

    Optional<QuestionnaireAssignment> findByAssignee(
            String tenantId,
            String questionnaireId,
            QuestionnaireAssigneeKind kind,
            String assigneeId);

    QuestionnaireAssignment insert(QuestionnaireAssignment assignment);

    /**
     * Delete one assignment in the given tenant and questionnaire. Returns false when missing.
     * 删除该租户该问卷下的一条分配；不存在则返回 false。
     */
    boolean delete(String tenantId, String questionnaireId, String assignmentId);
}
