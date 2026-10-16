-- WF-1: thin workflow instances + transition history; repair-ticket FSM permissions.
-- WF-1：薄工作流实例与流转历史；报修工单 FSM 权限。

CREATE TABLE workflow_instance (
    instance_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    definition_key VARCHAR(64) NOT NULL,
    entity_key VARCHAR(128) NOT NULL,
    record_id VARCHAR(128) NOT NULL,
    current_state VARCHAR(32) NOT NULL,
    started_by_subject_id VARCHAR(64) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (instance_id)
);

CREATE UNIQUE INDEX workflow_instance_record_uq
    ON workflow_instance (tenant_id, entity_key, record_id);

CREATE INDEX workflow_instance_tenant_state
    ON workflow_instance (tenant_id, current_state);

CREATE TABLE workflow_transition_history (
    history_id VARCHAR(64) NOT NULL,
    instance_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    from_state VARCHAR(32),
    action_name VARCHAR(64) NOT NULL,
    to_state VARCHAR(32) NOT NULL,
    actor_subject_id VARCHAR(64) NOT NULL,
    note_text VARCHAR(512),
    capability_id VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (history_id)
);

CREATE INDEX workflow_transition_history_instance
    ON workflow_transition_history (instance_id, created_at);

INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('workflow.read', 'Read workflow instance status and history / 读取工作流实例状态与历史'),
    ('workflow.write', 'Start workflow and apply work transitions / 启动工作流并执行作业类流转'),
    ('workflow.approve', 'Approve or reject awaiting workflow steps / 审批或驳回待审工作流步骤');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'workflow.read'),
    ('platform-operator', 'workflow.write'),
    ('platform-operator', 'workflow.approve'),
    ('platform-reader', 'workflow.read');
