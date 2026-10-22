package com.subjex.platform.contract.questionnaire;

/**
 * QuestionnaireAnonKeys — 匿名限流动作键与主体派生（Q4）。
 */
public final class QuestionnaireAnonKeys {

    /** RateLimitPort action prefix — 限流动作前缀. */
    public static final String RATE_ACTION_PREFIX = "questionnaire-anon-submit/";

    private QuestionnaireAnonKeys() {}

    /**
     * Bucket action: per questionnaire + client key — 按问卷+客户端键分桶.
     */
    public static String rateAction(String questionnaireId, String clientKey) {
        return RATE_ACTION_PREFIX + questionnaireId + "/" + clientKey;
    }

    /**
     * Opaque ANON subject from token + client key (stable, ≤64 chars) —
     * 由令牌+客户端键派生的匿名主体（稳定、≤64）.
     */
    public static String opaqueSubject(String tokenId, String clientKey) {
        // Compact hex of hashCode mix — enough for MVP identity, not a crypto commitment.
        // 紧凑十六进制混合 — MVP 身份足够，非密码学承诺。
        long mix = ((long) tokenId.hashCode() << 32) ^ (clientKey.hashCode() & 0xffffffffL);
        String hex = Long.toUnsignedString(mix, 16);
        String raw = "a-" + hex;
        return raw.length() <= 64 ? raw : raw.substring(0, 64);
    }
}
