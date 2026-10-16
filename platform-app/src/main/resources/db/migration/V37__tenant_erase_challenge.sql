-- TN-2: single-use hard-erase confirm tokens (GDPR-ish).
-- TN-2：一次性硬删除确认令牌（类 GDPR）。

CREATE TABLE tenant_erase_challenge (
    challenge_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    created_by_identity_id VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP,
    PRIMARY KEY (challenge_id)
);

CREATE INDEX tenant_erase_challenge_tenant ON tenant_erase_challenge (tenant_id, expires_at);
