-- Draft from entity service-note — 由实体 service-note 生成的草稿
-- Human-editable. Promoted into platform-app Flyway as V11__service_note.sql (post-stage step 3).
-- 可人工编辑。已迁入 platform-app Flyway：V11__service_note.sql（阶段后第 3 项）。
-- Keep regenerating here; copy/adapt into platform-app/db/migration when the table changes.
-- 继续在此重生；表变更时再复制/改编进 platform-app/db/migration。
-- Shared by MySQL 8.4 and PostgreSQL 16 style (no vendor-only types).
-- MySQL 8.4 与 PostgreSQL 16 共用风格（无厂商专有类型）。

CREATE TABLE service_note (
  note_id VARCHAR(64) NOT NULL,
  title VARCHAR(200) NOT NULL,
  body VARCHAR(4000),
  priority INTEGER,
  PRIMARY KEY (note_id)
);
