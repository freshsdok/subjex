-- Shared outbox delivery circuit breaker state (Scale-4c).
-- 共享出箱投递熔断状态（Scale-4c）。
-- One row per destination key; opened_at NULL = closed, non-null = tripped (open or half-open).
-- 每个目的键一行；opened_at 空为关闭，非空为已跳闸（打开或半开）。
-- Shared by MySQL 8.4 and PostgreSQL 16 style.
-- MySQL 8.4 与 PostgreSQL 16 共用风格。

CREATE TABLE delivery_circuit_breaker (
    destination_key VARCHAR(256) NOT NULL,
    consecutive_failures INT NOT NULL,
    opened_at TIMESTAMP,
    PRIMARY KEY (destination_key)
);
