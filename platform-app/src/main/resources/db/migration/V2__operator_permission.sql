-- Operator roles, permissions, and sign-in, shared by MySQL and PostgreSQL.
-- 操作员角色、权限与登录凭据，MySQL 与 PostgreSQL 共用。
-- An operator is a Subject whose Identity lives in the reserved tenant 'platform'.
-- 操作员是一个主体，它的身份落在保留租户 'platform' 里。
-- This script holds the catalog only. No person and no password is seeded here.
-- 这份脚本只放目录。这里不写入任何人，也不写入任何口令。

-- The reserved tenant for operator identities. No business tenant uses this id.
-- 操作员身份所在的保留租户。业务租户不用这个标识。
INSERT INTO tenant (tenant_id, tenant_name, tenant_state)
VALUES ('platform', 'Platform operators / 平台操作员', 'ACTIVE');

-- platform_permission: one named thing an operator may do, for example 'config.write'.
-- platform_permission：操作员可以做的一件具名的事，例如 'config.write'。
CREATE TABLE platform_permission (
    permission_name VARCHAR(64) NOT NULL,
    permission_summary VARCHAR(256) NOT NULL,
    PRIMARY KEY (permission_name)
);

-- platform_role: a named bundle of permissions handed to a subject.
-- platform_role：交给主体的一组具名权限。
CREATE TABLE platform_role (
    role_name VARCHAR(64) NOT NULL,
    role_summary VARCHAR(256) NOT NULL,
    PRIMARY KEY (role_name)
);

-- role_permission: this role grants this permission.
-- role_permission：这个角色授予这项权限。
CREATE TABLE role_permission (
    role_name VARCHAR(64) NOT NULL,
    permission_name VARCHAR(64) NOT NULL,
    PRIMARY KEY (role_name, permission_name)
);

-- subject_role: this subject holds this role.
-- subject_role：这个主体持有这个角色。
CREATE TABLE subject_role (
    subject_id VARCHAR(64) NOT NULL,
    role_name VARCHAR(64) NOT NULL,
    PRIMARY KEY (subject_id, role_name)
);

-- operator_credential: the password hash an account signs in with. The hash carries its encoder id, e.g. {bcrypt}.
-- operator_credential：账号登录用的口令摘要。摘要带着编码器标识，例如 {bcrypt}。
CREATE TABLE operator_credential (
    account_id VARCHAR(64) NOT NULL,
    password_hash VARCHAR(256) NOT NULL,
    PRIMARY KEY (account_id)
);

-- What an audited action touched, for example a config key or a service name.
-- 被审计的动作碰到的对象，例如一个配置键或一个服务名。
ALTER TABLE audit_entry ADD COLUMN action_target VARCHAR(256);

INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('admin.read', 'Read tenants, tasks, dead letters, health, and audit entries / 读取租户、任务、死信、健康和审计'),
    ('page.read', 'Open the deploy, forms, codegen, language, and skin pages / 打开部署、字段、生成、语言和外观页'),
    ('config.read', 'Read the config list and effective entries / 读取配置名单与生效条目'),
    ('config.write', 'Override one named config key / 压过一个具名配置键'),
    ('registry.read', 'Read the service list and registry / 读取服务名单与登记簿'),
    ('registry.write', 'Register a service endpoint / 登记一个服务端点'),
    ('task.write', 'Submit tasks, attempts, and confirmations / 提交任务、尝试和确认');

INSERT INTO platform_role (role_name, role_summary) VALUES
    ('platform-operator', 'Every operator permission / 全部操作员权限'),
    ('platform-reader', 'Read-only operator pages and admin / 只读操作页与管理台');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'admin.read'),
    ('platform-operator', 'page.read'),
    ('platform-operator', 'config.read'),
    ('platform-operator', 'config.write'),
    ('platform-operator', 'registry.read'),
    ('platform-operator', 'registry.write'),
    ('platform-operator', 'task.write'),
    ('platform-reader', 'admin.read'),
    ('platform-reader', 'page.read'),
    ('platform-reader', 'config.read'),
    ('platform-reader', 'registry.read');
