-- Platform schema shared by MySQL and PostgreSQL.
-- 平台表结构，MySQL 与 PostgreSQL 共用。
-- Tables are objects. Columns are facts. States are names, not a second model.
-- 表是对象。列是事实。状态是名字，不是另一层模型。
-- Reads and writes use SQL. There is no entity model beside these tables.
-- 读写使用 SQL。这些表旁边没有实体模型。

CREATE TABLE tenant (
    tenant_id VARCHAR(64) NOT NULL,
    tenant_name VARCHAR(256) NOT NULL,
    tenant_state VARCHAR(32) NOT NULL,
    PRIMARY KEY (tenant_id)
);

CREATE TABLE account (
    account_id VARCHAR(64) NOT NULL,
    login_name VARCHAR(256) NOT NULL,
    account_state VARCHAR(32) NOT NULL,
    PRIMARY KEY (account_id)
);

CREATE TABLE subject (
    subject_id VARCHAR(64) NOT NULL,
    subject_name VARCHAR(256) NOT NULL,
    subject_kind VARCHAR(32) NOT NULL,
    PRIMARY KEY (subject_id)
);

CREATE TABLE subject_identity (
    identity_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    identity_state VARCHAR(32) NOT NULL,
    PRIMARY KEY (identity_id)
);

CREATE TABLE audit_entry (
    audit_entry_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    actor_identity_id VARCHAR(64) NOT NULL,
    action_name VARCHAR(128) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    PRIMARY KEY (audit_entry_id)
);

CREATE TABLE platform_task (
    task_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    task_kind VARCHAR(32) NOT NULL,
    task_state VARCHAR(32) NOT NULL,
    step_name VARCHAR(128) NOT NULL,
    attempt_count INT NOT NULL,
    max_attempt INT NOT NULL,
    model_id VARCHAR(128),
    input_digest VARCHAR(128),
    human_confirmation VARCHAR(32),
    failure_reason VARCHAR(512),
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (task_id),
    CONSTRAINT platform_task_kind_facts CHECK (
        (task_kind = 'DETERMINISTIC'
            AND model_id IS NULL
            AND input_digest IS NULL
            AND human_confirmation IS NULL)
        OR
        (task_kind = 'NON_DETERMINISTIC'
            AND model_id IS NOT NULL
            AND input_digest IS NOT NULL
            AND human_confirmation IS NOT NULL)
    ),
    CONSTRAINT platform_task_confirmation_completion CHECK (
        task_state <> 'COMPLETED'
        OR task_kind <> 'NON_DETERMINISTIC'
        OR human_confirmation = 'CONFIRMED'
    )
);

CREATE TABLE outbox_event (
    event_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    event_name VARCHAR(128) NOT NULL,
    event_body VARCHAR(4000) NOT NULL,
    event_state VARCHAR(32) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    attempt_count INT NOT NULL,
    failure_reason VARCHAR(512),
    occurred_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP,
    PRIMARY KEY (event_id)
);

CREATE TABLE dead_letter (
    dead_letter_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    origin_kind VARCHAR(32) NOT NULL,
    origin_id VARCHAR(64) NOT NULL,
    failure_reason VARCHAR(512) NOT NULL,
    recorded_at TIMESTAMP NOT NULL,
    PRIMARY KEY (dead_letter_id)
);

CREATE TABLE idempotency_claim (
    tenant_id VARCHAR(64) NOT NULL,
    idempotency_token VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    claimed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, idempotency_token)
);
