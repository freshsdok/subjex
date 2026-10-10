-- ES-2 / ADR 0002: entity attachment / blob metadata (bytes live in ObjectStorage).
-- ES-2 / ADR 0002：实体附件/blob 元数据（字节在 ObjectStorage，禁止塞进 attrs）。
-- Dual-mode friendly VARCHAR columns (same pattern as entity_record / form_submission).

CREATE TABLE entity_blob (
  blob_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL,
  entity_key VARCHAR(128) NOT NULL,
  record_id VARCHAR(128) NOT NULL,
  field_name VARCHAR(128) NOT NULL,
  content_type VARCHAR(128) NOT NULL,
  byte_size INT NOT NULL,
  storage_key VARCHAR(256) NOT NULL,
  checksum_sha256 VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (blob_id)
);

CREATE INDEX entity_blob_tenant_entity_record
  ON entity_blob (tenant_id, entity_key, record_id);

CREATE INDEX entity_blob_storage_key
  ON entity_blob (tenant_id, storage_key);
