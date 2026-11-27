package com.subjex.platform.app.exporthook;

import com.subjex.platform.app.storage.ObjectGrantStore;
import com.subjex.platform.app.support.TenantKeyset;
import com.subjex.platform.contract.consent.ConsentPort;
import com.subjex.platform.contract.exporthook.ExportArtifact;
import com.subjex.platform.contract.exporthook.SubjectRecordExporter;
import com.subjex.platform.contract.exporthook.SensitivityClass;
import com.subjex.platform.contract.inbox.InboxPort;
import com.subjex.platform.contract.questionnaire.QuestionnairePort;
import com.subjex.platform.contract.questionnaire.QuestionnaireRespondentRealm;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ObjectMapper;

/**
 * GenericSubjectRecordExporter — built-in JSON bundle of the subject's own platform data.
 * Pages inbox, questionnaire responses, owned objects, and consent history with a tenant-scoped
 * keyset and streams them; it does not assemble the whole package in a single map. Current
 * consents stay a short purpose list. No domain exporter. Object metadata only (no blob bytes).
 * Sensitive sections are marked.
 * 内置通用 JSON 包：主体本人的平台数据。站内信、答卷、自有对象与同意历史按租户键集分页并流式写出，
 * 不把整包装进一张大图。当前同意仍按目的短列表写出。无领域导出器。对象只含元数据（不含字节）。敏感段已标记。
 */
public final class GenericSubjectRecordExporter implements SubjectRecordExporter {

    public static final String CONTENT_TYPE = "application/json";

