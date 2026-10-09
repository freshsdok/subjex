-- Dual-operator promote approval + rollback support (P7).
-- 双人晋升确认与回滚支持（P7）。
-- Shared by MySQL 8.4 and PostgreSQL 16 style.
-- MySQL 8.4 与 PostgreSQL 16 共用风格。

CREATE TABLE declaration_promote_approval (
    approval_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    declaration_kind VARCHAR(32) NOT NULL,
    declaration_key VARCHAR(128) NOT NULL,
    revision INT NOT NULL,
    requested_by_subject_id VARCHAR(64) NOT NULL,
    approval_state VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP,
    consumed_by_subject_id VARCHAR(64),
    PRIMARY KEY (approval_id)
);

CREATE INDEX declaration_promote_approval_lookup
    ON declaration_promote_approval (tenant_id, declaration_kind, declaration_key, revision, approval_state);
