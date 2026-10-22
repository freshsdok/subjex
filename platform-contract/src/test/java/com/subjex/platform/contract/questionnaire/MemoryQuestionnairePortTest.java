package com.subjex.platform.contract.questionnaire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;


/**
 * MemoryQuestionnairePortTest — Q1–Q5 memory port including stats/CSV.
 * 门禁：内存创建/题目/生命周期与认证答卷。
 */
class MemoryQuestionnairePortTest {

    private static final Instant T0 = Instant.parse("2026-10-22T04:00:00Z");

    @Test
    void createGetListAndTenantIsolation() {
        MemoryQuestionnairePort port = new MemoryQuestionnairePort(Clock.fixed(T0, ZoneOffset.UTC));

        QuestionnaireDefinition a = port.create("tenant-a", "满意度", QuestionnaireCollectMode.AUTH_END_USER);
        assertEquals(QuestionnaireStatus.DRAFT, a.status());
        assertEquals(1, a.revision());
        assertTrue(a.items().isEmpty());
        assertEquals(T0, a.createdAt());

        assertTrue(port.get("tenant-a", a.questionnaireId()).isPresent());
        assertTrue(port.get("tenant-b", a.questionnaireId()).isEmpty());

        port.create("tenant-a", "第二份", QuestionnaireCollectMode.ANON_LINK);
        List<QuestionnaireDefinition> listed = port.list("tenant-a", 10);
        assertEquals(2, listed.size());
        assertTrue(port.list("tenant-b", 10).isEmpty());
    }

    @Test
    void draftReplaceKeepsRevisionPublishCloseAndPublishedBump() {
        MemoryQuestionnairePort port = new MemoryQuestionnairePort(Clock.fixed(T0, ZoneOffset.UTC));
        QuestionnaireDefinition draft =
                port.create("tenant-a", "课程反馈", QuestionnaireCollectMode.AUTH_END_USER);

        QuestionnaireDefinition withItems = port.replaceItems(
                        "tenant-a",
                        draft.questionnaireId(),
                        List.of(
                                item("", 1, QuestionType.SINGLE_CHOICE, "满意度?", true, "[\"好\",\"一般\"]"),
                                item("", 2, QuestionType.TEXT_SHORT, "建议", false, null)))
                .orElseThrow();
        assertEquals(1, withItems.revision());
        assertEquals(2, withItems.items().size());
        assertEquals(QuestionnaireStatus.DRAFT, withItems.status());

        assertThrows(
                IllegalArgumentException.class,
                () -> port.publish("tenant-a", port.create("tenant-a", "空", QuestionnaireCollectMode.ANON_LINK)
                        .questionnaireId()));

        QuestionnaireDefinition published = port.publish("tenant-a", draft.questionnaireId()).orElseThrow();
        assertEquals(QuestionnaireStatus.PUBLISHED, published.status());
        assertEquals(1, published.revision());

        QuestionnaireDefinition bumped = port.replaceItems(
                        "tenant-a",
                        draft.questionnaireId(),
                        List.of(item("stable-1", 1, QuestionType.RATING, "打分", true, "{\"max\":5}")))
                .orElseThrow();
        assertEquals(2, bumped.revision());
        assertEquals(QuestionnaireStatus.PUBLISHED, bumped.status());
        assertEquals(1, bumped.items().size());
        assertEquals("stable-1", bumped.items().get(0).itemId());

        QuestionnaireDefinition closed = port.close("tenant-a", draft.questionnaireId()).orElseThrow();
        assertEquals(QuestionnaireStatus.CLOSED, closed.status());
        assertEquals(2, closed.revision());

        assertThrows(
                IllegalStateException.class,
                () -> port.replaceItems(
                        "tenant-a",
                        draft.questionnaireId(),
                        List.of(item("", 1, QuestionType.TEXT_LONG, "x", false, null))));
        assertThrows(IllegalStateException.class, () -> port.publish("tenant-a", draft.questionnaireId()));
        assertThrows(IllegalStateException.class, () -> port.close("tenant-a", draft.questionnaireId()));
    }

