-- Payment lifecycle clocks: pending expiry and success/cancel timestamps (ADR 0018 follow-up).
-- 支付生命周期时钟：待支付过期，以及成功/取消时间（ADR 0018 后续）。
-- PENDING unpaid orders expire 30 minutes after created_at (persisted as expires_at on insert).
-- 待支付单据自 created_at 起 30 分钟过期（创单时写入 expires_at）。
-- Successful payments stay refundable for 7 days after succeeded_at.
-- 支付成功后，自 succeeded_at 起 7 日内可退款。
-- No vendor-only types; no medical / settlement columns. Existing rows leave the new
-- columns NULL; the service derives expires_at from created_at and succeeded_at from
-- the PAYMENT_SUCCEEDED voucher when a value was not stored.
-- 无厂商专有类型，无医疗/结算列。已有行新列为空；服务在未落库时用 created_at
-- 推导过期时间，用 PAYMENT_SUCCEEDED 凭证时间推导成功时间。
-- Shared by MySQL 8.4 and PostgreSQL 16 style.
-- MySQL 8.4 与 PostgreSQL 16 共用风格。

ALTER TABLE payment ADD expires_at TIMESTAMP;
ALTER TABLE payment ADD succeeded_at TIMESTAMP;
ALTER TABLE payment ADD cancelled_at TIMESTAMP;

CREATE INDEX payment_pending_expires
    ON payment (payment_status, expires_at);
