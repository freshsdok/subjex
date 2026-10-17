-- IM-A1 / ADR 0009: inbox attachments + mailing groups + broadcast permissions.
-- IM-A1 / ADR 0009：站内信附件、收件组与群发权限。
-- Shared MySQL/PostgreSQL/H2: VARCHAR + TIMESTAMP; bytes live in ObjectStorage (not entity_blob).
-- 字节在 ObjectStorage；不复用 entity_blob。

CREATE TABLE inbox_attachment (
    attachment_id VARCHAR(64) NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    file_name VARCHAR(512) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    byte_size INT NOT NULL,
    storage_key VARCHAR(256) NOT NULL,
    checksum_sha256 VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (attachment_id)
);

CREATE INDEX inbox_attachment_message_sort
    ON inbox_attachment (message_id, sort_order);

CREATE INDEX inbox_attachment_tenant_storage
    ON inbox_attachment (tenant_id, storage_key);

CREATE TABLE inbox_group (
    group_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(256) NOT NULL,
    description VARCHAR(1024),
    realm VARCHAR(16) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (group_id)
);

CREATE INDEX inbox_group_tenant_realm_name
    ON inbox_group (tenant_id, realm, name);

CREATE TABLE inbox_group_member (
    group_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    member_realm VARCHAR(16) NOT NULL,
    member_subject_id VARCHAR(64) NOT NULL,
    added_at TIMESTAMP NOT NULL,
    PRIMARY KEY (group_id, member_realm, member_subject_id)
);

CREATE INDEX inbox_group_member_tenant_subject
    ON inbox_group_member (tenant_id, member_realm, member_subject_id);

INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('inbox.broadcast', 'Send ORG_BROADCAST / GROUP_SEND within tenant grant / 租户内组织或站内信组群发'),
    ('inbox.group.admin', 'Create and manage inbox mailing groups / 管理站内信收件组');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'inbox.broadcast'),
    ('platform-operator', 'inbox.group.admin');
