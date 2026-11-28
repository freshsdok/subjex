-- Platform-generic points / benefits ledger (tenant-scoped, subject-addressed).
-- 平台通用积分/权益台账（按租户、按主体）。
-- Append-only history plus a transactional balance projection. No earning rules, catalog, coupons, or expiry job.
-- 只追加历史，并用事务内余额投影。无自动规则、兑换目录、券或过期任务。
-- Reserved tenant platform is never a business home. Shared by MySQL 8.4 and PostgreSQL 16 style.
-- 保留租户 platform 不是业务归属。MySQL 8.4 与 PostgreSQL 16 共用风格。

CREATE TABLE points_account (
    tenant_id VARCHAR(64) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    balance BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, subject_id),
    CONSTRAINT points_account_balance_non_negative CHECK (balance >= 0)
);

CREATE TABLE points_ledger (
    entry_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    subject_id VARCHAR(64) NOT NULL,
    delta BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    reason VARCHAR(256) NOT NULL,
    memo VARCHAR(256),
    reference_type VARCHAR(64),
    reference_id VARCHAR(128),
    actor_subject_id VARCHAR(64),
    actor_kind VARCHAR(32) NOT NULL,
    idempotency_token VARCHAR(128),
    idempotency_fingerprint VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (entry_id),
    CONSTRAINT points_ledger_delta_nonzero CHECK (delta <> 0),
    CONSTRAINT points_ledger_balance_non_negative CHECK (balance_after >= 0)
);

CREATE INDEX points_ledger_subject_created
    ON points_ledger (tenant_id, subject_id, created_at);

-- Unique when a token is present. Multiple NULL tokens must remain allowed
-- (MySQL / PostgreSQL unique-null semantics; H2 compatibility modes follow the same idea).
-- 仅在幂等令牌出现时唯一。多个 NULL 令牌必须仍被允许。
CREATE UNIQUE INDEX points_ledger_tenant_idempotency
    ON points_ledger (tenant_id, idempotency_token);

INSERT INTO platform_permission (permission_name, permission_summary) VALUES
    ('points.read', 'Read a subject points balance and history in a granted tenant / 在已授权租户内查阅主体积分余额与历史'),
    ('points.adjust', 'Grant or deduct points for a subject in a granted tenant / 在已授权租户内为主体发放或扣减积分');

INSERT INTO role_permission (role_name, permission_name) VALUES
    ('platform-operator', 'points.read'),
    ('platform-operator', 'points.adjust'),
    ('platform-reader', 'points.read');
