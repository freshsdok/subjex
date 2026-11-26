-- Questionnaire assignment (AUTH_END_USER visibility). Tenant-scoped.
-- 问卷分配（认证终端可见性），按租户隔离。
-- Operators assign a questionnaire to an individual subject or an organization
-- (department). Published-but-unassigned AUTH_END_USER questionnaires are not
-- visible to everyone. ANON_LINK public tokens are unchanged.
-- 操作员把问卷分配给个人主体或组织（部门）。已发布但未分配的认证问卷不对所有人可见。
-- 匿名填写码不受本表约束。
-- assignee_kind: SUBJECT | ORGANIZATION.
-- 分配对象种类：SUBJECT（个人）或 ORGANIZATION（部门）。
-- Shared by MySQL 8.4 and PostgreSQL 16 style (no vendor-only types).
-- MySQL 8.4 与 PostgreSQL 16 共用风格（无厂商专有类型）。
-- No medical / patient-specific columns.
-- 无医疗/患者专用列。

CREATE TABLE questionnaire_assignment (
    assignment_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    questionnaire_id VARCHAR(64) NOT NULL,
    assignee_kind VARCHAR(32) NOT NULL,
    assignee_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    created_by_subject_id VARCHAR(64),
    PRIMARY KEY (assignment_id)
);

CREATE UNIQUE INDEX uq_questionnaire_assignment
    ON questionnaire_assignment (tenant_id, questionnaire_id, assignee_kind, assignee_id);

CREATE INDEX idx_questionnaire_assignment_q
    ON questionnaire_assignment (tenant_id, questionnaire_id);

CREATE INDEX idx_questionnaire_assignment_assignee
    ON questionnaire_assignment (tenant_id, assignee_kind, assignee_id);
