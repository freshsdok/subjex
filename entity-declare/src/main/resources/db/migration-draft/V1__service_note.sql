-- Draft from entity service-note — 由实体 service-note 生成的草稿
-- Human-editable. Not applied by platform-app Flyway until moved into its migrations.
-- 可人工编辑。在移入 platform-app 迁移目录之前不会被应用。
-- Shared by MySQL 8.4 and PostgreSQL 16 style (no vendor-only types).
-- MySQL 8.4 与 PostgreSQL 16 共用风格（无厂商专有类型）。

CREATE TABLE service_note (
  note_id VARCHAR(64) NOT NULL,
  title VARCHAR(200) NOT NULL,
  body VARCHAR(4000),
  priority INTEGER,
  PRIMARY KEY (note_id)
);
