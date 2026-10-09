-- O3: side map legacy org_unit → Organization (no cross-tenant merge).
-- O3：旧 org_unit → Organization 旁路映射（禁止跨租户合并）。
-- organization_id stays VARCHAR(64): reuse org_unit_id when globally unique, else SHA-256 hex.
-- organization_id 仍为 VARCHAR(64)：全局唯一则复用 org_unit_id，否则 SHA-256 十六进制。
-- Legacy org_unit / org_membership tables remain; do not DROP (O7 only).
-- 旧表保留；禁止 DROP（仅 O7）。

CREATE TABLE org_unit_organization_map (
    tenant_id VARCHAR(64) NOT NULL,
    org_unit_id VARCHAR(64) NOT NULL,
    organization_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, org_unit_id)
);

-- One Organization row is produced per backfilled unit; id must not be shared across mapped units.
-- 每个回填单元对应一个 Organization；映射侧 organization_id 不共享。
CREATE UNIQUE INDEX uk_org_unit_organization_map_organization
    ON org_unit_organization_map (organization_id);