    @Test
    void refusesBlankTenantOrTitle() {
        MemoryQuestionnairePort port = new MemoryQuestionnairePort();
        assertThrows(
                IllegalArgumentException.class,
                () -> port.create(" ", "t", QuestionnaireCollectMode.AUTH_END_USER));
        assertThrows(
                IllegalArgumentException.class,
                () -> port.create("t1", "  ", QuestionnaireCollectMode.AUTH_END_USER));
        assertThrows(NullPointerException.class, () -> port.create("t1", "ok", null));
    }

    @Test
    void clampsListLimit() {
        assertEquals(50, MemoryQuestionnairePort.clampLimit(0));
        assertEquals(50, MemoryQuestionnairePort.clampLimit(-1));
        assertEquals(100, MemoryQuestionnairePort.clampLimit(1000));
    }

    @Test
    void submitEndUserAndOperatorPreviewListing() {
        MemoryQuestionnairePort port = new MemoryQuestionnairePort(Clock.fixed(T0, ZoneOffset.UTC));
        QuestionnaireDefinition draft =
                port.create("tenant-a", "课程反馈", QuestionnaireCollectMode.AUTH_END_USER);
        port.replaceItems(
                "tenant-a",
                draft.questionnaireId(),
                List.of(
                        item("i1", 1, QuestionType.SINGLE_CHOICE, "满意度?", true, "[\"好\",\"差\"]"),
                        item("i2", 2, QuestionType.TEXT_SHORT, "建议", false, null)));
        port.publish("tenant-a", draft.questionnaireId());

        QuestionnaireResponse endUser = port.submit(
                        "tenant-a",
                        draft.questionnaireId(),
                        QuestionnaireRespondentRealm.END_USER,
                        "user-1",
                        List.of(
                                new QuestionnaireAnswer("i1", "\"好\""),
                                new QuestionnaireAnswer("i2", "\"不错\"")))
                .orElseThrow();
        assertTrue(endUser.countsForStats());
        assertEquals(1, endUser.revision());

        QuestionnaireResponse preview = port.submit(
                        "tenant-a",
                        draft.questionnaireId(),
                        QuestionnaireRespondentRealm.OPERATOR,
                        "op-1",
                        List.of(new QuestionnaireAnswer("i1", "\"差\"")))
                .orElseThrow();
        assertTrue(!preview.countsForStats());

        assertEquals(
                1,
                port.listResponses(
                                "tenant-a",
                                draft.questionnaireId(),
                                QuestionnaireResponseListQuery.production(10))
                        .size());
        assertEquals(
                2,
                port.listResponses(
                                "tenant-a",
                                draft.questionnaireId(),
                                QuestionnaireResponseListQuery.includingPreview(10))
                        .size());

        assertThrows(
                IllegalStateException.class,
                () -> port.submit(
                        "tenant-a",
                        draft.questionnaireId(),
                        QuestionnaireRespondentRealm.END_USER,
                        "user-1",
                        List.of(new QuestionnaireAnswer("i1", "\"好\""))));
        assertThrows(
                IllegalArgumentException.class,
                () -> port.submit(
                        "tenant-a",
                        draft.questionnaireId(),
                        QuestionnaireRespondentRealm.ANON,
                        "anon-1",
                        List.of(new QuestionnaireAnswer("i1", "\"好\""))));
    }

