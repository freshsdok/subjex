-- Declaration migration queue (MQ-1) — persist reviewed SQL; no auto-apply this slice.
-- 声明迁移队列（MQ-1）——落库待审 SQL；本片不自动执行。
-- Shared by MySQL 8.4 and PostgreSQL 16 style (no vendor-only types).
-- MySQL 8.4 与 PostgreSQL 16 共用风格（无厂商专有类型）。
-- declaration_revision mirrors declaration_promote.revision (revision number, not a separate PK).
-- declaration_revision 对齐 declaration_promote.revision（修订号，非独立主键）。
-- Prefer additive SQL; hosts switch declaration revision only after APPLIED (bind = MQ-2).
-- 优先加列 SQL；主机仅在 APPLIED 后切换声明修订（绑定见 MQ-2）。
-- status values enforced in Java: PENDING | REVIEWED | APPLIED | FAILED | CANCELLED.
-- status 取值由 Java 校验。

CREATE TABLE declaration_migration (
    migration_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    declaration_kind VARCHAR(32) NOT NULL,
    declaration_key VARCHAR(128) NOT NULL,
    declaration_revision INT NOT NULL,
    sql_text VARCHAR(16000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    created_by_subject_id VARCHAR(64) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    applied_at TIMESTAMP,
    error_message VARCHAR(2000),
    PRIMARY KEY (migration_id)
);

CREATE INDEX declaration_migration_tenant_key_status
    ON declaration_migration (tenant_id, declaration_kind, declaration_key, status);

INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('declaration.migrate', 'Enqueue and review declaration schema migrations / 入队并审阅声明 schema 迁移');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'declaration.migrate');
