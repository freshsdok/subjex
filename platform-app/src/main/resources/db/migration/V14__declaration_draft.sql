-- Tenant-scoped declaration draft revisions (Z3-1 / dual-track B).
-- 租户隔离的声明草稿修订（Z3-1 / 双轨 B）。
-- Shared by MySQL 8.4 and PostgreSQL 16 style (no vendor-only types).
-- MySQL 8.4 与 PostgreSQL 16 共用风格（无厂商专有类型）。
-- declaration_kind values enforced in Java: entity | form | flow (SQL comment only).
-- declaration_kind 取值由 Java 校验：entity | form | flow（此处仅注释）。
-- yaml_body stores declaration text only; schema/table evolution stays on migration queue (no auto-DDL).
-- yaml_body 只存声明文本；改表仍走迁移队列（禁止自动 DDL）。
-- draft_state: DRAFT now; PROMOTED arrives in Z5.
-- draft_state：本片仅 DRAFT；PROMOTED 在 Z5。

CREATE TABLE declaration_revision (
    tenant_id VARCHAR(64) NOT NULL,
    declaration_kind VARCHAR(32) NOT NULL,
    declaration_key VARCHAR(128) NOT NULL,
    revision INT NOT NULL,
    yaml_body VARCHAR(8000) NOT NULL,
    draft_state VARCHAR(32) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    updated_by_subject_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, declaration_kind, declaration_key, revision)
);

INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('declaration.read', 'List and read tenant declaration drafts / 列出并读取租户声明草稿'),
    ('declaration.write', 'Save tenant declaration drafts / 保存租户声明草稿');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'declaration.read'),
    ('platform-operator', 'declaration.write'),
    ('platform-reader', 'declaration.read');
