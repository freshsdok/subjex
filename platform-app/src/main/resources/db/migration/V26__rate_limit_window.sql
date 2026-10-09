-- Shared fixed-window rate-limit counters (Scale-4b).
-- 共享固定时间窗限流计数（Scale-4b）。
-- One row per tenant/action bucket; processes sharing the DB share one budget.
-- 每个租户/动作桶一行；共用库的进程共用一份额度。
-- Shared by MySQL 8.4 and PostgreSQL 16 style.
-- MySQL 8.4 与 PostgreSQL 16 共用风格。

CREATE TABLE rate_limit_window (
    bucket_key VARCHAR(512) NOT NULL,
    window_started_at TIMESTAMP NOT NULL,
    permit_count INT NOT NULL,
    PRIMARY KEY (bucket_key)
);
