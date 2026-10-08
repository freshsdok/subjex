-- Promoted from entity-declare migration-draft/V1__demo_ticket.sql (Z1-1).
-- 由 entity-declare 草稿 V1__demo_ticket.sql 迁入（Z1-1）。
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
