package com.subjex.platform.contract.questionnaire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * QuestionnaireStatsSupport — Q5 薄汇总与 CSV（Memory/JDBC 共用）。
 * <p>
 * Callers must pass <strong>production</strong> responses only ({@code countsForStats()==true}).
 * 调用方须只传入生产答卷（预览已过滤）。
 */
public final class QuestionnaireStatsSupport {

    private QuestionnaireStatsSupport() {}

    /**
     * Build thin aggregates for choice/rating items — 为选择/评分题构建薄汇总。
     */
    public static QuestionnaireStats aggregate(
            QuestionnaireDefinition definition, List<QuestionnaireResponse> productionResponses) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(productionResponses, "productionResponses");
        List<QuestionnaireItemStats> itemStats = new ArrayList<>();
        for (QuestionnaireItem item : definition.items()) {
            if (!isAggregatable(item.questionType())) {
                continue;
            }
            Map<String, Long> counts = new LinkedHashMap<>();
            seedChoiceOptions(item, counts);
            for (QuestionnaireResponse response : productionResponses) {
                for (String bucket : answerBuckets(item.questionType(), answerValue(response, item.itemId()))) {
                    counts.merge(bucket, 1L, Long::sum);
                }
            }
            itemStats.add(new QuestionnaireItemStats(
                    item.itemId(), item.questionType(), item.prompt(), Map.copyOf(counts)));
        }
        return new QuestionnaireStats(
                definition.questionnaireId(),
                definition.tenantId(),
                definition.revision(),
                productionResponses.size(),
                List.copyOf(itemStats));
    }

    /**
     * CSV export: header + one row per production response — CSV：表头 + 每行一份生产答卷。
     * <p>
     * Columns: {@code response_id,revision,respondent_realm,respondent_subject_id,submitted_at}
     * then one column per item id (definition order).
     * 列：固定元数据列，再按定义顺序每题一列。
     */
    public static String toCsv(QuestionnaireDefinition definition, List<QuestionnaireResponse> productionResponses) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(productionResponses, "productionResponses");
        StringBuilder sb = new StringBuilder();
        sb.append("response_id,revision,respondent_realm,respondent_subject_id,submitted_at");
        for (QuestionnaireItem item : definition.items()) {
            sb.append(',').append(csvEscape(item.itemId()));
        }
        sb.append('\n');
        for (QuestionnaireResponse response : productionResponses) {
            sb.append(csvEscape(response.responseId()))
                    .append(',')
                    .append(response.revision())
                    .append(',')
                    .append(csvEscape(response.respondentRealm().name()))
                    .append(',')
                    .append(csvEscape(response.respondentSubjectId()))
                    .append(',')
                    .append(csvEscape(response.submittedAt().toString()));
            for (QuestionnaireItem item : definition.items()) {
                sb.append(',').append(csvEscape(answerValue(response, item.itemId())));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static boolean isAggregatable(QuestionType type) {
        return type == QuestionType.SINGLE_CHOICE
                || type == QuestionType.MULTI_CHOICE
                || type == QuestionType.RATING;
    }

    private static void seedChoiceOptions(QuestionnaireItem item, Map<String, Long> counts) {
        if (item.questionType() != QuestionType.SINGLE_CHOICE
                && item.questionType() != QuestionType.MULTI_CHOICE) {
            return;
        }
        for (String option : parseJsonStringList(item.optionsJson())) {
            counts.putIfAbsent(option, 0L);
        }
    }

    private static String answerValue(QuestionnaireResponse response, String itemId) {
        for (QuestionnaireAnswer answer : response.answers()) {
            if (itemId.equals(answer.itemId())) {
                return answer.valueJson() == null ? "" : answer.valueJson();
            }
        }
        return "";
    }

    /**
     * Turn stored value_json into one or more count buckets — 将 value_json 拆成计数桶。
     */
    static List<String> answerBuckets(QuestionType type, String valueJson) {
        if (valueJson == null || valueJson.isBlank()) {
            return List.of();
        }
        String raw = valueJson.trim();
        if (type == QuestionType.MULTI_CHOICE) {
            List<String> options = parseJsonStringList(raw);
            return options.isEmpty() ? List.of(stripQuotes(raw)) : options;
        }
        if (type == QuestionType.SINGLE_CHOICE || type == QuestionType.RATING) {
            if (raw.startsWith("[")) {
                List<String> options = parseJsonStringList(raw);
                return options.isEmpty() ? List.of() : List.of(options.get(0));
            }
            return List.of(stripQuotes(raw));
        }
        return List.of();
    }

    /**
     * Minimal JSON string-array parser ({@code ["a","b"]}) — 最小 JSON 字符串数组解析。
     */
    static List<String> parseJsonStringList(String json) {
        if (json == null) {
            return List.of();
        }
        String s = json.trim();
        if (!s.startsWith("[") || !s.endsWith("]")) {
            return List.of();
        }
        String body = s.substring(1, s.length() - 1).trim();
        if (body.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        boolean escape = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (escape) {
                cur.append(c);
                escape = false;
                continue;
            }
            if (c == '\\' && inQuotes) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }
            if (c == ',' && !inQuotes) {
                String part = cur.toString().trim();
                if (!part.isEmpty()) {
                    out.add(part);
                }
                cur.setLength(0);
                continue;
            }
            if (inQuotes) {
                cur.append(c);
            } else if (!Character.isWhitespace(c)) {
                cur.append(c);
            }
        }
        String last = cur.toString().trim();
        if (!last.isEmpty()) {
            out.add(last);
        }
        return List.copyOf(out);
    }

    static String stripQuotes(String raw) {
        String s = raw.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        boolean needQuotes =
                value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
