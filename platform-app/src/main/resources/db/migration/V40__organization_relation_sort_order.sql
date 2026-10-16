-- Should S2: sibling order on OrganizationRelation (CONTAINS).
-- Should S2：组织关系兄弟排序。
-- Shared MySQL/PostgreSQL/H2: INTEGER NOT NULL DEFAULT 0.

ALTER TABLE organization_relation ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;
