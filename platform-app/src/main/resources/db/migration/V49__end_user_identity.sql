-- WP2-1: first-class end-user identity tables (ADR 0014).
-- WP2-1：一等终端用户身份表（ADR 0014）。
-- Reuses account / subject / subject_identity. Does not touch operator_* tables.
-- 复用 account / subject / subject_identity。不改动 operator_* 表。
-- Phone uniqueness is global on the account (one phone → one Account).
-- 手机号在账号上全局唯一（一个手机号对应一个账号）。
-- WeChat / IdP link follows the existing adapter model: unique (issuer, idp_subject)
-- (issuer is wechat:mini / wechat:open; subject is unionId if present else openId).
-- 微信/IdP 绑定沿用现有适配器模型：唯一 (issuer, idp_subject)。
-- Token and lockout tables are created here so later slices do not need a second
-- migration; this slice does not issue tokens or run lockout.
-- 令牌与锁定表在此建好，供后续切片使用；本切片不签发令牌、不执行锁定。
-- Shared by MySQL 8.4 and PostgreSQL 16 style (no vendor-only types).
-- MySQL 8.4 与 PostgreSQL 16 共用风格（无厂商专有类型）。
-- No medical / patient-specific columns.
-- 无医疗/患者专用列。

CREATE TABLE end_user_phone_binding (
    binding_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (binding_id)
);

CREATE UNIQUE INDEX uq_end_user_phone_binding_phone ON end_user_phone_binding (phone);
-- One phone binding per account in this wave (bind replaces after unbind in later slices).
-- 本波每个账号一条手机号绑定（解绑后再绑由后续切片处理）。
CREATE UNIQUE INDEX uq_end_user_phone_binding_account ON end_user_phone_binding (account_id);

CREATE TABLE end_user_idp_link (
    link_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    provider_id VARCHAR(64) NOT NULL,
    issuer VARCHAR(512) NOT NULL,
    idp_subject VARCHAR(256) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (link_id)
);

CREATE UNIQUE INDEX uq_end_user_idp_link_issuer_sub ON end_user_idp_link (issuer, idp_subject);
-- One IdP binding per account in this wave (phone lives in a separate table).
-- 本波每个账号一条 IdP 绑定（手机号在另一张表）。
CREATE UNIQUE INDEX uq_end_user_idp_link_account ON end_user_idp_link (account_id);

CREATE TABLE end_user_access_token (
    token_id VARCHAR(64) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    family_id VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    PRIMARY KEY (token_id)
);

CREATE UNIQUE INDEX uq_end_user_access_token_hash ON end_user_access_token (token_hash);
CREATE INDEX idx_end_user_access_token_subject ON end_user_access_token (subject_id);
CREATE INDEX idx_end_user_access_token_family ON end_user_access_token (family_id);

CREATE TABLE end_user_refresh_token (
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

CREATE UNIQUE INDEX uq_end_user_refresh_token_hash ON end_user_refresh_token (token_hash);
CREATE INDEX idx_end_user_refresh_token_subject ON end_user_refresh_token (subject_id);
CREATE INDEX idx_end_user_refresh_token_family ON end_user_refresh_token (family_id);

CREATE TABLE end_user_login_lockout (
    lockout_key VARCHAR(256) NOT NULL,
    failure_count INT NOT NULL,
    locked_until TIMESTAMP,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (lockout_key)
);
