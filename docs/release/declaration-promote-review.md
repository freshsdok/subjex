# Declaration promote review and rollback / 声明晋升评审与回滚（P7）

## Dual approve / 双人确认

1. Operator A (`declaration.promote`): `POST /api/v1/declarations/{kind}/{key}/promote/approvals?tenantId=`
2. Operator B ≠ A (same permission): `POST .../promote` with body `{"approvalId":"...","revision":N?}`.

Self-confirm is rejected (`DeclarationPromoteNeedsSecondOperator`).
禁止同一人确认。

Unfinished entity migrations still block promote (`DeclarationPromoteBlockedByMigration`).
未完成实体迁移仍阻断晋升。

## Rollback / 回滚

`POST /api/v1/declarations/{kind}/{key}/rollback?tenantId=`

- Switches effective tip to the **previous PROMOTED** revision (marks current tip `SUPERSEDED`).
- Rewrites internal git YAML to that revision.
- **Never** auto DROP columns. Schema rollback remains a human compensating migration.
- Needs at least two promote history rows.

绝不自动删列。
