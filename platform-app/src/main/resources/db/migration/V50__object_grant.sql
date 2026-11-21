-- WP3-1: authorized-object registry and grants (ADR 0015).
-- WP3-1：授权对象登记与授权行（ADR 0015）。
-- Authorization sits above ObjectStorage; this is not a change to local disk / S3 adapters.
-- 授权在对象存储端口之上，不修改本地盘 / S3 适配器。
-- Owner is recorded only for objects registered on this track. Existing entity_blob
-- rows are not backfilled (no silent owner that could open others' files).
-- 只为在本轨登记的对象记录所有者。不回填已有 entity_blob，以免静默开放他人文件。
-- Grants are tenant-scoped to a subject or an organization; membership alone is not authorization.
-- 授权限定在租户内，对象为新主体或组织；仅有成员关系不是授权。
-- Reserved tenant "platform" is not a default home. No medical / document-type columns.
-- 保留租户 platform 不是默认归属。无医疗/文档类型列。
-- Shared by MySQL 8.4 and PostgreSQL 16 style (no vendor-only types, no FKs).
-- MySQL 8.4 与 PostgreSQL 16 共用风格（无厂商专有类型，不加外键）。

CREATE TABLE object_descriptor (
    object_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    object_key VARCHAR(256) NOT NULL,
    owner_subject_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (object_id)
);

CREATE UNIQUE INDEX object_descriptor_tenant_key
    ON object_descriptor (tenant_id, object_key);

CREATE INDEX object_descriptor_owner
    ON object_descriptor (tenant_id, owner_subject_id);

CREATE TABLE object_grant (
    grant_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    object_key VARCHAR(256) NOT NULL,
    grantee_kind VARCHAR(32) NOT NULL,
    grantee_id VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    created_by_subject_id VARCHAR(64),
    PRIMARY KEY (grant_id)
);

CREATE UNIQUE INDEX object_grant_tenant_key_grantee
    ON object_grant (tenant_id, object_key, grantee_kind, grantee_id);

CREATE INDEX object_grant_tenant_key
    ON object_grant (tenant_id, object_key);
