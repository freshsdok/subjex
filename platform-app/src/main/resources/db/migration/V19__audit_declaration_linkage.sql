-- Versioned audit linkage for form audit.write (RT-6).
-- 表单 audit.write 的版本化审计关联（RT-6）。
-- Optional columns: entity key, declaration version, effective resolution source.
-- 可选列：实体键、声明版本、生效解析来源。

ALTER TABLE audit_entry ADD COLUMN entity_key VARCHAR(128);
ALTER TABLE audit_entry ADD COLUMN declaration_version INT;
ALTER TABLE audit_entry ADD COLUMN resolution_source VARCHAR(32);
