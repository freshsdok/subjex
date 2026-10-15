-- ID-3: scope catalog, first-party flag, consent grants + pending challenges.
-- ID-3：范围目录、一等客户端标记、同意授权与待决挑战。

ALTER TABLE oauth_client ADD COLUMN first_party BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE oauth_client SET first_party = TRUE WHERE client_id = 'subjex-console';

CREATE TABLE oauth_scope (
  scope_name VARCHAR(64) NOT NULL,
  description_en VARCHAR(512) NOT NULL,
  description_zh VARCHAR(512) NOT NULL,
  -- Null for openid/profile; else aligns with OperatorPermission name.
  -- openid/profile 为空；其余对齐 OperatorPermission 名。
  permission_name VARCHAR(64),
  PRIMARY KEY (scope_name)
);

INSERT INTO oauth_scope (scope_name, description_en, description_zh, permission_name) VALUES
  ('openid', 'Sign you in', '登录身份', NULL),
  ('profile', 'Basic profile (login name)', '基本资料（登录名）', NULL),
  ('page.read', 'Read console pages and entity lists', '读取控制台页面与实体列表', 'page.read'),
  ('admin.read', 'Read admin and audit data', '读取管理与审计数据', 'admin.read'),
  ('config.read', 'Read configuration entries', '读取配置条目', 'config.read'),
  ('registry.read', 'Read service registry', '读取服务登记簿', 'registry.read'),
  ('org.read', 'Read organization ontology', '读取组织本体', 'org.read'),
  ('declaration.read', 'Read declaration drafts', '读取声明草稿', 'declaration.read');

CREATE TABLE oauth_consent (
  subject_id VARCHAR(64) NOT NULL,
  client_id VARCHAR(64) NOT NULL,
  scopes VARCHAR(512) NOT NULL,
  granted_at TIMESTAMP NOT NULL,
  revoked_at TIMESTAMP,
  PRIMARY KEY (subject_id, client_id)
);

CREATE INDEX idx_oauth_consent_client ON oauth_consent (client_id);

CREATE TABLE oauth_consent_challenge (
  challenge_id VARCHAR(64) NOT NULL,
  subject_id VARCHAR(64) NOT NULL,
  account_id VARCHAR(64) NOT NULL,
  login_name VARCHAR(128) NOT NULL,
  client_id VARCHAR(64) NOT NULL,
  redirect_uri VARCHAR(512) NOT NULL,
  scopes_requested VARCHAR(512) NOT NULL,
  code_challenge VARCHAR(128) NOT NULL,
  code_challenge_method VARCHAR(16) NOT NULL,
  state VARCHAR(256),
  nonce VARCHAR(128),
  expires_at TIMESTAMP NOT NULL,
  consumed_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (challenge_id)
);

CREATE INDEX idx_oauth_consent_challenge_subject ON oauth_consent_challenge (subject_id);
CREATE INDEX idx_oauth_consent_challenge_expires ON oauth_consent_challenge (expires_at);
