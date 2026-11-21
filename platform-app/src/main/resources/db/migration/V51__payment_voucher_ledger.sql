-- WP6-1: payment / refund / voucher ledger (ADR 0018).
-- WP6-1：支付单 / 退款 / 凭证台账（ADR 0018）。
-- Platform-side facts only: no commission, split-accounting, tax, or settlement columns.
-- 只记平台侧事实：无分润、分账、税务或结算列。
-- Amounts are non-negative BIGINT minor units plus an explicit ISO 4217 CHAR(3) currency; no default currency.
-- 金额为非负 BIGINT 最小货币单位 + 显式 ISO 4217 CHAR(3) 币种；不设默认币种。
-- Refund rows must use the same currency as the payment and must not exceed the remaining refundable amount.
-- That currency/remaining cap is a documented service rule, enforced when refund apply lands in WP6-2.
-- Schema already checks payment.refunded_amount_minor <= amount_minor and refund.amount_minor > 0.
-- 退款必须与原单同币种且不得超过剩余可退金额。该币种/余额上限为已记录的服务规则，退款入账在 WP6-2 强制。
-- 表级已检查 payment.refunded_amount_minor <= amount_minor 以及 refund.amount_minor > 0。
-- Idempotency-key columns live on this aggregate; do not reuse idempotency_claim.task_id
-- or TaskMessagePort.submit, and do not add a second outbox table.
-- 幂等键列落在本聚合上；不占用 idempotency_claim.task_id，不走 TaskMessagePort.submit，也不另起出箱表。
-- Shared by MySQL 8.4 and PostgreSQL 16 style (no vendor-only types, no FKs).
-- MySQL 8.4 与 PostgreSQL 16 共用风格（无厂商专有类型，不加外键）。

CREATE TABLE payment (
    payment_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    refunded_amount_minor BIGINT NOT NULL,
    payment_status VARCHAR(32) NOT NULL,
    domain_reference VARCHAR(256),
    payer_subject_id VARCHAR(64),
    payer_realm VARCHAR(32),
    provider_id VARCHAR(64) NOT NULL,
    provider_reference VARCHAR(128),
    create_idempotency_token VARCHAR(128),
    create_idempotency_fingerprint VARCHAR(128),
    callback_notification_id VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (payment_id),
    CONSTRAINT payment_amount_non_negative CHECK (amount_minor >= 0),
    CONSTRAINT payment_refunded_non_negative CHECK (refunded_amount_minor >= 0),
    CONSTRAINT payment_refunded_not_over_amount CHECK (refunded_amount_minor <= amount_minor)
);

CREATE INDEX payment_tenant_created
    ON payment (tenant_id, created_at);

-- Unique when a create token is present. Multiple NULL tokens must remain allowed
-- (MySQL / PostgreSQL unique-null semantics; H2 compatibility modes follow the same idea).
-- 仅在创单令牌出现时唯一。多个 NULL 令牌必须仍被允许
-- （MySQL / PostgreSQL 的 UNIQUE NULL 语义；H2 兼容模式按同一思路处理）。
CREATE UNIQUE INDEX payment_tenant_create_idempotency
    ON payment (tenant_id, create_idempotency_token);

CREATE TABLE payment_refund (
    refund_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    payment_id VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    refund_status VARCHAR(32) NOT NULL,
    refund_idempotency_token VARCHAR(128),
    refund_idempotency_fingerprint VARCHAR(128),
    provider_refund_id VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (refund_id),
    CONSTRAINT payment_refund_amount_positive CHECK (amount_minor > 0)
);

CREATE INDEX payment_refund_payment
    ON payment_refund (tenant_id, payment_id);

CREATE UNIQUE INDEX payment_refund_tenant_idempotency
    ON payment_refund (tenant_id, refund_idempotency_token);

CREATE TABLE payment_voucher (
    voucher_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    payment_id VARCHAR(64) NOT NULL,
    refund_id VARCHAR(64),
    fact_kind VARCHAR(32) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    resulting_status VARCHAR(32) NOT NULL,
    provider_id VARCHAR(64) NOT NULL,
    provider_reference VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (voucher_id),
    CONSTRAINT payment_voucher_amount_non_negative CHECK (amount_minor >= 0)
);

CREATE INDEX payment_voucher_payment
    ON payment_voucher (tenant_id, payment_id, created_at);
