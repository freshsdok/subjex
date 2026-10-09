-- Form submission tenant + quota support (P5).
-- 表单提交租户列 + 配额支持（P5）。
-- Shared by MySQL 8.4 and PostgreSQL 16 style.
-- MySQL 8.4 与 PostgreSQL 16 共用风格。

ALTER TABLE form_submission ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'platform';

CREATE INDEX form_submission_tenant_submitted ON form_submission (tenant_id, submitted_at);
