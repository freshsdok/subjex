package com.subjex.platform.contract.questionnaire;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * QuestionnaireSubmitSupport — 答卷提交校验（Q3 认证 + Q4 匿名共用）。
 * <p>
 * PUBLISHED only. END_USER ↔ {@link QuestionnaireCollectMode#AUTH_END_USER}; ANON ↔
 * {@link QuestionnaireCollectMode#ANON_LINK}; OPERATOR preview on any published mode.
 * 仅已发布；终端/匿名各对采集模式；操作员预览不限模式。
 */
public final class QuestionnaireSubmitSupport {

    private QuestionnaireSubmitSupport() {}

    /**
     * Validate submit against a loaded definition — 按已加载定义校验提交。
     *
     * @return normalized answers / 规范化后的答案
     */
    public static List<QuestionnaireAnswer> validateSubmit(
            QuestionnaireDefinition definition,
            QuestionnaireRespondentRealm realm,
            String respondentSubjectId,
            List<QuestionnaireAnswer> answers) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(realm, "realm");
        Objects.requireNonNull(answers, "answers");
        requireSubject(respondentSubjectId);
        if (definition.status() != QuestionnaireStatus.PUBLISHED) {
            throw new IllegalStateException("submit requires PUBLISHED, was " + definition.status());
        }
        if (realm == QuestionnaireRespondentRealm.END_USER
                && definition.collectMode() != QuestionnaireCollectMode.AUTH_END_USER) {
            throw new IllegalStateException(
                    "END_USER submit requires collectMode AUTH_END_USER, was " + definition.collectMode());
        }
        if (realm == QuestionnaireRespondentRealm.ANON
                && definition.collectMode() != QuestionnaireCollectMode.ANON_LINK) {
            throw new IllegalStateException(
                    "ANON submit requires collectMode ANON_LINK, was " + definition.collectMode());
        }
        // OPERATOR preview allowed on any PUBLISHED collect mode — 操作员预览不限采集模式。
        Map<String, QuestionnaireItem> byId = definition.items().stream()
                .collect(Collectors.toMap(QuestionnaireItem::itemId, item -> item, (a, b) -> a, HashMap::new));
        Set<String> seen = new HashSet<>();
        List<QuestionnaireAnswer> normalized = new ArrayList<>(answers.size());
        for (QuestionnaireAnswer raw : answers) {
            Objects.requireNonNull(raw, "answer");
            if (raw.itemId() == null || raw.itemId().isBlank()) {
                throw new IllegalArgumentException("answer itemId required");
            }
            String itemId = raw.itemId().trim();
            if (!byId.containsKey(itemId)) {
                throw new IllegalArgumentException("unknown itemId=" + itemId + " for revision " + definition.revision());
            }
            if (!seen.add(itemId)) {
                throw new IllegalArgumentException("duplicate answer itemId=" + itemId);
            }
            String value = requireValueJson(raw.valueJson());
            normalized.add(new QuestionnaireAnswer(itemId, value));
        }
        for (QuestionnaireItem item : definition.items()) {
            if (item.required() && !seen.contains(item.itemId())) {
                throw new IllegalArgumentException("missing required itemId=" + item.itemId());
            }
        }
        return List.copyOf(normalized);
    }

    public static String requireSubject(String respondentSubjectId) {
        if (respondentSubjectId == null || respondentSubjectId.isBlank()) {
            throw new IllegalArgumentException("respondentSubjectId required");
        }
        String subject = respondentSubjectId.trim();
        if (subject.length() > 64) {
            throw new IllegalArgumentException("respondentSubjectId too long");
        }
        return subject;
    }

    public static String requireClientKey(String clientKey) {
        if (clientKey == null || clientKey.isBlank()) {
            throw new IllegalArgumentException("clientKey required");
        }
        String trimmed = clientKey.trim();
        if (trimmed.length() > 128) {
            throw new IllegalArgumentException("clientKey too long");
        }
        return trimmed;
    }

    private static String requireValueJson(String valueJson) {
        if (valueJson == null || valueJson.isBlank()) {
            throw new IllegalArgumentException("valueJson required");
        }
        String trimmed = valueJson.trim();
        if (trimmed.length() > 4000) {
            throw new IllegalArgumentException("valueJson too long");
        }
        return trimmed;
    }
}
