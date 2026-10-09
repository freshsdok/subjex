-- O8-5: rename demo_ticket.org_unit → organization to match entity field organization.
-- O8-5：demo_ticket.org_unit 重命名为 organization，与实体字段 organization 对齐。
-- Does not edit executed V12; additive rename only.
-- 不改已执行的 V12；仅追加重命名。

ALTER TABLE demo_ticket RENAME COLUMN org_unit TO organization;
