package com.subjex.platform.contract.questionnaire;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * QuestionnaireItemSupport — 题目列表规范化与校验（Q2 共用）。
 * <p>
 * Blank {@code itemId} → new UUID. Choice types need non-blank {@code optionsJson}.
 * 空 itemId 生成 UUID；选择题须非空 optionsJson。
 */
public final class QuestionnaireItemSupport {

    private QuestionnaireItemSupport() {}

    /**
     * Normalize and validate a full item list — 规范化并校验完整题目列表。
     *
     * @throws IllegalArgumentException if invalid / 非法则抛
     */
    public static List<QuestionnaireItem> normalize(List<QuestionnaireItem> items) {
        Objects.requireNonNull(items, "items");
        List<QuestionnaireItem> out = new ArrayList<>(items.size());
        Set<String> ids = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (QuestionnaireItem raw : items) {
            Objects.requireNonNull(raw, "item");
            Objects.requireNonNull(raw.questionType(), "questionType");
            String prompt = requirePrompt(raw.prompt());
            String itemId = raw.itemId() == null || raw.itemId().isBlank()
                    ? UUID.randomUUID().toString()
                    : raw.itemId().trim();
            if (!ids.add(itemId)) {
                throw new IllegalArgumentException("duplicate itemId=" + itemId);
            }
            if (!orders.add(raw.sortOrder())) {
                throw new IllegalArgumentException("duplicate sortOrder=" + raw.sortOrder());
            }
            String options = normalizeOptions(raw.questionType(), raw.optionsJson());
            out.add(new QuestionnaireItem(
                    itemId, raw.sortOrder(), raw.questionType(), prompt, raw.required(), options));
        }
        out.sort((a, b) -> Integer.compare(a.sortOrder(), b.sortOrder()));
        return List.copyOf(out);
    }

    private static String requirePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt required");
        }
        String trimmed = prompt.trim();
        if (trimmed.length() > 2000) {
            throw new IllegalArgumentException("prompt too long");
        }
        return trimmed;
    }

    private static String normalizeOptions(QuestionType type, String optionsJson) {
        String trimmed = optionsJson == null ? null : optionsJson.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        if (trimmed != null && trimmed.length() > 4000) {
            throw new IllegalArgumentException("optionsJson too long");
        }
        if (type == QuestionType.SINGLE_CHOICE || type == QuestionType.MULTI_CHOICE) {
            if (trimmed == null) {
                throw new IllegalArgumentException("optionsJson required for " + type);
            }
        }
        return trimmed;
    }
}
