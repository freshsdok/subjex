-- Draft from entity demo-ticket — 由实体 demo-ticket 生成的草稿
-- Human-editable. Promoted into platform-app Flyway as V12__demo_ticket.sql (Z1-1).
-- 可人工编辑。已迁入 platform-app Flyway：V12__demo_ticket.sql（Z1-1）。
-- Keep regenerating here; copy/adapt into platform-app/db/migration when the table changes.
-- 继续在此重生；表变更时再复制/改编进 platform-app/db/migration。
-- Shared by MySQL 8.4 and PostgreSQL 16 style (no vendor-only types).
-- MySQL 8.4 与 PostgreSQL 16 共用风格（无厂商专有类型）。

CREATE TABLE demo_ticket (
  ticket_id VARCHAR(64) NOT NULL,
  title VARCHAR(200) NOT NULL,
  status VARCHAR(32) NOT NULL,
  assignee VARCHAR(64),
  org_unit VARCHAR(64),
  PRIMARY KEY (ticket_id)
);
