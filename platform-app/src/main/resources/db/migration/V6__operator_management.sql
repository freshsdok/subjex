-- Operator management and operator–tenant grants / 操作员管理与操作员—租户授权
-- Adds operator.manage permission, grant table, no people seeded.
-- 增加 operator.manage 权限与授权表，不写入任何人。

INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('operator.manage', 'List, create, disable operators and assign tenant grants / 列出、创建、禁用操作员并分配租户授权');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'operator.manage');

-- Which business tenants an operator (subject) may act in. tenant_id '*' means all tenants.
-- 操作员（主体）可行动的业务租户。tenant_id 为 '*' 表示全部租户。
CREATE TABLE operator_tenant_grant (
    subject_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (subject_id, tenant_id)
);
