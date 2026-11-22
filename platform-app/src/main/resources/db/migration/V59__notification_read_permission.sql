-- Operator read-only query of notification send records (console list).
-- 操作员只读查询外部通知发送记录（控制台列表）。
-- Does not allow sending, retrying, or replaying; replay stays on the dead-letter API.
-- 不允许发送、重试或重放；重放仍走已有死信接口。
INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('notification.read', 'Read notification send records in a granted tenant / 在已授权租户内阅读通知发送记录');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'notification.read'),
    ('platform-reader', 'notification.read');
