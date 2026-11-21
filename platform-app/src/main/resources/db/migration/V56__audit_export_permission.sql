-- WP9-1: audit.export permission for NDJSON audit export (operator only).
-- WP9-1：审计 NDJSON 导出权限，仅授予 platform-operator。
INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('audit.export', 'Export audit records as NDJSON / 以 NDJSON 导出审计记录');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'audit.export');
-- platform-reader is not granted audit.export (bulk extract).
-- platform-reader 不授予审计导出（批量提取）。
