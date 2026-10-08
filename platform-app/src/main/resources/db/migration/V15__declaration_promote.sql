-- Declaration promote audit + declaration.promote (Z5-1 / internal git).
-- 声明晋升审计 + declaration.promote（Z5-1 / 内部 git）。
-- Shared by MySQL 8.4 and PostgreSQL 16 style (no vendor-only types).
-- MySQL 8.4 与 PostgreSQL 16 共用风格（无厂商专有类型）。
-- Records each promote into the in-platform git working tree (not GitHub).
-- 记录每次写入平台内 git 工作树的晋升（不依赖 GitHub）。
-- Schema/DDL is never auto-applied from promote (migration queue stays separate).
-- 晋升不自动改表（迁移队列仍独立）。

CREATE TABLE declaration_promote (
    tenant_id VARCHAR(64) NOT NULL,
    declaration_kind VARCHAR(32) NOT NULL,
    declaration_key VARCHAR(128) NOT NULL,
    revision INT NOT NULL,
    git_commit_sha VARCHAR(64) NOT NULL,
    promoted_at TIMESTAMP NOT NULL,
    promoted_by_subject_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, declaration_kind, declaration_key, revision)
);

INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('declaration.promote', 'Promote tenant declaration drafts into internal git / 将租户声明草稿晋升进内部 git');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'declaration.promote');
