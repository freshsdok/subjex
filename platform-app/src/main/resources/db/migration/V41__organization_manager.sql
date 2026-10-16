-- Should S6: primary department manager (Subject), not OrganizationRelation (org→org only).
-- Should S6：部门主管（主体）；不用 OrganizationRelation（仅 org→org）。
-- Shared MySQL/PostgreSQL/H2: no FK; integrity in JdbcOrganizationStore.

CREATE TABLE organization_manager (
    organization_id VARCHAR(64) NOT NULL,
    manager_subject_id VARCHAR(64) NOT NULL,
    link_state VARCHAR(32) NOT NULL,
    PRIMARY KEY (organization_id)
);
