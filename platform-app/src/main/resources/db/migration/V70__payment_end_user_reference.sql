-- End-user generic create: structured business reference tokens and an optional display memo.
-- 终端通用创单：结构化业务引用令牌与可选展示备注。
-- reference_type / reference_id are tokens, not a closed catalog (same idea as resource_event).
-- reference_type / reference_id 是令牌，不是封闭目录（与 resource_event 同一思路）。
-- No SKU, cart, or industry checkout columns. Shared by MySQL 8.4 and PostgreSQL 16 style.
-- 无货品目录、购物车或行业结账列。MySQL 8.4 与 PostgreSQL 16 共用风格。

ALTER TABLE payment ADD reference_type VARCHAR(64);
ALTER TABLE payment ADD reference_id VARCHAR(128);
ALTER TABLE payment ADD display_memo VARCHAR(256);
