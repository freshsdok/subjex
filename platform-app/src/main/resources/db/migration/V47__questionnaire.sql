-- Q1 / ADR 0013: questionnaire definition + response schema (dual MySQL/PostgreSQL/H2).
-- Q1 / ADR 0013：问卷定义与答卷表（MySQL/PostgreSQL/H2 共用）。
-- Survey SoT is these tables — never form_submission.
-- 调研真相源是这些表，绝不用 form_submission。
-- Response/answer tables are schema-ready; submit APIs land in Q3+.
-- 答卷表仅建好 schema；提交 API 在 Q3+。

CREATE TABLE questionnaire (
    questionnaire_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    title VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL,
    revision INT NOT NULL,
    collect_mode VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (questionnaire_id)
);

CREATE INDEX questionnaire_tenant_updated
    ON questionnaire (tenant_id, updated_at);

CREATE TABLE questionnaire_item (
    questionnaire_id VARCHAR(64) NOT NULL,
    revision INT NOT NULL,
    item_id VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL,
    question_type VARCHAR(32) NOT NULL,
    prompt VARCHAR(2000) NOT NULL,
    required BOOLEAN NOT NULL,
    options_json VARCHAR(4000),
    PRIMARY KEY (questionnaire_id, revision, item_id)
);

CREATE INDEX questionnaire_item_q_rev_sort
    ON questionnaire_item (questionnaire_id, revision, sort_order);

CREATE TABLE questionnaire_response (
    response_id VARCHAR(64) NOT NULL,
    questionnaire_id VARCHAR(64) NOT NULL,
    revision INT NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    respondent_realm VARCHAR(16) NOT NULL,
    respondent_subject_id VARCHAR(64) NOT NULL,
    submitted_at TIMESTAMP NOT NULL,
    PRIMARY KEY (response_id)
);

CREATE INDEX questionnaire_response_q_submitted
    ON questionnaire_response (questionnaire_id, submitted_at);

CREATE INDEX questionnaire_response_tenant_submitted
    ON questionnaire_response (tenant_id, submitted_at);

CREATE TABLE questionnaire_answer (
    response_id VARCHAR(64) NOT NULL,
    item_id VARCHAR(64) NOT NULL,
    value_json VARCHAR(4000) NOT NULL,
    PRIMARY KEY (response_id, item_id)
);

INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('questionnaire.read', 'Read questionnaire definitions and responses / 读取问卷定义与答卷'),
    ('questionnaire.write', 'Create and edit questionnaire definitions / 创建与编辑问卷定义');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'questionnaire.read'),
    ('platform-operator', 'questionnaire.write'),
    ('platform-reader', 'questionnaire.read');
