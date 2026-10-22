package com.subjex.platform.contract.questionnaire;

import java.util.List;
import java.util.Optional;

/**
 * QuestionnairePort — 问卷端口：定义/答卷/匿名令牌（Q1–Q4）+ 薄汇总/CSV（Q5）。
 * <p>
 * Tenant fail-closed. OPERATOR preview excluded from production stats/export by default.
 * 租户失败关闭；操作员预览默认不进生产统计/导出。
 */
public interface QuestionnairePort {

    QuestionnaireDefinition create(String tenantId, String title, QuestionnaireCollectMode collectMode);

    Optional<QuestionnaireDefinition> get(String tenantId, String questionnaireId);

    List<QuestionnaireDefinition> list(String tenantId, int limit);

    Optional<QuestionnaireDefinition> replaceItems(
            String tenantId, String questionnaireId, List<QuestionnaireItem> items);

    /**
     * DRAFT → PUBLISHED; ANON_LINK auto-issues a collect token — 发布；匿名模式自动签发采集令牌。
     */
    Optional<QuestionnaireDefinition> publish(String tenantId, String questionnaireId);

    Optional<QuestionnaireDefinition> close(String tenantId, String questionnaireId);

    Optional<QuestionnaireResponse> submit(
            String tenantId,
            String questionnaireId,
            QuestionnaireRespondentRealm respondentRealm,
            String respondentSubjectId,
            List<QuestionnaireAnswer> answers);

    List<QuestionnaireResponse> listResponses(
            String tenantId, String questionnaireId, QuestionnaireResponseListQuery query);

    /**
     * Issue (or rotate) an active collect token for an ANON_LINK questionnaire —
     * 为匿名问卷签发/轮换活跃采集令牌。
     * <p>
     * Prior active tokens for the questionnaire are revoked. Requires PUBLISHED + ANON_LINK.
     * 同问卷先前活跃令牌撤销；须已发布且 ANON_LINK。
     */
    Optional<QuestionnaireCollectToken> issueCollectToken(String tenantId, String questionnaireId);

    /**
     * Revoke one collect token — 撤销一条采集令牌.
     *
     * @return empty if missing / wrong tenant / 不存在或租户不符
     */
    Optional<QuestionnaireCollectToken> revokeCollectToken(String tenantId, String tokenId);

    /**
     * Active collect token for a questionnaire if any — 问卷当前活跃采集令牌（若有）.
     */
    Optional<QuestionnaireCollectToken> activeCollectToken(String tenantId, String questionnaireId);

    /**
     * Resolve PUBLISHED definition by active collect token (public fill) —
     * 凭活跃令牌解析已发布定义（公开填报）.
     */
    Optional<QuestionnaireDefinition> getByCollectToken(String tokenId);

    /**
     * Anonymous submit via collect token — 凭采集令牌匿名提交.
     * <p>
     * Rate-limited per tenant + questionnaire + {@code clientKey}. Subject is opaque
     * {@link QuestionnaireAnonKeys#opaqueSubject}. Throws {@link QuestionnaireRateLimited} when denied.
     * 按租户+问卷+客户端键限流；主体不透明派生；拒绝时抛 {@link QuestionnaireRateLimited}。
     */
    Optional<QuestionnaireResponse> submitAnon(String tokenId, String clientKey, List<QuestionnaireAnswer> answers);

    /**
     * Thin production aggregates (excludes OPERATOR preview) — 生产薄汇总（排除操作员预览）.
     *
     * @return empty if questionnaire missing / 问卷不存在则 empty
     */
    Optional<QuestionnaireStats> stats(String tenantId, String questionnaireId);

    /**
     * CSV of production responses (excludes OPERATOR preview) — 生产答卷 CSV（排除预览）.
     *
     * @return empty if questionnaire missing / 问卷不存在则 empty
     */
    Optional<String> exportCsv(String tenantId, String questionnaireId);
}