    private final JdbcTemplate jdbc;
    private final ConsentPort consents;
    private final InboxPort inbox;
    private final QuestionnairePort questionnaires;
    private final ObjectGrantStore objects;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public GenericSubjectRecordExporter(
            JdbcTemplate jdbc,
            ConsentPort consents,
            InboxPort inbox,
            QuestionnairePort questionnaires,
            ObjectGrantStore objects,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.consents = consents;
        this.inbox = inbox;
        this.questionnaires = questionnaires;
        this.objects = Objects.requireNonNull(objects, "objects");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public boolean supports(String tenantId, String subjectId, String scopeDescriptor) {
        return tenantId != null
                && !tenantId.isBlank()
                && subjectId != null
                && !subjectId.isBlank()
                && scopeDescriptor != null
                && !scopeDescriptor.isBlank();
    }

    @Override
    public ExportArtifact export(String tenantId, String subjectId, String scopeDescriptor) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            exportTo(tenantId, subjectId, scopeDescriptor, buffer);
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to serialize export bundle", ex);
        }
        return new ExportArtifact(buffer.toByteArray(), CONTENT_TYPE, "export-" + subjectId + ".json");
    }

    @Override
    public void exportTo(String tenantId, String subjectId, String scopeDescriptor, OutputStream output)
            throws IOException {
        if (!supports(tenantId, subjectId, scopeDescriptor)) {
            throw new IllegalArgumentException("exporter does not support this tenant/subject/scope");
        }
        Objects.requireNonNull(output, "output");
        Instant now = clock.instant();
        try (JsonGenerator json = mapper.createGenerator(output)) {
            json.writeStartObject();
            json.writeStringProperty("schema", "platform-personal-data-bundle/v1");
            json.writeStringProperty("tenantId", tenantId);
            json.writeStringProperty("subjectId", subjectId);
            json.writeStringProperty("scope", scopeDescriptor);
            json.writeStringProperty("generatedAt", now.toString());
            // Whole artifact is SENSITIVE (ADR 0020); sections also carry markers.
            // 整包按 ADR 0020 标为 SENSITIVE；各段也带标记。
            json.writeStringProperty("artifactSensitivity", SensitivityClass.SENSITIVE.name());
            json.writePOJOProperty("profile", profile(subjectId));
            writeConsents(json, tenantId, subjectId);
            writeInbox(json, tenantId, subjectId);
            writeQuestionnaires(json, tenantId, subjectId);
            writeObjects(json, tenantId, subjectId);
            json.writeEndObject();
            json.flush();
        }
    }

    private Map<String, Object> profile(String subjectId) {
        List<String> names = jdbc.query(
                "SELECT subject_name FROM subject WHERE subject_id = ?",
                (row, n) -> row.getString("subject_name"),
                subjectId);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("subjectName", names.isEmpty() ? null : names.get(0));
        profile.put("sensitivity", SensitivityClass.NORMAL.name());
        return profile;
    }

    private void writeConsents(JsonGenerator json, String tenantId, String subjectId) throws IOException {
        json.writeObjectPropertyStart("consents");
        json.writeStringProperty("sensitivity", SensitivityClass.SENSITIVE.name());
        json.writePOJOProperty("current", consents == null ? List.of() : consents.listCurrent(tenantId, subjectId));
        json.writeArrayPropertyStart("history");
        Instant afterTime = Instant.EPOCH;
        String afterId = "";
        int pageSize = TenantKeyset.EXPORT_PAGE_LIMIT;
        while (true) {
            List<ConsentHistoryRow> page = jdbc.query(
                    """
                    SELECT event_id, tenant_id, subject_id, purpose_key, consent_version_id,
                           event_kind, actor_identity_id, occurred_at
                    FROM consent_event
                    WHERE tenant_id = ? AND subject_id = ?
                      AND (occurred_at > ? OR (occurred_at = ? AND event_id > ?))
                    ORDER BY occurred_at ASC, event_id ASC
                    LIMIT ?
                    """,
                    (row, n) -> new ConsentHistoryRow(
                            row.getString("event_id"),
                            row.getString("tenant_id"),
                            row.getString("subject_id"),
                            row.getString("purpose_key"),
                            row.getString("consent_version_id"),
                            row.getString("event_kind"),
                            row.getString("actor_identity_id"),
                            instant(row.getTimestamp("occurred_at"))),
                    tenantId,
                    subjectId,
                    Timestamp.from(afterTime),
                    Timestamp.from(afterTime),
                    afterId,
                    pageSize);
            for (ConsentHistoryRow event : page) {
                json.writeStartObject();
                json.writeStringProperty("eventId", event.eventId);
                json.writeStringProperty("tenantId", event.tenantId);
                json.writeStringProperty("subjectId", event.subjectId);
                json.writeStringProperty("purposeKey", event.purposeKey);
                json.writeStringProperty("consentVersionId", event.consentVersionId);
                json.writeStringProperty("eventKind", event.eventKind);
                json.writeStringProperty("actorIdentityId", event.actorIdentityId);
                if (event.occurredAt == null) {
                    json.writeNullProperty("occurredAt");
                } else {
                    json.writeStringProperty("occurredAt", event.occurredAt.toString());
                }
                json.writeEndObject();
                afterTime = event.occurredAt == null ? afterTime : event.occurredAt;
                afterId = event.eventId;
            }
            if (page.size() < pageSize) {
                break;
            }
        }
        json.writeEndArray();
        json.writeEndObject();
    }

    private void writeInbox(JsonGenerator json, String tenantId, String subjectId) throws IOException {
        json.writeObjectPropertyStart("inbox");
        json.writeStringProperty("sensitivity", SensitivityClass.NORMAL.name());
        json.writeArrayPropertyStart("entries");
        Instant afterTime = Instant.EPOCH;
        String afterId = "";
        int pageSize = TenantKeyset.EXPORT_PAGE_LIMIT;
        while (true) {
            List<InboxRow> page = jdbc.query(
                    """
                    SELECT entry_id, message_id, read_at, created_at
                    FROM inbox_entry
                    WHERE tenant_id = ? AND recipient_realm = 'END_USER' AND recipient_subject_id = ?
                      AND (created_at > ? OR (created_at = ? AND entry_id > ?))
                    ORDER BY created_at ASC, entry_id ASC
                    LIMIT ?
                    """,
                    (row, n) -> new InboxRow(
                            row.getString("entry_id"),
                            row.getString("message_id"),
                            instant(row.getTimestamp("read_at")),
                            instant(row.getTimestamp("created_at"))),
                    tenantId,
                    subjectId,
                    Timestamp.from(afterTime),
                    Timestamp.from(afterTime),
                    afterId,
                    pageSize);
            for (InboxRow entry : page) {
                Map<String, Object> meta = messageMeta(entry.messageId);
                json.writeStartObject();
                json.writeStringProperty("entryId", entry.entryId);
                json.writeStringProperty("messageId", entry.messageId);
                json.writeStringProperty("channel", stringOrNull(meta.get("channel")));
                json.writeStringProperty("title", stringOrNull(meta.get("title")));
                json.writeStringProperty("severity", stringOrNull(meta.get("severity")));
                if (entry.readAt == null) {
                    json.writeNullProperty("readAt");
                } else {
                    json.writeStringProperty("readAt", entry.readAt.toString());
                }
                json.writeStringProperty("createdAt", entry.createdAt.toString());
                json.writeEndObject();
                afterTime = entry.createdAt;
                afterId = entry.entryId;
            }
            if (page.size() < pageSize) {
                break;
            }
        }
        json.writeEndArray();
        json.writeEndObject();
    }

    private Map<String, Object> messageMeta(String messageId) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT channel, title, severity FROM inbox_message WHERE message_id = ?",
                (row, n) -> {
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("channel", row.getString("channel"));
                    meta.put("title", row.getString("title"));
                    meta.put("severity", row.getString("severity"));
                    return meta;
                },
                messageId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private void writeQuestionnaires(JsonGenerator json, String tenantId, String subjectId) throws IOException {
        json.writeObjectPropertyStart("questionnaires");
        json.writeStringProperty("sensitivity", SensitivityClass.SENSITIVE.name());
        json.writeArrayPropertyStart("responses");
        Instant afterTime = Instant.EPOCH;
        String afterId = "";
        int pageSize = TenantKeyset.EXPORT_PAGE_LIMIT;
        while (true) {
            List<ResponseRow> page = jdbc.query(
                    """
                    SELECT response_id, questionnaire_id, revision, tenant_id,
                           respondent_realm, respondent_subject_id, submitted_at
                    FROM questionnaire_response
                    WHERE tenant_id = ? AND respondent_subject_id = ? AND respondent_realm <> ?
                      AND (submitted_at > ? OR (submitted_at = ? AND response_id > ?))
                    ORDER BY submitted_at ASC, response_id ASC
                    LIMIT ?
                    """,
                    (row, n) -> new ResponseRow(
                            row.getString("response_id"),
                            row.getString("questionnaire_id"),
                            row.getInt("revision"),
                            row.getString("tenant_id"),
                            row.getString("respondent_realm"),
                            row.getString("respondent_subject_id"),
                            instant(row.getTimestamp("submitted_at"))),
                    tenantId,
                    subjectId,
                    QuestionnaireRespondentRealm.OPERATOR.name(),
                    Timestamp.from(afterTime),
                    Timestamp.from(afterTime),
                    afterId,
                    pageSize);
            for (ResponseRow response : page) {
                List<Map<String, Object>> answers = jdbc.query(
                        """
                        SELECT item_id, value_json
                        FROM questionnaire_answer
                        WHERE response_id = ?
                        ORDER BY item_id ASC
                        """,
                        (row, n) -> {
                            Map<String, Object> answer = new LinkedHashMap<>();
                            answer.put("itemId", row.getString("item_id"));
                            answer.put("valueJson", row.getString("value_json"));
                            return answer;
                        },
                        response.responseId);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("responseId", response.responseId);
                body.put("questionnaireId", response.questionnaireId);
                body.put("revision", response.revision);
                body.put("tenantId", response.tenantId);
                body.put("respondentRealm", response.respondentRealm);
                body.put("respondentSubjectId", response.respondentSubjectId);
                body.put("submittedAt", response.submittedAt == null ? null : response.submittedAt.toString());
                body.put("answers", answers);
                json.writePOJO(body);
                afterTime = response.submittedAt;
                afterId = response.responseId;
            }
            if (page.size() < pageSize) {
                break;
            }
        }
        json.writeEndArray();
        json.writeEndObject();
    }

    private void writeObjects(JsonGenerator json, String tenantId, String subjectId) throws IOException {
        json.writeObjectPropertyStart("objects");
        json.writeStringProperty("sensitivity", SensitivityClass.NORMAL.name());
        json.writeArrayPropertyStart("descriptors");
        Instant afterTime = Instant.EPOCH;
        String afterId = "";
        int pageSize = TenantKeyset.EXPORT_PAGE_LIMIT;
        while (true) {
            List<ObjectRow> page = jdbc.query(
                    """
                    SELECT object_id, object_key, owner_subject_id, created_at
                    FROM object_descriptor
                    WHERE tenant_id = ? AND owner_subject_id = ?
                      AND (created_at > ? OR (created_at = ? AND object_id > ?))
                    ORDER BY created_at ASC, object_id ASC
                    LIMIT ?
                    """,
                    (row, n) -> new ObjectRow(
                            row.getString("object_id"),
                            row.getString("object_key"),
                            row.getString("owner_subject_id"),
                            instant(row.getTimestamp("created_at"))),
                    tenantId,
                    subjectId,
                    Timestamp.from(afterTime),
                    Timestamp.from(afterTime),
                    afterId,
                    pageSize);
            for (ObjectRow descriptor : page) {
                json.writeStartObject();
                json.writeStringProperty("objectId", descriptor.objectId);
                json.writeStringProperty("objectKey", descriptor.objectKey);
                json.writeStringProperty("ownerSubjectId", descriptor.ownerSubjectId);
                if (descriptor.createdAt == null) {
                    json.writeNullProperty("createdAt");
                } else {
                    json.writeStringProperty("createdAt", descriptor.createdAt.toString());
                }
                json.writeStringProperty(
                        "sensitivity", objects.objectSensitivity(tenantId, descriptor.objectKey).name());
                json.writeEndObject();
                afterTime = descriptor.createdAt == null ? afterTime : descriptor.createdAt;
                afterId = descriptor.objectId;
            }
            if (page.size() < pageSize) {
                break;
            }
        }
        json.writeEndArray();
        json.writeEndObject();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record ConsentHistoryRow(
            String eventId,
            String tenantId,
            String subjectId,
            String purposeKey,
            String consentVersionId,
            String eventKind,
            String actorIdentityId,
            Instant occurredAt) {}

    private record InboxRow(String entryId, String messageId, Instant readAt, Instant createdAt) {}

    private record ResponseRow(
            String responseId,
            String questionnaireId,
            int revision,
            String tenantId,
            String respondentRealm,
            String respondentSubjectId,
            Instant submittedAt) {}

    private record ObjectRow(String objectId, String objectKey, String ownerSubjectId, Instant createdAt) {}
}
