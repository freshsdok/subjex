-- Operator read-only query of purpose-consent records (console list).
-- 操作员只读查询目的同意记录（控制台列表）。
-- Does not allow granting or withdrawing on behalf of the subject.
-- 不允许代替主体授予或撤回。
INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('consent.read', 'Read purpose-consent records in a granted tenant / 在已授权租户内阅读目的同意记录');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'consent.read'),
    ('platform-reader', 'consent.read');
