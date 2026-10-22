# PolicyEngine caller inventory (AuthZ-1e/1f/1g) / PolicyEngine 调用盘点

Date: 2026-10-22 Asia/Shanghai. After AuthZ-1d (`platform.authz.engine=cedar` default).

**Why:** Cedar ADR residual — declaration paths used to call `AccessChecker` / `DeclarationAccess.require` directly. 1e inventoried + Pages; 1f Forms/Entity/Blob; **1g** entity upsert domain action.

**为何：** 声明路径曾直调 AccessChecker。1e Pages；1f 表单/实体/附件；**1g** 实体 upsert 领域动作。

## Already on PolicyEngine / 已走端口

| Caller | Notes |
| --- | --- |
| `OrganizationApiEndpoint` | Membership write + org-scope via `policyEngine.require` |
| `org.legacy.OrgApiEndpoint` | Membership write via `policyEngine.require` |
| `PlatformWiring.policyEngine` | Factory: default `cedar`, escape `sql` |
| `SqlRbacPolicyEngine` / `CedarPolicyEngine` | Adapters; both may call `AccessChecker` for tenant/org after permission |
| **`PagesApiEndpoint` detail** | **`requireVia` (1e)** |
| **`FormsApiEndpoint` detail** | **`requireVia` (1f)** |
| **`FormSubmissionEndpoint` submit+history** | **`requireVia` (1f)** |
| **`GenericEntityEndpoint` requireEntity** | **`requireVia` (1f)** |
| **`EntityBlobEndpoint` requireContext** | **`requireVia` (1f)** |
| **`EntityRecordUpsertDomainActionHandler`** | **`requireVia` (1g)** — entity.permission + tenantScoped |

## Remaining / 仍余

None for declaration CRUD / domain-action AuthZ widen. `DeclarationAccess.require` / `requireTenantWhenScoped` remain as compatibility helpers (no production declaration callers left on them after 1g).

声明 CRUD / 领域动作 AuthZ 拓宽已清空。`require` / `requireTenantWhenScoped` 仍作兼容辅助（1g 后生产声明调用方已不直调）。

## Non-goals this inventory / 本盘点非目标

- Online Cedar policy editor (still forbidden; UI Manifest editor = ADR 0010 carve-out)
- Casbin
- Replacing named `OperatorPermission` strings
- Migrating every Spring Security matcher onto Cedar

## Next / 下一步

AuthZ declaration-path widen **closed**. Optional later: delete unused `require` call sites in tests only; other pre-alpha tracks.

## Related

- ADR: [`cedar-or-casbin-adr.md`](cedar-or-casbin-adr.md)
- Dual-run: `PolicyEngineDualRunGatesTest`
