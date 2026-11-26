package com.subjex.platform.app.questionnaire;

import com.subjex.platform.app.jdbc.PlatformTables;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JdbcQuestionnaireAssignmentStore — JDBC {@code questionnaire_assignment}.
 * 问卷分配表的 JDBC 实现。
 */
public final class JdbcQuestionnaireAssignmentStore implements QuestionnaireAssignmentStore {

    private static final RowMapper<QuestionnaireAssignment> ROW = (row, n) -> new QuestionnaireAssignment(
            row.getString("assignment_id"),
            row.getString("tenant_id"),
            row.getString("questionnaire_id"),
            QuestionnaireAssigneeKind.valueOf(row.getString("assignee_kind")),
            row.getString("assignee_id"),
            instant(row, "created_at"),
            row.getString("created_by_subject_id"));

    private final JdbcTemplate jdbc;

    public JdbcQuestionnaireAssignmentStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<QuestionnaireAssignment> list(String tenantId, String questionnaireId) {
        if (blank(tenantId) || blank(questionnaireId)) {
            return List.of();
        }
        return jdbc.query(
                """
                SELECT assignment_id, tenant_id, questionnaire_id, assignee_kind, assignee_id,
                       created_at, created_by_subject_id
                  FROM questionnaire_assignment
                 WHERE tenant_id = ? AND questionnaire_id = ?
                 ORDER BY created_at, assignment_id
                """,
                ROW,
                tenantId.trim(),
                questionnaireId.trim());
    }

    @Override
    public List<QuestionnaireAssignment> list(String tenantId, Collection<String> questionnaireIds) {
        if (blank(tenantId) || questionnaireIds == null || questionnaireIds.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String id : questionnaireIds) {
            if (!blank(id)) {
                ids.add(id.trim());
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(tenantId.trim());
        args.addAll(ids);
        return jdbc.query(
                """
                SELECT assignment_id, tenant_id, questionnaire_id, assignee_kind, assignee_id,
                       created_at, created_by_subject_id
                  FROM questionnaire_assignment
                 WHERE tenant_id = ? AND questionnaire_id IN ("""
                        + placeholders
                        + """
                )
                 ORDER BY questionnaire_id, created_at, assignment_id
                """,
                ROW,
                args.toArray());
    }

    @Override
    public Optional<QuestionnaireAssignment> find(String tenantId, String questionnaireId, String assignmentId) {
        if (blank(tenantId) || blank(questionnaireId) || blank(assignmentId)) {
            return Optional.empty();
        }
        List<QuestionnaireAssignment> rows = jdbc.query(
                """
                SELECT assignment_id, tenant_id, questionnaire_id, assignee_kind, assignee_id,
                       created_at, created_by_subject_id
                  FROM questionnaire_assignment
                 WHERE tenant_id = ? AND questionnaire_id = ? AND assignment_id = ?
                """,
                ROW,
                tenantId.trim(),
                questionnaireId.trim(),
                assignmentId.trim());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<QuestionnaireAssignment> findByAssignee(
            String tenantId,
            String questionnaireId,
            QuestionnaireAssigneeKind kind,
            String assigneeId) {
        if (blank(tenantId) || blank(questionnaireId) || kind == null || blank(assigneeId)) {
            return Optional.empty();
        }
        List<QuestionnaireAssignment> rows = jdbc.query(
                """
                SELECT assignment_id, tenant_id, questionnaire_id, assignee_kind, assignee_id,
                       created_at, created_by_subject_id
                  FROM questionnaire_assignment
                 WHERE tenant_id = ? AND questionnaire_id = ? AND assignee_kind = ? AND assignee_id = ?
                """,
                ROW,
                tenantId.trim(),
                questionnaireId.trim(),
                kind.name(),
                assigneeId.trim());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public QuestionnaireAssignment insert(QuestionnaireAssignment assignment) {
        Objects.requireNonNull(assignment, "assignment");
        jdbc.update(
                """
                INSERT INTO questionnaire_assignment
                  (assignment_id, tenant_id, questionnaire_id, assignee_kind, assignee_id,
                   created_at, created_by_subject_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                assignment.assignmentId(),
                assignment.tenantId(),
                assignment.questionnaireId(),
                assignment.assigneeKind().name(),
                assignment.assigneeId(),
                PlatformTables.timestamp(assignment.createdAt()),
                assignment.createdBySubjectId());
        return assignment;
    }

    @Override
    public boolean delete(String tenantId, String questionnaireId, String assignmentId) {
        if (blank(tenantId) || blank(questionnaireId) || blank(assignmentId)) {
            return false;
        }
        int n = jdbc.update(
                """
                DELETE FROM questionnaire_assignment
                 WHERE tenant_id = ? AND questionnaire_id = ? AND assignment_id = ?
                """,
                tenantId.trim(),
                questionnaireId.trim(),
                assignmentId.trim());
        return n > 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        Timestamp timestamp = row.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
