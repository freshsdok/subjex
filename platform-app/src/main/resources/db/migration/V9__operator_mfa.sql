-- TOTP MFA + recovery codes + short-lived login challenges (Slice D).
-- TOTP 多因素、恢复码与登录挑战令牌（切片 D）。
-- TOTP secrets are AES-GCM ciphertext; recovery codes and challenges are SHA-256 hex hashes only.
-- TOTP 密钥为 AES-GCM 密文；恢复码与挑战令牌只存 SHA-256 十六进制摘要。

CREATE TABLE operator_mfa_totp (
    subject_id VARCHAR(64) NOT NULL,
    secret_encrypted VARCHAR(512) NOT NULL,
    confirmed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (subject_id)
);

CREATE TABLE operator_mfa_recovery (
    recovery_id VARCHAR(64) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    used_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (recovery_id)
);

CREATE UNIQUE INDEX uq_operator_mfa_recovery_hash ON operator_mfa_recovery (code_hash);
CREATE INDEX idx_operator_mfa_recovery_subject ON operator_mfa_recovery (subject_id);

CREATE TABLE operator_mfa_challenge (
    challenge_id VARCHAR(64) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP,
    PRIMARY KEY (challenge_id)
);

CREATE UNIQUE INDEX uq_operator_mfa_challenge_hash ON operator_mfa_challenge (token_hash);
CREATE INDEX idx_operator_mfa_challenge_subject ON operator_mfa_challenge (subject_id);
