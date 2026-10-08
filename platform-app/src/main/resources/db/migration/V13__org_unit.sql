-- Thin org_unit tree + membership inside a tenant (R1 #2 / thin-org-1).
-- 租户内薄组织树与成员关系（R1 #2 / thin-org-1）。
-- Shared by MySQL 8.4 and PostgreSQL 16 style (no vendor-only types; no self-FK for H2 dual-dialect simplicity).
-- MySQL 8.4 与 PostgreSQL 16 共用风格（无厂商专有类型；为 H2 双方言简化不加自引用外键）。
-- Parent must be same tenant (enforced by app later; documented only here).
-- 父节点须同租户（应用层稍后约束；此处仅文档说明）。

-- org_unit: department/team tree scoped by tenant_id. parent_org_unit_id NULL = root.
-- org_unit：按 tenant_id 隔离的部门/团队树。parent_org_unit_id 为空即根。
CREATE TABLE org_unit (
    tenant_id VARCHAR(64) NOT NULL,
    org_unit_id VARCHAR(64) NOT NULL,
    parent_org_unit_id VARCHAR(64),
    unit_name VARCHAR(200) NOT NULL,
    unit_state VARCHAR(32) NOT NULL,
    PRIMARY KEY (tenant_id, org_unit_id)
);

-- org_membership: subject belongs to an org unit within a tenant (simple membership; no primary/secondary dual-role).
-- org_membership：主体在租户内属于某组织单元（简单成员关系；无主/兼岗双角色）。
CREATE TABLE org_membership (
    tenant_id VARCHAR(64) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    org_unit_id VARCHAR(64) NOT NULL,
    membership_state VARCHAR(32) NOT NULL,
    PRIMARY KEY (tenant_id, subject_id, org_unit_id)
);

-- Read-only permission for org tree and memberships.
-- 组织树与成员关系的只读权限。
INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('org.read', 'List org_unit tree and memberships (read-only) / 列出组织树与成员关系（只读）');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'org.read'),
    ('platform-reader', 'org.read');

-- Reserved break-glass role: own name, isolated; zero ordinary permissions in this slice; no subject_role.
-- 预留破窗角色：独立角色名、与普通授权隔离；本片零普通权限；不分配任何主体。
INSERT INTO platform_role (role_name, role_summary) VALUES
    ('platform.super-admin', 'Break-glass / isolated platform power; not an ordinary grant / 破窗/隔离平台权力；非普通授权');
