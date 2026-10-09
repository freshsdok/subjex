-- O2: world-model Organization ontology (alongside legacy org_unit until O7).
-- O2：现实侧 Organization 本体（与旧 org_unit 并存至 O7）。
-- Shared MySQL 8.4 / PostgreSQL 16 / H2 dual-MODE style: no vendor-only types; no FK constraints
-- (integrity enforced in JdbcOrganizationStore — same pattern as V13 org_unit).
-- MySQL/PostgreSQL/H2 共用：无厂商专有类型；不加外键（完整性由 JdbcOrganizationStore 约束，同 V13）。

-- Organization: global within the platform DB; NO tenant_id column.
-- Organization：平台库内全局；无 tenant_id 列。
CREATE TABLE organization (
    organization_id VARCHAR(64) NOT NULL,
    organization_name VARCHAR(200) NOT NULL,
    organization_state VARCHAR(32) NOT NULL,
    PRIMARY KEY (organization_id)
);

-- Membership: Subject ∈ Organization; NO tenant_id; does NOT grant permission.
-- Membership：主体属于组织；无 tenant_id；不授权限。
CREATE TABLE membership (
    subject_id VARCHAR(64) NOT NULL,
    organization_id VARCHAR(64) NOT NULL,
    membership_state VARCHAR(32) NOT NULL,
    PRIMARY KEY (subject_id, organization_id)
);

-- OrganizationRelation: directed org→org edges. First kind: CONTAINS (acyclic).
-- OrganizationRelation：组织间有向边。首种：CONTAINS（无环）。
CREATE TABLE organization_relation (
    from_organization_id VARCHAR(64) NOT NULL,
    to_organization_id VARCHAR(64) NOT NULL,
    relation_kind VARCHAR(32) NOT NULL,
    relation_state VARCHAR(32) NOT NULL,
    PRIMARY KEY (from_organization_id, to_organization_id, relation_kind)
);

-- TenantOrganization: Tenant ↔ Organization M:N; link ≠ authorization.
-- TenantOrganization：租户↔组织多对多；关联≠授权。
CREATE TABLE tenant_organization (
    tenant_id VARCHAR(64) NOT NULL,
    organization_id VARCHAR(64) NOT NULL,
    link_state VARCHAR(32) NOT NULL,
    PRIMARY KEY (tenant_id, organization_id)
);
