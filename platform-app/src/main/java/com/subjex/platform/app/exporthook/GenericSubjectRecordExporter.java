package com.subjex.platform.app.exporthook;

import com.subjex.platform.app.storage.ObjectDescriptor;
import com.subjex.platform.app.storage.ObjectGrantStore;
import com.subjex.platform.contract.consent.ConsentPort;
import com.subjex.platform.contract.exporthook.ExportArtifact;
import com.subjex.platform.contract.exporthook.SubjectRecordExporter;
import com.subjex.platform.contract.exporthook.SensitivityClass;
import com.subjex.platform.contract.inbox.InboxListQuery;
import com.subjex.platform.contract.inbox.InboxPort;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.questionnaire.QuestionnaireDefinition;
import com.subjex.platform.contract.questionnaire.QuestionnairePort;
import com.subjex.platform.contract.questionnaire.QuestionnaireResponse;
import com.subjex.platform.contract.questionnaire.QuestionnaireResponseListQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * GenericSubjectRecordExporter — built-in JSON bundle of the subject's own platform data (WP8-3).
 * No domain exporter. Metadata only for objects (no blob bytes). Sensitive sections are marked.
 * 内置通用 JSON 包：主体本人的平台数据。无领域导出器。对象只含元数据（不含字节）。敏感段已标记。
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
        if (!supports(tenantId, subjectId, scopeDescriptor)) {
            throw new IllegalArgumentException("exporter does not support this tenant/subject/scope");
        }
        Instant now = clock.instant();
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("schema", "platform-personal-data-bundle/v1");
        bundle.put("tenantId", tenantId);
        bundle.put("subjectId", subjectId);
        bundle.put("scope", scopeDescriptor);
        bundle.put("generatedAt", now.toString());
        // Whole artifact is SENSITIVE (ADR 0020); sections also carry markers.
        // 整包按 ADR 0020 标为 SENSITIVE；各段也带标记。
        bundle.put("artifactSensitivity", SensitivityClass.SENSITIVE.name());
        bundle.put("profile", profile(subjectId));
        bundle.put("consents", consents(tenantId, subjectId));
        bundle.put("inbox", inbox(tenantId, subjectId));
        bundle.put("questionnaires", questionnaires(tenantId, subjectId));
        bundle.put("objects", objects(tenantId, subjectId));
        byte[] json;
        try {
            json = mapper.writeValueAsBytes(bundle);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to serialize export bundle", ex);
        }
        return new ExportArtifact(json, CONTENT_TYPE, "export-" + subjectId + ".json");
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

    private Map<String, Object> consents(String tenantId, String subjectId) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("sensitivity", SensitivityClass.SENSITIVE.name());
        if (consents == null) {
            section.put("current", List.of());
            section.put("history", List.of());
            return section;
        }
        section.put("current", consents.listCurrent(tenantId, subjectId));
        section.put("history", consents.history(tenantId, subjectId, null));
        return section;
    }

    private Map<String, Object> inbox(String tenantId, String subjectId) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("sensitivity", SensitivityClass.NORMAL.name());
        List<Map<String, Object>> entries = new ArrayList<>();
        if (inbox != null) {
            inbox.listEntries(
                            tenantId,
                            InboxRecipientRealm.END_USER,
                            subjectId,
                            new InboxListQuery(false, null, InboxListQuery.MAX_LIMIT, 0))
                    .forEach(view -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("entryId", view.entry().entryId());
                        row.put("messageId", view.entry().messageId());
                        row.put("channel", view.message().channel().name());
                        row.put("title", view.message().title());
                        row.put("severity", view.message().severity().name());
                        row.put("readAt", view.entry().readAt() == null ? null : view.entry().readAt().toString());
                        row.put("createdAt", view.entry().createdAt().toString());
                        entries.add(row);
                    });
        }
        section.put("entries", entries);
        return section;
    }

    private Map<String, Object> questionnaires(String tenantId, String subjectId) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("sensitivity", SensitivityClass.SENSITIVE.name());
        List<QuestionnaireResponse> mine = new ArrayList<>();
        if (questionnaires != null) {
            for (QuestionnaireDefinition definition : questionnaires.list(tenantId, 200)) {
                for (QuestionnaireResponse response :
                        questionnaires.listResponses(
                                tenantId, definition.questionnaireId(), QuestionnaireResponseListQuery.production(200))) {
                    if (subjectId.equals(response.respondentSubjectId())) {
                        mine.add(response);
                    }
                }
            }
        }
        section.put("responses", mine);
        return section;
    }

    private Map<String, Object> objects(String tenantId, String subjectId) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("sensitivity", SensitivityClass.NORMAL.name());
        List<Map<String, Object>> descriptors = new ArrayList<>();
        for (ObjectDescriptor descriptor : objects.listOwnedOrSubjectGranted(tenantId, subjectId, 200, 0)) {
            if (!subjectId.equals(descriptor.ownerSubjectId())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("objectId", descriptor.objectId());
            row.put("objectKey", descriptor.objectKey());
            row.put("ownerSubjectId", descriptor.ownerSubjectId());
            row.put("createdAt", descriptor.createdAt() == null ? null : descriptor.createdAt().toString());
            row.put("sensitivity", objects.objectSensitivity(tenantId, descriptor.objectKey()).name());
            descriptors.add(row);
        }
        section.put("descriptors", descriptors);
        return section;
    }
}
