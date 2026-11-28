package com.subjex.platform.app.questionnaire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.questionnaire.QuestionType;
import com.subjex.platform.app.ratelimit.SingleProcessRateLimit;
import com.subjex.platform.contract.questionnaire.QuestionnaireAnswer;
import com.subjex.platform.contract.questionnaire.QuestionnaireCollectToken;
import com.subjex.platform.contract.questionnaire.QuestionnaireRateLimited;
import com.subjex.platform.contract.ratelimit.RateLimitPort;
import com.subjex.platform.contract.questionnaire.QuestionnaireCollectMode;
import com.subjex.platform.contract.questionnaire.QuestionnaireDefinition;
import com.subjex.platform.contract.questionnaire.QuestionnaireItem;
import com.subjex.platform.contract.questionnaire.QuestionnaireRespondentRealm;
import com.subjex.platform.contract.questionnaire.QuestionnaireResponse;
import com.subjex.platform.contract.questionnaire.QuestionnaireStats;
import com.subjex.platform.contract.questionnaire.QuestionnaireStatsSupport;
import com.subjex.platform.contract.questionnaire.QuestionnaireResponseListQuery;
import com.subjex.platform.app.support.TenantKeyset;
import com.subjex.platform.contract.questionnaire.QuestionnaireStatus;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcQuestionnairePortTest — Q1–Q5 Flyway schema + stats/CSV on dual H2.
 * 门禁：双模式 H2 上生命周期与认证答卷；预览不进生产列表。
 */
class JdbcQuestionnairePortTest {

    private static final String TENANT_A = "tenant-q-a";
    private static final String TENANT_B = "tenant-q-b";
    private static final Instant T0 = Instant.parse("2026-10-22T04:00:00Z");

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void createGetListTenantIsolation(H2PlatformTables.Mode mode) {
        JdbcQuestionnairePort port = newPort(mode);

        QuestionnaireDefinition created =
                port.create(TENANT_A, "课程反馈", QuestionnaireCollectMode.AUTH_END_USER);
        assertThat(created.questionnaireId()).isNotBlank();
        assertThat(created.status()).isEqualTo(QuestionnaireStatus.DRAFT);
        assertThat(created.revision()).isEqualTo(1);
        assertThat(created.collectMode()).isEqualTo(QuestionnaireCollectMode.AUTH_END_USER);
        assertThat(created.items()).isEmpty();
        assertThat(created.createdAt()).isEqualTo(T0);

        assertThat(port.get(TENANT_A, created.questionnaireId())).isPresent();
        assertThat(port.get(TENANT_B, created.questionnaireId())).isEmpty();

        port.create(TENANT_A, "匿名预研", QuestionnaireCollectMode.ANON_LINK);
        assertThat(port.list(TENANT_A, 10)).hasSize(2);
        assertThat(port.list(TENANT_B, 10)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void draftReplacePublishBumpKeepsPriorRevisionRows(H2PlatformTables.Mode mode) {
        DataSource ds = H2PlatformTables.migrated(mode);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ensureTenant(jdbc, TENANT_A);
        JdbcQuestionnairePort port = new JdbcQuestionnairePort(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(ds)),
                Clock.fixed(T0, ZoneOffset.UTC),
                alwaysPermit());

        QuestionnaireDefinition draft = port.create(TENANT_A, "反馈", QuestionnaireCollectMode.AUTH_END_USER);
        QuestionnaireDefinition withItems = port.replaceItems(
                        TENANT_A,
                        draft.questionnaireId(),
                        List.of(
                                item("i1", 1, QuestionType.SINGLE_CHOICE, "满意度?", true, "[\"好\",\"差\"]"),
                                item("i2", 2, QuestionType.TEXT_SHORT, "建议", false, null)))
                .orElseThrow();
        assertThat(withItems.revision()).isEqualTo(1);
        assertThat(withItems.items()).hasSize(2);

        assertThatThrownBy(() -> port.publish(
                        TENANT_A, port.create(TENANT_A, "空卷", QuestionnaireCollectMode.ANON_LINK).questionnaireId()))
                .isInstanceOf(IllegalArgumentException.class);

        QuestionnaireDefinition published = port.publish(TENANT_A, draft.questionnaireId()).orElseThrow();
        assertThat(published.status()).isEqualTo(QuestionnaireStatus.PUBLISHED);
        assertThat(published.revision()).isEqualTo(1);

        QuestionnaireDefinition bumped = port.replaceItems(
                        TENANT_A,
                        draft.questionnaireId(),
                        List.of(item("i1", 1, QuestionType.RATING, "打分", true, "{\"max\":5}")))
                .orElseThrow();
        assertThat(bumped.revision()).isEqualTo(2);
        assertThat(bumped.items()).hasSize(1);
        assertThat(bumped.items().get(0).questionType()).isEqualTo(QuestionType.RATING);

        Integer rev1Count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM questionnaire_item WHERE questionnaire_id = ? AND revision = 1",
                Integer.class,
                draft.questionnaireId());
        Integer rev2Count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM questionnaire_item WHERE questionnaire_id = ? AND revision = 2",
                Integer.class,
                draft.questionnaireId());
        assertThat(rev1Count).isEqualTo(2);
        assertThat(rev2Count).isEqualTo(1);

