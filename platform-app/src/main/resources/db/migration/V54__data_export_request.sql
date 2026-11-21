-- WP8-2: data-export request + approval hook, and optional object sensitivity (ADR 0020).
-- WP8-2：数据导出申请与审批钩子，以及对象可选敏感级别（ADR 0020）。
-- Existing object_descriptor rows receive NORMAL; this does not silently mark them SENSITIVE.
-- 既有对象行得到 NORMAL，不会被悄悄改标为 SENSITIVE。
-- Not oauth_consent and not tenant-level ZIP export. No outbox table.
-- 不是 OAuth 同意，也不是租户级 ZIP 导出。不加出箱表。
-- Shared by MySQL 8.4 and PostgreSQL 16 style (no vendor-only types, no FKs).
-- MySQL 8.4 与 PostgreSQL 16 共用风格（无厂商专有类型，不加外键）。

ALTER TABLE object_descriptor
    ADD COLUMN sensitivity VARCHAR(16) DEFAULT 'NORMAL' NOT NULL;

CREATE TABLE data_export_request (
    request_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    requester_realm VARCHAR(32) NOT NULL,
    requester_id VARCHAR(64) NOT NULL,
    scope_descriptor VARCHAR(512) NOT NULL,
    request_status VARCHAR(32) NOT NULL,
    approver_subject_id VARCHAR(64),
    workflow_instance_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    decided_at TIMESTAMP,
    PRIMARY KEY (request_id)
);

CREATE INDEX data_export_request_tenant_subject
    ON data_export_request (tenant_id, subject_id, created_at);

CREATE INDEX data_export_request_tenant_status
    ON data_export_request (tenant_id, request_status, created_at);

INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('export.request', 'Request a personal-data export on behalf of a subject / 代主体申请个人数据导出'),
    ('export.approve', 'Approve or reject a personal-data export request / 批准或驳回个人数据导出申请');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'export.request'),
    ('platform-operator', 'export.approve');
-- platform-reader is not granted export.request / export.approve (write-style gates).
-- platform-reader 不授予导出申请/审批（写类门禁）。
