-- Outbox DLQ polish: mark discarded dead letters without deleting history.
-- 出箱死信打磨：丢弃标记，保留历史。
-- Shared MySQL/PostgreSQL/H2: nullable TIMESTAMP.

ALTER TABLE dead_letter ADD COLUMN discarded_at TIMESTAMP NULL;
