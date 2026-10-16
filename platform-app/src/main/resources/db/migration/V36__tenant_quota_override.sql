-- TN-1: per-tenant quota overrides (null column = use global default).
-- TN-1：按租户配额覆盖（列为空则用全局默认）。

CREATE TABLE tenant_quota_override (
    tenant_id VARCHAR(64) NOT NULL,
    daily_submit_limit INT,
    storage_row_limit INT,
    updated_at TIMESTAMP NOT NULL,
    updated_by_identity_id VARCHAR(64),
    PRIMARY KEY (tenant_id)
);
