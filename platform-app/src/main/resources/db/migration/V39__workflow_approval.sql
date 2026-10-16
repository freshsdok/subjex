-- WF-2: durable dual-approve gate for workflow instances (refuse self-approve).
-- WF-2：工作流实例的持久化双人审批门闩（拒绝自批）。

CREATE TABLE workflow_approval (
    approval_id VARCHAR(64) NOT NULL,
    instance_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    requested_by_subject_id VARCHAR(64) NOT NULL,
    approval_state VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP,
    consumed_by_subject_id VARCHAR(64),
    decision_action VARCHAR(32),
    PRIMARY KEY (approval_id)
);

CREATE INDEX workflow_approval_instance_state
    ON workflow_approval (instance_id, approval_state);

CREATE INDEX workflow_approval_tenant
    ON workflow_approval (tenant_id, created_at);