    @Test
    void anonCollectTokenSubmitAndRateLimit() {
        CountingLimit limit = new CountingLimit(1);
        MemoryQuestionnairePort port = new MemoryQuestionnairePort(Clock.fixed(T0, ZoneOffset.UTC), limit);
        QuestionnaireDefinition draft =
                port.create("tenant-a", "匿名调研", QuestionnaireCollectMode.ANON_LINK);
        port.replaceItems(
                "tenant-a",
                draft.questionnaireId(),
                List.of(item("i1", 1, QuestionType.SINGLE_CHOICE, "满意度?", true, "[\"好\",\"差\"]")));
        port.publish("tenant-a", draft.questionnaireId());

        QuestionnaireCollectToken token =
                port.activeCollectToken("tenant-a", draft.questionnaireId()).orElseThrow();
        assertTrue(token.active());
        assertTrue(port.getByCollectToken(token.tokenId()).isPresent());

        QuestionnaireResponse anon = port.submitAnon(
                        token.tokenId(),
                        "client-a",
                        List.of(new QuestionnaireAnswer("i1", "\"好\"")))
                .orElseThrow();
        assertEquals(QuestionnaireRespondentRealm.ANON, anon.respondentRealm());
        assertTrue(anon.countsForStats());

        assertThrows(
                QuestionnaireRateLimited.class,
                () -> port.submitAnon(
                        token.tokenId(),
                        "client-a",
                        List.of(new QuestionnaireAnswer("i1", "\"差\""))));

        assertTrue(port.submitAnon(
                        token.tokenId(),
                        "client-b",
                        List.of(new QuestionnaireAnswer("i1", "\"差\"")))
                .isPresent());

        port.revokeCollectToken("tenant-a", token.tokenId());
        assertTrue(port.getByCollectToken(token.tokenId()).isEmpty());
    }

    @Test
    void statsAndCsvExcludeOperatorPreview() {
        MemoryQuestionnairePort port = new MemoryQuestionnairePort(Clock.fixed(T0, ZoneOffset.UTC));
        QuestionnaireDefinition draft =
                port.create("tenant-a", "反馈", QuestionnaireCollectMode.AUTH_END_USER);
        port.replaceItems(
                "tenant-a",
                draft.questionnaireId(),
                List.of(
                        item("i1", 1, QuestionType.SINGLE_CHOICE, "满意度?", true, "[\"好\",\"差\"]"),
                        item("i2", 2, QuestionType.MULTI_CHOICE, "标签", false, "[\"快\",\"稳\"]")));
        port.publish("tenant-a", draft.questionnaireId());

        port.submit(
                "tenant-a",
                draft.questionnaireId(),
                QuestionnaireRespondentRealm.END_USER,
                "user-1",
                List.of(
                        new QuestionnaireAnswer("i1", "\"好\""),
                        new QuestionnaireAnswer("i2", "[\"快\",\"稳\"]")));
        port.submit(
                "tenant-a",
                draft.questionnaireId(),
                QuestionnaireRespondentRealm.OPERATOR,
                "op-1",
                List.of(new QuestionnaireAnswer("i1", "\"差\"")));

        QuestionnaireStats stats = port.stats("tenant-a", draft.questionnaireId()).orElseThrow();
        assertEquals(1, stats.responseCount());
        assertEquals(1L, stats.items().get(0).counts().get("好"));
        assertEquals(0L, stats.items().get(0).counts().get("差")); // preview excluded
        assertEquals(1L, stats.items().get(1).counts().get("快"));
        assertEquals(1L, stats.items().get(1).counts().get("稳"));

        String csv = port.exportCsv("tenant-a", draft.questionnaireId()).orElseThrow();
        assertTrue(csv.contains("END_USER"));
        assertTrue(!csv.contains("OPERATOR"));
        assertTrue(csv.contains("user-1"));
    }

    /** Tiny fixed-budget limiter for Q4 tests — Q4 测试用固定额度限流器. */
    private static final class CountingLimit implements com.subjex.platform.contract.ratelimit.RateLimitPort {
        private final int permits;
        private final java.util.concurrent.ConcurrentHashMap<String, Integer> counts =
                new java.util.concurrent.ConcurrentHashMap<>();

        private CountingLimit(int permits) {
            this.permits = permits;
        }

        @Override
        public boolean permit(String tenantId, String actionName) {
            String key = tenantId + "/" + actionName;
            int next = counts.merge(key, 1, Integer::sum);
            return next <= permits;
        }
    }

    private static QuestionnaireItem item(
            String id, int order, QuestionType type, String prompt, boolean required, String options) {
        return new QuestionnaireItem(id, order, type, prompt, required, options);
    }
}
