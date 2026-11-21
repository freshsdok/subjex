-- WP8-1: append-only purpose-consent ledger (ADR 0020).
-- WP8-1：目的同意追加式台账（ADR 0020）。
-- Kernel stores no legal text, only a tenant-supplied purpose key and consent-version id.
-- 内核不存放法律正文，只存租户提供的目的键与同意文本版本标识。
-- This is not oauth_consent (OAuth client scopes) and not tenant-level ZIP export.
-- 本表不是 oauth_consent（OAuth 客户端 scope），也不是租户级 ZIP 导出。
-- consent_event is append-only (no updates or deletes of past facts).
-- consent_event 只追加（不改、不删既有事实）。
-- consent_current is a projection of the latest GRANT that has not been withdrawn, kept in the same transaction.
-- consent_current 是尚未撤销的最近一次 GRANT 的投影，与事件在同一事务中保持一致。
-- Shared by MySQL 8.4 and PostgreSQL 16 style (no vendor-only types, no FKs).
-- MySQL 8.4 与 PostgreSQL 16 共用风格（无厂商专有类型，不加外键）。

CREATE TABLE consent_event (
    event_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    purpose_key VARCHAR(64) NOT NULL,
    consent_version_id VARCHAR(64) NOT NULL,
    event_kind VARCHAR(16) NOT NULL,
    actor_identity_id VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    PRIMARY KEY (event_id)
);

CREATE INDEX consent_event_tenant_subject_purpose_time
    ON consent_event (tenant_id, subject_id, purpose_key, occurred_at, event_id);

CREATE TABLE consent_current (
    tenant_id VARCHAR(64) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    purpose_key VARCHAR(64) NOT NULL,
    consent_version_id VARCHAR(64) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    granted_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, subject_id, purpose_key)
);

CREATE INDEX consent_current_tenant_subject
    ON consent_current (tenant_id, subject_id, purpose_key);
