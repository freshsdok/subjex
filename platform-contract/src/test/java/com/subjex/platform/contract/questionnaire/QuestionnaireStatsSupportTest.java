package com.subjex.platform.contract.questionnaire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * QuestionnaireStatsSupportTest — Q5 parser + aggregate + CSV gates.
 * 门禁：解析/汇总/CSV。
 */
class QuestionnaireStatsSupportTest {

    @Test
    void parseJsonStringListAndAggregateCsv() {
        assertEquals(List.of("好", "差"), QuestionnaireStatsSupport.parseJsonStringList("[\"好\",\"差\"]"));
        assertEquals("好", QuestionnaireStatsSupport.stripQuotes("\"好\""));

        QuestionnaireDefinition def = new QuestionnaireDefinition(
                "q1",
                "t1",
                "反馈",
                QuestionnaireStatus.PUBLISHED,
                1,
                QuestionnaireCollectMode.AUTH_END_USER,
                List.of(
                        new QuestionnaireItem(
                                "i1", 1, QuestionType.SINGLE_CHOICE, "满意度?", true, "[\"好\",\"差\"]"),
                        new QuestionnaireItem("i2", 2, QuestionType.RATING, "打分", true, "{\"max\":5}"),
                        new QuestionnaireItem("i3", 3, QuestionType.TEXT_SHORT, "建议", false, null)),
                Instant.parse("2026-10-22T04:00:00Z"),
                Instant.parse("2026-10-22T04:00:00Z"));

        List<QuestionnaireResponse> rows = List.of(
                response(
                        "r1",
                        QuestionnaireRespondentRealm.END_USER,
                        "u1",
                        Instant.parse("2026-10-22T04:01:00Z"),
                        List.of(
                                new QuestionnaireAnswer("i1", "\"好\""),
                                new QuestionnaireAnswer("i2", "5"),
                                new QuestionnaireAnswer("i3", "\"ok\""))),
                response(
                        "r2",
                        QuestionnaireRespondentRealm.ANON,
                        "a1",
                        Instant.parse("2026-10-22T04:02:00Z"),
                        List.of(
                                new QuestionnaireAnswer("i1", "\"差\""),
                                new QuestionnaireAnswer("i2", "\"3\""))));

        QuestionnaireStats stats = QuestionnaireStatsSupport.aggregate(def, rows);
        assertEquals(2, stats.responseCount());
        assertEquals(2, stats.items().size()); // text skipped
        QuestionnaireItemStats choice = stats.items().get(0);
        assertEquals(1L, choice.counts().get("好"));
        assertEquals(1L, choice.counts().get("差"));
        assertEquals(1L, stats.items().get(1).counts().get("5"));
        assertEquals(1L, stats.items().get(1).counts().get("3"));

        String csv = QuestionnaireStatsSupport.toCsv(def, rows);
        assertTrue(csv.startsWith("response_id,revision,respondent_realm,respondent_subject_id,submitted_at,i1,i2,i3\n"));
        assertTrue(csv.contains("r1,1,END_USER,u1,"));
        assertTrue(csv.contains("\"好\""));
    }

    private static QuestionnaireResponse response(
            String id,
            QuestionnaireRespondentRealm realm,
            String subject,
            Instant at,
            List<QuestionnaireAnswer> answers) {
        return new QuestionnaireResponse(id, "q1", 1, "t1", realm, subject, at, answers);
    }
}
