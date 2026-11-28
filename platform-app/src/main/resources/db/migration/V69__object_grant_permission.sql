-- Operator object-grant management (list / grant / revoke) on the authorized-object track.
-- 操作员对象授权管理（列出、授予、撤销），走已有授权对象轨。
-- Read may list registered objects and grants in a granted tenant. Write creates or revokes grants.
-- 读权限可列出已授权租户内已登记对象与授权行；写权限用于授予或撤销。
-- Does not add public share links, folders, or a previewer. Does not migrate inbox or entity_blob.
-- 不提供公开分享链接、文件夹或预览器。不迁移站内信附件或 entity_blob。
INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('object.grant.read', 'List registered objects and their grants in a granted tenant / 在已授权租户内列出已登记对象及其授权'),
    ('object.grant', 'Grant or revoke object access to a subject or organization in a granted tenant / 在已授权租户内向主体或组织授予或撤销对象访问');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'object.grant.read'),
    ('platform-operator', 'object.grant'),
    ('platform-reader', 'object.grant.read');
