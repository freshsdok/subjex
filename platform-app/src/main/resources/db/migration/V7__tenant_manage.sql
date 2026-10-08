-- Tenant management permission / 租户管理权限
-- Adds tenant.manage for create/rename/disable/enable. List stays admin.read.
-- 增加 tenant.manage 用于创建/改名/禁用/启用。列表仍用 admin.read。
-- tenant table already has tenant_id, tenant_name, tenant_state (ACTIVE|SUSPENDED).
-- tenant 表已有标识、显示名与状态列，无需改表结构。

INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('tenant.manage', 'Create, rename, disable, and enable business tenants / 创建、改名、禁用与启用业务租户');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'tenant.manage');
