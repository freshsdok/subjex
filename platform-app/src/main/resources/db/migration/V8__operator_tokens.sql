-- Platform-issued opaque access + rotating refresh tokens (Slice C).
-- 平台签发的不透明访问令牌与轮换刷新令牌（切片 C）。
-- Raw tokens are never stored; only SHA-256 hex hashes.
-- 从不存原始令牌，只存 SHA-256 十六进制摘要。

CREATE TABLE operator_access_token (
    token_id VARCHAR(64) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    family_id VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    PRIMARY KEY (token_id)
);

CREATE UNIQUE INDEX uq_operator_access_token_hash ON operator_access_token (token_hash);
CREATE INDEX idx_operator_access_token_subject ON operator_access_token (subject_id);
CREATE INDEX idx_operator_access_token_family ON operator_access_token (family_id);

CREATE TABLE operator_refresh_token (
    token_id VARCHAR(64) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    family_id VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    user_agent VARCHAR(512),
    client_ip VARCHAR(64),
    PRIMARY KEY (token_id)
);

CREATE UNIQUE INDEX uq_operator_refresh_token_hash ON operator_refresh_token (token_hash);
CREATE INDEX idx_operator_refresh_token_subject ON operator_refresh_token (subject_id);
CREATE INDEX idx_operator_refresh_token_family ON operator_refresh_token (family_id);
