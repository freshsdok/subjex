-- ID-1: thin OIDC OP — clients, auth codes, signing keys.
-- ID-1：轻量 OIDC 签发方——客户端、授权码、签名密钥。

CREATE TABLE oauth_client (
  client_id VARCHAR(64) NOT NULL,
  client_secret_hash VARCHAR(64),
  client_name VARCHAR(128) NOT NULL,
  redirect_uris VARCHAR(2000) NOT NULL,
  grant_types VARCHAR(256) NOT NULL,
  scopes VARCHAR(512) NOT NULL,
  require_pkce BOOLEAN NOT NULL,
  public_client BOOLEAN NOT NULL,
  active BOOLEAN NOT NULL,
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (client_id)
);

CREATE TABLE oauth_auth_code (
  code_hash VARCHAR(64) NOT NULL,
  client_id VARCHAR(64) NOT NULL,
  subject_id VARCHAR(64) NOT NULL,
  account_id VARCHAR(64) NOT NULL,
  login_name VARCHAR(128) NOT NULL,
  redirect_uri VARCHAR(512) NOT NULL,
  scopes VARCHAR(512) NOT NULL,
  code_challenge VARCHAR(128) NOT NULL,
  code_challenge_method VARCHAR(16) NOT NULL,
  nonce VARCHAR(128),
  expires_at TIMESTAMP NOT NULL,
  consumed_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (code_hash)
);

CREATE INDEX idx_oauth_auth_code_client ON oauth_auth_code (client_id);
CREATE INDEX idx_oauth_auth_code_expires ON oauth_auth_code (expires_at);

CREATE TABLE oauth_signing_key (
  kid VARCHAR(64) NOT NULL,
  algorithm VARCHAR(16) NOT NULL,
  private_pem VARCHAR(8000) NOT NULL,
  public_pem VARCHAR(4000) NOT NULL,
  active BOOLEAN NOT NULL,
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (kid)
);

-- First-party console public client (PKCE). Redirects for local console defaults.
-- 控制台一等公钥客户端（PKCE）。本地控制台默认回调。
INSERT INTO oauth_client (
  client_id, client_secret_hash, client_name, redirect_uris, grant_types, scopes,
  require_pkce, public_client, active, created_at
) VALUES (
  'subjex-console',
  NULL,
  'Subjex Console',
  'http://127.0.0.1:3000/api/auth/callback http://localhost:3000/api/auth/callback',
  'authorization_code',
  'openid profile',
  TRUE,
  TRUE,
  TRUE,
  CURRENT_TIMESTAMP
);
