-- Operator-defined scheduled work (cron in Asia/Shanghai, enqueue task or start classpath workflow).
-- 操作员定义的定时工作（cron 按 Asia/Shanghai 计算，入队任务或启动 classpath 工作流）。
-- This is a reusable platform scheduler, not a BPM engine, config-center, or second job platform.
-- 这是可复用的平台调度器，不是 BPM 引擎、配置中心或第二套任务平台。
-- Reserved tenant platform is never a business home. Shared by MySQL 8.4 and PostgreSQL 16 style.
-- 保留租户 platform 不是业务归属。MySQL 8.4 与 PostgreSQL 16 共用风格。

CREATE TABLE operator_schedule (
    schedule_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    schedule_name VARCHAR(200) NOT NULL,
    cron_expression VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    task_step_name VARCHAR(128),
    workflow_definition_key VARCHAR(128),
    workflow_entity_key VARCHAR(128),
    workflow_record_id VARCHAR(96),
    next_run_at TIMESTAMP,
    last_run_at TIMESTAMP,
    last_error VARCHAR(512),
    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMP,
    created_by_subject_id VARCHAR(64) NOT NULL,
    created_by_identity_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (schedule_id)
);

CREATE INDEX operator_schedule_tenant_created
    ON operator_schedule (tenant_id, created_at);

CREATE INDEX operator_schedule_due
    ON operator_schedule (next_run_at);

INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('schedule.read', 'List operator schedules in a granted tenant / 在已授权租户内查阅操作员调度'),
    ('schedule.write', 'Create, enable, or disable operator schedules / 创建、启用或停用操作员调度');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'schedule.read'),
    ('platform-operator', 'schedule.write'),
    ('platform-reader', 'schedule.read');
