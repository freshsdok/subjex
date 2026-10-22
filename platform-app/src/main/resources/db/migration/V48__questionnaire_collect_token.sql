-- Q4 / ADR 0013: opaque ANON_LINK collect tokens (revoke via revoked_at).
-- Q4 / ADR 0013：匿名采集令牌（revoked_at 撤销）。
-- Rate limits reuse rate_limit_window via RateLimitPort (action per questionnaire).
-- 限流复用 rate_limit_window（按问卷动作键）。

CREATE TABLE questionnaire_collect_token (
    token_id VARCHAR(64) NOT NULL,
    questionnaire_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    PRIMARY KEY (token_id)
);

CREATE INDEX questionnaire_collect_token_q
    ON questionnaire_collect_token (questionnaire_id, revoked_at);
