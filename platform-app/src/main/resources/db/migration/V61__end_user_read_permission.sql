-- Operator read-only directory of existing end users in a granted tenant (console list/lookup).
-- 操作员只读查阅已授权租户内已有的终端用户（控制台名册与查找）。
-- Does not add disable, unlink, or a new account-lifecycle product.
-- 不新增停用、解绑或另一套账号生命周期产品。
INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('end-user.read', 'Read existing end-user directory rows in a granted tenant / 在已授权租户内查阅已有终端用户名册');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'end-user.read'),
    ('platform-reader', 'end-user.read');
