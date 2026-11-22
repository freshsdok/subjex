-- Operator read-only query of payment and refund status (console list).
-- 操作员只读查询支付与退款状态（控制台列表）。
-- Does not allow creating orders, applying callbacks, or issuing refunds.
-- 不允许创单、办理回调或发起退款。
INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('payment.read', 'Read payment and refund status in a granted tenant / 在已授权租户内阅读支付与退款状态');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'payment.read'),
    ('platform-reader', 'payment.read');
