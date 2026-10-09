-- Org write permission: create/update/disable org units and memberships (Org-W1).
-- 组织写权限：创建/更新/停用组织单元与成员关系（Org-W1）。

INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('org.write', 'Create/update/disable org units and memberships / 创建、更新、停用组织单元与成员关系');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'org.write');
-- platform-reader keeps org.read only (no org.write).
-- platform-reader 仍仅有 org.read（无 org.write）。