        QuestionnaireDefinition closed = port.close(TENANT_A, draft.questionnaireId()).orElseThrow();
        assertThat(closed.status()).isEqualTo(QuestionnaireStatus.CLOSED);

        assertThatThrownBy(() -> port.replaceItems(
                        TENANT_A,
                        draft.questionnaireId(),
                        List.of(item("x", 1, QuestionType.TEXT_LONG, "no", false, null))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> port.publish(TENANT_A, draft.questionnaireId()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> port.close(TENANT_A, draft.questionnaireId()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(port.replaceItems(TENANT_B, draft.questionnaireId(), List.of())).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void refusesBlankTenantOrTitle(H2PlatformTables.Mode mode) {
        JdbcQuestionnairePort port = newPort(mode);
        assertThatThrownBy(() -> port.create(" ", "t", QuestionnaireCollectMode.AUTH_END_USER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> port.create(TENANT_A, "\t", QuestionnaireCollectMode.ANON_LINK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void permissionsSeeded(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        Integer read = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_permission WHERE permission_name = 'questionnaire.read'",
                Integer.class);
        Integer write = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_permission WHERE permission_name = 'questionnaire.write'",
                Integer.class);
        assertThat(read).isEqualTo(1);
        assertThat(write).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void submitEndUserAndOperatorPreviewListing(H2PlatformTables.Mode mode) {
        JdbcQuestionnairePort port = newPort(mode);
        QuestionnaireDefinition draft = port.create(TENANT_A, "反馈", QuestionnaireCollectMode.AUTH_END_USER);
        port.replaceItems(
                TENANT_A,
                draft.questionnaireId(),
                List.of(
                        item("i1", 1, QuestionType.SINGLE_CHOICE, "满意度?", true, "[\"好\",\"差\"]"),
                        item("i2", 2, QuestionType.TEXT_SHORT, "建议", false, null)));
        port.publish(TENANT_A, draft.questionnaireId());

        QuestionnaireResponse endUser = port.submit(
                        TENANT_A,
                        draft.questionnaireId(),
                        QuestionnaireRespondentRealm.END_USER,
                        "user-1",
                        List.of(
                                new QuestionnaireAnswer("i1", "\"好\""),
                                new QuestionnaireAnswer("i2", "\"ok\"")))
                .orElseThrow();
        assertThat(endUser.countsForStats()).isTrue();
        assertThat(endUser.answers()).hasSize(2);

        QuestionnaireResponse preview = port.submit(
                        TENANT_A,
                        draft.questionnaireId(),
                        QuestionnaireRespondentRealm.OPERATOR,
                        "op-1",
                        List.of(new QuestionnaireAnswer("i1", "\"差\"")))
                .orElseThrow();
        assertThat(preview.countsForStats()).isFalse();

        assertThat(port.listResponses(
                        TENANT_A, draft.questionnaireId(), QuestionnaireResponseListQuery.production(10)))
                .hasSize(1)
                .first()
                .extracting(QuestionnaireResponse::respondentRealm)
                .isEqualTo(QuestionnaireRespondentRealm.END_USER);
        assertThat(port.listResponses(
                        TENANT_A, draft.questionnaireId(), QuestionnaireResponseListQuery.includingPreview(10)))
                .hasSize(2);

        assertThatThrownBy(() -> port.submit(
                        TENANT_A,
                        draft.questionnaireId(),
                        QuestionnaireRespondentRealm.END_USER,
                        "user-1",
                        List.of(new QuestionnaireAnswer("i1", "\"好\""))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> port.submit(
                        TENANT_A,
                        draft.questionnaireId(),
                        QuestionnaireRespondentRealm.ANON,
                        "anon-1",
                        List.of(new QuestionnaireAnswer("i1", "\"好\""))))
                .isInstanceOf(IllegalArgumentException.class);

        // CLOSED rejects submit — 关闭后拒提交。
        port.close(TENANT_A, draft.questionnaireId());
        assertThatThrownBy(() -> port.submit(
                        TENANT_A,
                        draft.questionnaireId(),
                        QuestionnaireRespondentRealm.END_USER,
                        "user-2",
                        List.of(new QuestionnaireAnswer("i1", "\"好\""))))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void anonCollectTokenSubmitAndRateLimit(H2PlatformTables.Mode mode) {
        DataSource ds = H2PlatformTables.migrated(mode);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ensureTenant(jdbc, TENANT_A);
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        RateLimitPort tight = new SingleProcessRateLimit(1, Duration.ofMinutes(1), clock);
        JdbcQuestionnairePort port = new JdbcQuestionnairePort(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), clock, tight);

        QuestionnaireDefinition draft =
                port.create(TENANT_A, "匿名调研", QuestionnaireCollectMode.ANON_LINK);
        port.replaceItems(
                TENANT_A,
                draft.questionnaireId(),
                List.of(item("i1", 1, QuestionType.SINGLE_CHOICE, "满意度?", true, "[\"好\",\"差\"]")));
        port.publish(TENANT_A, draft.questionnaireId());

        QuestionnaireCollectToken token =
                port.activeCollectToken(TENANT_A, draft.questionnaireId()).orElseThrow();
        assertThat(token.active()).isTrue();
        assertThat(port.getByCollectToken(token.tokenId())).isPresent();

        QuestionnaireResponse anon = port.submitAnon(
                        token.tokenId(),
                        "client-a",
                        List.of(new QuestionnaireAnswer("i1", "\"好\"")))
                .orElseThrow();
        assertThat(anon.respondentRealm()).isEqualTo(QuestionnaireRespondentRealm.ANON);
        assertThat(anon.countsForStats()).isTrue();

        assertThatThrownBy(() -> port.submitAnon(
                        token.tokenId(),
                        "client-a",
                        List.of(new QuestionnaireAnswer("i1", "\"差\""))))
                .isInstanceOf(QuestionnaireRateLimited.class);

        // Different client key has its own budget — 不同客户端键各自限额。
        assertThat(port.submitAnon(
                        token.tokenId(),
                        "client-b",
                        List.of(new QuestionnaireAnswer("i1", "\"差\""))))
                .isPresent();

        port.revokeCollectToken(TENANT_A, token.tokenId());
        assertThat(port.getByCollectToken(token.tokenId())).isEmpty();
        assertThat(port.submitAnon(
                        token.tokenId(),
                        "client-c",
                        List.of(new QuestionnaireAnswer("i1", "\"好\""))))
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void statsAndCsvExcludeOperatorPreview(H2PlatformTables.Mode mode) {
        JdbcQuestionnairePort port = newPort(mode);
        QuestionnaireDefinition draft =
                port.create(TENANT_A, "反馈", QuestionnaireCollectMode.AUTH_END_USER);
        port.replaceItems(
                TENANT_A,
                draft.questionnaireId(),
                List.of(
                        item("i1", 1, QuestionType.SINGLE_CHOICE, "满意度?", true, "[\"好\",\"差\"]"),
                        item("i2", 2, QuestionType.RATING, "打分", true, "{\"max\":5}")));
        port.publish(TENANT_A, draft.questionnaireId());

        port.submit(
                TENANT_A,
                draft.questionnaireId(),
                QuestionnaireRespondentRealm.END_USER,
                "user-1",
                List.of(
                        new QuestionnaireAnswer("i1", "\"好\""),
                        new QuestionnaireAnswer("i2", "4")));
        port.submit(
                TENANT_A,
                draft.questionnaireId(),
                QuestionnaireRespondentRealm.OPERATOR,
                "op-1",
                List.of(
                        new QuestionnaireAnswer("i1", "\"差\""),
                        new QuestionnaireAnswer("i2", "1")));

        QuestionnaireStats stats = port.stats(TENANT_A, draft.questionnaireId()).orElseThrow();
        assertThat(stats.responseCount()).isEqualTo(1);
        assertThat(stats.items().get(0).counts().get("好")).isEqualTo(1L);
        assertThat(stats.items().get(0).counts().get("差")).isEqualTo(0L);
        assertThat(stats.items().get(1).counts().get("4")).isEqualTo(1L);
        assertThat(stats.items().get(1).counts().getOrDefault("1", 0L)).isEqualTo(0L);

        String csv = port.exportCsv(TENANT_A, draft.questionnaireId()).orElseThrow();
        assertThat(csv).contains("END_USER").doesNotContain("OPERATOR").contains("user-1");
        assertThat(csv.lines().findFirst().orElseThrow())
                .isEqualTo("response_id,revision,respondent_realm,respondent_subject_id,submitted_at,i1,i2");
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void writeCsvPagesWithoutMaterializingWholeString(H2PlatformTables.Mode mode) throws IOException {
        JdbcQuestionnairePort port = newPort(mode);
        QuestionnaireDefinition draft = port.create(TENANT_A, "导出分页", QuestionnaireCollectMode.AUTH_END_USER);
        port.replaceItems(
                TENANT_A,
                draft.questionnaireId(),
                List.of(item("i1", 1, QuestionType.TEXT_SHORT, "建议", false, null)));
        port.publish(TENANT_A, draft.questionnaireId());

        int production = TenantKeyset.EXPORT_PAGE_LIMIT + 10;
        for (int i = 0; i < production; i++) {
            port.submit(
                    TENANT_A,
                    draft.questionnaireId(),
                    QuestionnaireRespondentRealm.END_USER,
                    "user-" + i,
                    List.of(new QuestionnaireAnswer("i1", "\"v" + i + "\"")));
        }
        port.submit(
                TENANT_A,
                draft.questionnaireId(),
                QuestionnaireRespondentRealm.OPERATOR,
                "op-preview",
                List.of(new QuestionnaireAnswer("i1", "\"hidden\"")));

        ChunkTrackingAppendable out = new ChunkTrackingAppendable();
        assertThat(port.writeCsv(TENANT_A, draft.questionnaireId(), out)).isTrue();
        String csv = out.text();
        assertThat(csv.lines().findFirst().orElseThrow())
                .isEqualTo("response_id,revision,respondent_realm,respondent_subject_id,submitted_at,i1");
        assertThat(csv.lines().count()).isEqualTo(production + 1L);
        assertThat(csv).contains("user-0").contains("user-" + (production - 1)).doesNotContain("OPERATOR");
        assertThat(csv).doesNotContain("op-preview").doesNotContain("hidden");
        // A leftover toCsv-style write would append the whole file in one chunk.
        // 若仍走整段 toCsv，会一次写入整个文件。
        assertThat(out.maxChunk()).isGreaterThan(0);
        assertThat(out.maxChunk()).isLessThan(out.total());
        assertThat(out.maxChunk()).isLessThan(csv.length() / 2);

        ChunkTrackingAppendable missing = new ChunkTrackingAppendable();
        assertThat(port.writeCsv(TENANT_A, "no-such-questionnaire", missing)).isFalse();
        assertThat(missing.total()).isZero();
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void statsAggregatesInDatabaseWithoutLoadingEveryResponse(H2PlatformTables.Mode mode) {
        JdbcQuestionnairePort port = newPort(mode);
        QuestionnaireDefinition draft =
                port.create(TENANT_A, "分组汇总", QuestionnaireCollectMode.AUTH_END_USER);
        port.replaceItems(
                TENANT_A,
                draft.questionnaireId(),
                List.of(
                        item("i1", 1, QuestionType.SINGLE_CHOICE, "满意度?", true, "[\"好\",\"差\"]"),
                        item("i2", 2, QuestionType.MULTI_CHOICE, "标签", false, "[\"快\",\"稳\"]"),
                        item("i3", 3, QuestionType.RATING, "打分", true, "{\"max\":5}"),
                        item("i4", 4, QuestionType.TEXT_SHORT, "建议", false, null)));
        port.publish(TENANT_A, draft.questionnaireId());

        int production = TenantKeyset.EXPORT_PAGE_LIMIT + 10;
        int good = 40;
        int bothTags = 30;
        int ratingFive = 35;
        for (int i = 0; i < production; i++) {
            String choice = i < good ? "\"好\"" : "\"差\"";
            String tags = i < bothTags ? "[\"快\",\"稳\"]" : "[\"快\"]";
            String rating = i < ratingFive ? "5" : "4";
            port.submit(
                    TENANT_A,
                    draft.questionnaireId(),
                    QuestionnaireRespondentRealm.END_USER,
                    "user-" + i,
                    List.of(
                            new QuestionnaireAnswer("i1", choice),
                            new QuestionnaireAnswer("i2", tags),
                            new QuestionnaireAnswer("i3", rating),
                            new QuestionnaireAnswer("i4", "\"note-" + i + "\"")));
        }
        port.submit(
                TENANT_A,
                draft.questionnaireId(),
                QuestionnaireRespondentRealm.OPERATOR,
                "op-preview",
                List.of(
                        new QuestionnaireAnswer("i1", "\"差\""),
                        new QuestionnaireAnswer("i2", "[\"稳\"]"),
                        new QuestionnaireAnswer("i3", "1"),
                        new QuestionnaireAnswer("i4", "\"hidden\"")));

        QuestionnaireDefinition other = port.create(TENANT_B, "其他租户", QuestionnaireCollectMode.AUTH_END_USER);
        port.replaceItems(
                TENANT_B,
                other.questionnaireId(),
                List.of(item("i1", 1, QuestionType.SINGLE_CHOICE, "满意度?", true, "[\"好\",\"差\"]")));
        port.publish(TENANT_B, other.questionnaireId());
        port.submit(
                TENANT_B,
                other.questionnaireId(),
                QuestionnaireRespondentRealm.END_USER,
                "user-b",
                List.of(new QuestionnaireAnswer("i1", "\"差\"")));

        List<String> aggregatableIds = List.of("i1", "i2", "i3");
        List<QuestionnaireStatsSupport.AnswerValueCount> groups =
                port.loadProductionAnswerValueCounts(TENANT_A, draft.questionnaireId(), aggregatableIds);
        // Grouped input is far smaller than the production set (and smaller than one export page).
        // 分组后的输入远小于生产答卷全集，也小于一页导出。
        assertThat(groups.size()).isLessThan(production);
        assertThat(groups.size()).isLessThan(TenantKeyset.EXPORT_PAGE_LIMIT);
        assertThat(groups).isNotEmpty();
        assertThat(groups.stream().map(QuestionnaireStatsSupport.AnswerValueCount::itemId).distinct())
                .containsExactlyInAnyOrder("i1", "i2", "i3");
        assertThat(port.countProductionResponses(TENANT_A, draft.questionnaireId())).isEqualTo(production);

        QuestionnaireStats stats = port.stats(TENANT_A, draft.questionnaireId()).orElseThrow();
        assertThat(stats.responseCount()).isEqualTo(production);
        assertThat(stats.items()).hasSize(3);
        assertThat(stats.items().get(0).itemId()).isEqualTo("i1");
        assertThat(stats.items().get(0).counts().get("好")).isEqualTo((long) good);
        assertThat(stats.items().get(0).counts().get("差")).isEqualTo((long) (production - good));
        assertThat(stats.items().get(1).counts().get("快")).isEqualTo((long) production);
        assertThat(stats.items().get(1).counts().get("稳")).isEqualTo((long) bothTags);
        assertThat(stats.items().get(2).counts().get("5")).isEqualTo((long) ratingFive);
        assertThat(stats.items().get(2).counts().get("4")).isEqualTo((long) (production - ratingFive));
        assertThat(stats.items().get(2).counts().getOrDefault("1", 0L)).isEqualTo(0L);
        assertThat(port.stats(TENANT_B, other.questionnaireId()).orElseThrow().responseCount()).isEqualTo(1L);
        assertThat(port.stats(TENANT_B, draft.questionnaireId())).isEmpty();
    }

    private static final class ChunkTrackingAppendable implements Appendable {
        private final StringBuilder all = new StringBuilder();
        private int maxChunk;

        @Override
        public Appendable append(CharSequence csq) {
            CharSequence chunk = csq == null ? "null" : csq;
            maxChunk = Math.max(maxChunk, chunk.length());
            all.append(chunk);
            return this;
        }

        @Override
        public Appendable append(CharSequence csq, int start, int end) {
            return append(csq == null ? "null" : csq.subSequence(start, end));
        }

        @Override
        public Appendable append(char c) {
            maxChunk = Math.max(maxChunk, 1);
            all.append(c);
            return this;
        }

        String text() {
            return all.toString();
        }

        int maxChunk() {
            return maxChunk;
        }

        int total() {
            return all.length();
        }
    }

    private static RateLimitPort alwaysPermit() {
        return (tenantId, actionName) -> true;
    }

    private static QuestionnaireItem item(
            String id, int order, QuestionType type, String prompt, boolean required, String options) {
        return new QuestionnaireItem(id, order, type, prompt, required, options);
    }

    private static JdbcQuestionnairePort newPort(H2PlatformTables.Mode mode) {
        DataSource ds = H2PlatformTables.migrated(mode);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ensureTenant(jdbc, TENANT_A);
        ensureTenant(jdbc, TENANT_B);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        return new JdbcQuestionnairePort(jdbc, tx, Clock.fixed(T0, ZoneOffset.UTC), alwaysPermit());
    }

    private static void ensureTenant(JdbcTemplate jdbc, String tenantId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Integer.class, tenantId);
        if (n == null || n == 0) {
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    tenantId,
                    tenantId);
        }
    }
}
