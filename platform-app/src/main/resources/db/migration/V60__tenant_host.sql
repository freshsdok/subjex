-- Tenant custom hostnames for Host-header routing (ops first cut).
-- 租户自定义主机名，按请求 Host 头解析（运营管理首切口）。
-- This table only records the binding. It does not provision public DNS or TLS certificates.
-- 本表只登记绑定。不办理公网 DNS，也不签发证书。
-- Shared by MySQL 8.4 and PostgreSQL 16 style (no vendor-only types, no FKs).
-- MySQL 8.4 与 PostgreSQL 16 共用风格（无厂商专有类型，不加外键）。
-- host_name is stored lowercase, without a port, scheme, or path, and is unique across tenants.
-- host_name 存小写、不含端口/协议/路径，全库唯一。

CREATE TABLE tenant_host (
    host_name VARCHAR(253) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    created_by_identity_id VARCHAR(64),
    PRIMARY KEY (host_name)
);

CREATE INDEX tenant_host_tenant ON tenant_host (tenant_id);
