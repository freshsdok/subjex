-- ID-2: tenant-bound confidential clients / service accounts (client_credentials).
-- ID-2：租户绑定的机密客户端 / 服务账号（client_credentials）。
-- client_secret_hash stays VARCHAR(64): SHA-256 hex (same pattern as auth codes).
-- client_secret_hash 仍为 VARCHAR(64)：SHA-256 十六进制（与授权码同模式）。

ALTER TABLE oauth_client ADD COLUMN tenant_id VARCHAR(64);

CREATE INDEX idx_oauth_client_tenant ON oauth_client (tenant_id);
