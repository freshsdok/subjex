-- End-user profile fields beside the identity triad (avatar + verified email).
-- 终端资料旁表（头像与已验证邮箱），不改 subject 三分表。
-- Avatar is an authorized-track object the account owns; this table stores the
-- object id and the tenant where it was registered, never a raw storage key.
-- 头像是账号拥有的授权轨对象；本表只存对象标识与登记租户，不存存储键。
-- email is the verified address only. pending_email is held until a verification
-- code is consumed; an unverified address must not replace a verified one.
-- email 只存已验证地址；pending_email 在验证码被消费前暂存，未验证不得覆盖已验证邮箱。
-- Email is a profile field, not a new login authenticator.
-- 邮箱是资料字段，不是新的登录认证器。
-- Shared by MySQL 8.4 and PostgreSQL 16 style (no vendor-only types).
-- MySQL 8.4 与 PostgreSQL 16 共用风格（无厂商专有类型）。
-- No medical / patient-specific columns.
-- 无医疗/患者专用列。

CREATE TABLE end_user_profile (
    account_id VARCHAR(64) NOT NULL,
    avatar_object_id VARCHAR(64),
    avatar_tenant_id VARCHAR(64),
    email VARCHAR(256),
    email_verified_at TIMESTAMP,
    pending_email VARCHAR(256),
    pending_email_requested_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (account_id)
);

CREATE INDEX idx_end_user_profile_email ON end_user_profile (email);
