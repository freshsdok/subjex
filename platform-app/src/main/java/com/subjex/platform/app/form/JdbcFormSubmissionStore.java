package com.subjex.platform.app.form;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subjex.platform.app.jdbc.PlatformTables;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcFormSubmissionStore — JDBC 表单提交存放：已接受的提交写在共享库的 {@code form_submission} 表里。
 * <p>
 * Every process on the same database sees the same history after a restart. Values are stored as JSON text.
 * {@code declaration_version} is the form YAML version at submit time.
 * 连同一个库的每个进程在重启后看到同一份历史。取值以 JSON 文本存放。
 * {@code declaration_version} 是提交时的表单 YAML 版本。
 */
public final class JdbcFormSubmissionStore implements FormSubmissionStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ObjectMapper json;

    public JdbcFormSubmissionStore(JdbcTemplate jdbc, Clock clock, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public FormSubmissionRow save(
            String formKey,
            int declarationVersion,
            String actorIdentityId,
            String loginName,
            Map<String, Object> values,
            String resultSummary) {
        Objects.requireNonNull(formKey, "formKey");
        if (declarationVersion < 1) {
            throw new IllegalArgumentException("declarationVersion must be at least 1");
        }
        Objects.requireNonNull(actorIdentityId, "actorIdentityId");
        Objects.requireNonNull(loginName, "loginName");
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(resultSummary, "resultSummary");
        String submissionId = UUID.randomUUID().toString();
        String valuesJson = writeJson(values);
        var submittedAt = clock.instant();
        jdbc.update(
                """
                INSERT INTO form_submission
                  (submission_id, form_key, declaration_version, actor_identity_id, login_name,
                   values_json, result_summary, submitted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                submissionId,
                formKey,
                declarationVersion,
                actorIdentityId,
                loginName,
                valuesJson,
                resultSummary,
                PlatformTables.timestamp(submittedAt));
        return new FormSubmissionRow(
                submissionId,
                formKey,
                declarationVersion,
                actorIdentityId,
                loginName,
                valuesJson,
                resultSummary,
                submittedAt);
    }

    @Override
    public List<FormSubmissionRow> listByFormKey(String formKey, int limit) {
        Objects.requireNonNull(formKey, "formKey");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return jdbc.query(
                """
                SELECT submission_id, form_key, declaration_version, actor_identity_id, login_name,
                       values_json, result_summary, submitted_at
                FROM form_submission
                WHERE form_key = ?
                ORDER BY submitted_at DESC
                LIMIT ?
                """,
                (row, rowNum) -> new FormSubmissionRow(
                        row.getString("submission_id"),
                        row.getString("form_key"),
                        row.getInt("declaration_version"),
                        row.getString("actor_identity_id"),
                        row.getString("login_name"),
                        row.getString("values_json"),
                        row.getString("result_summary"),
                        row.getTimestamp("submitted_at").toInstant()),
                formKey,
                limit);
    }

    private String writeJson(Map<String, Object> values) {
        try {
            return json.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("values cannot be written as JSON", ex);
        }
    }
}
