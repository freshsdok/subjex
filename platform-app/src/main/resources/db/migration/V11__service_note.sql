-- Promoted from entity-declare migration-draft/V1__service_note.sql (post-stage step 3).
-- 由 entity-declare 草稿 V1__service_note.sql 迁入（阶段后第 3 项）。
-- Shared by MySQL 8.4 and PostgreSQL 16 style (no vendor-only types).
-- MySQL 8.4 与 PostgreSQL 16 共用风格（无厂商专有类型）。

CREATE TABLE service_note (
  note_id VARCHAR(64) NOT NULL,
  title VARCHAR(200) NOT NULL,
  body VARCHAR(4000),
  priority INTEGER,
  PRIMARY KEY (note_id)
);
