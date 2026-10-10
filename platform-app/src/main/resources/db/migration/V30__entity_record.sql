-- ES-1 / ADR 0002: shared hybrid entity row store (core columns + attrs JSON text).
-- ES-1 / ADR 0002：共享混合实体行表（核心列 + attrs JSON 文本）。
-- Dual-mode (H2 MySQL/PostgreSQL): attrs is VARCHAR JSON text, same pattern as form_submission.values_json.
-- 双模式：attrs 为 VARCHAR JSON 文本（与 form_submission.values_json 一致）；原生 jsonb 留给后续优化。
-- Physical per-entity tables remain the optional track (storageMode=table).
-- 一实体一物理表仍为可选轨（storageMode=table）。

CREATE TABLE entity_record (
  tenant_id VARCHAR(64) NOT NULL,
  entity_key VARCHAR(128) NOT NULL,
  record_id VARCHAR(128) NOT NULL,
  record_state VARCHAR(32) NOT NULL,
  attrs VARCHAR(16000) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  PRIMARY KEY (tenant_id, entity_key, record_id)
);

CREATE INDEX entity_record_tenant_entity_updated
  ON entity_record (tenant_id, entity_key, updated_at);

CREATE INDEX entity_record_entity_record
  ON entity_record (entity_key, record_id);
