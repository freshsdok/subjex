-- OIDC RP: IdP subject → operator link + short-lived PKCE login state (Slice E).
-- OIDC 依赖方：IdP 主体到操作员的绑定，以及短时 PKCE 登录态（切片 E）。
-- Deny unlinked IdP users by default (no auto-provision). Platform remains AuthZ source of truth.
-- 默认拒绝未绑定的 IdP 用户（不自动建号）。平台仍是授权真相源。

CREATE TABLE operator_idp_link (
    link_id VARCHAR(64) NOT NULL,
    issuer VARCHAR(512) NOT NULL,
    idp_subject VARCHAR(256) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (link_id)
);

CREATE UNIQUE INDEX uq_operator_idp_link_issuer_sub ON operator_idp_link (issuer, idp_subject);
-- One IdP binding per operator account in v1 (single IdP).
-- v1 每个操作员账号一条 IdP 绑定（单 IdP）。
CREATE UNIQUE INDEX uq_operator_idp_link_account ON operator_idp_link (account_id);
CREATE INDEX idx_operator_idp_link_subject ON operator_idp_link (subject_id);

CREATE TABLE operator_oidc_login (
    state_hash VARCHAR(64) NOT NULL,
    code_verifier VARCHAR(128) NOT NULL,
    nonce VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP,
    PRIMARY KEY (state_hash)
);
