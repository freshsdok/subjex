-- IM-1: in-app mailbox tables + operator permissions (ADR 0008).
-- IM-1：站内信表与操作员权限（ADR 0008）。
-- Shared MySQL/PostgreSQL/H2: VARCHAR + TIMESTAMP; no vendor-specific types.
-- ORG_BROADCAST is schema placeholder only (no API until IM-7).
-- ORG_BROADCAST 仅枚举占位（IM-7 前无 API）。

CREATE TABLE inbox_message (
    message_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    title VARCHAR(512) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    producer_kind VARCHAR(64) NOT NULL,
    producer_id VARCHAR(128),
    ref_kind VARCHAR(64),
    ref_id VARCHAR(128),
    entity_key VARCHAR(128),
    declaration_version INT,
    deep_link VARCHAR(512),
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (message_id)
);

CREATE INDEX inbox_message_tenant_created
    ON inbox_message (tenant_id, created_at);

CREATE TABLE inbox_entry (
    entry_id VARCHAR(64) NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    recipient_realm VARCHAR(16) NOT NULL,
    recipient_subject_id VARCHAR(64) NOT NULL,
    read_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (entry_id)
);

CREATE UNIQUE INDEX inbox_entry_message_recipient_uq
    ON inbox_entry (message_id, recipient_realm, recipient_subject_id);

CREATE INDEX inbox_entry_recipient_mailbox
    ON inbox_entry (tenant_id, recipient_realm, recipient_subject_id, read_at, created_at);

INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('inbox.read', 'Read own in-app mailbox entries / 读取本人站内信'),
    ('inbox.send.system', 'Send SYSTEM notices within tenant grant / 在租户授权内发送系统站内信');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'inbox.read'),
    ('platform-operator', 'inbox.send.system'),
    ('platform-reader', 'inbox.read');
