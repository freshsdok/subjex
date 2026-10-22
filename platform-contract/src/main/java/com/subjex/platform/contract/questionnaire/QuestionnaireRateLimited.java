package com.subjex.platform.contract.questionnaire;

/**
 * QuestionnaireRateLimited — 匿名采集提交超过限流窗口。
 * <p>
 * Thrown by {@link QuestionnairePort#submitAnon} when {@code RateLimitPort} denies the bucket.
 * 匿名提交被限流时抛出。
 */
public final class QuestionnaireRateLimited extends RuntimeException {

    public QuestionnaireRateLimited() {
        super("questionnaire anon submit rate limited");
    }
}
