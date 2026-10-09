# Lifecycle · ID · State rules / 生命周期 · ID · 状态

Per O1 frozen concept. Implementation enums land in O2; names here are normative.  
按 O1 冻结概念。实现枚举在 O2；此处名称具规范效力。

## Shared ID rules / 共用 ID 规则

| Rule | Detail |
| --- | --- |
| Format | `VARCHAR(64)` non-blank; trim on write; case-sensitive |
| Stability | IDs are immutable after create; rename changes `*_name` only |
| Generation | Opaque string (caller- or system-assigned); no sequential dependency across tenants |
| Cross-ref | Foreign keys always store the target concept’s primary id — never a display name |

## Subject

| | |
| --- | --- |
| **Exists without** | Organization, Tenant (identity binding to a tenant is `subject_identity`, out of O1 freeze scope but already real) |
| **Lifecycle** | Create → ACTIVE → (SUSPENDED \| DISABLED) → no hard-delete in v1 (soft state only) |
| **ID** | `subject_id` PK (existing V1) |
| **State** | Align with existing platform usage; O1 does not add Position / dual-role fields |
| **Auth** | Subject alone does not grant org scope or tenant access |

## Organization

| | |
| --- | --- |
| **Exists without** | Tenant (yes); Subject (yes) |
| **Lifecycle** | Create → ACTIVE → SUSPENDED → (optional DISABLED). Children via OrganizationRelation, not embedded parent |
| **ID** | `organization_id` PK — **global within the platform DB**, not `(tenant_id, …)` |
| **State** | `ACTIVE` \| `SUSPENDED` \| `DISABLED` |
| **Meaning** | Real-world / business org. Not `org_unit`, not a picker alias for tenant department |

## Tenant

| | |
| --- | --- |
| **Exists without** | Organization (yes) |
| **Lifecycle** | Existing V1/V7: ACTIVE / SUSPENDED; reserved `platform` immutable |
| **ID** | `tenant_id` PK (existing) |
| **State** | Existing `tenant_state` |
| **Auth** | Tenant grants (`operator_tenant_grant`) remain fail-closed; separate from TenantOrganization |

## Membership

| | |
| --- | --- |
| **Tuple** | `(subject_id, organization_id)` — **no `tenant_id`** |
| **Lifecycle** | Create → ACTIVE → ENDED (or DISABLED). No primary/secondary dual-role this round |
| **ID** | Composite PK; no separate membership_id required in O1 |
| **State** | `ACTIVE` \| `ENDED` \| `DISABLED` |
| **Forbids** | Permission strings; tenant semantics; auto-scope from “any membership” |

## OrganizationRelation

| | |
| --- | --- |
| **Tuple** | `(from_organization_id, to_organization_id, relation_kind)` |
| **First kind** | `CONTAINS` — parent contains child (tree/DAG; **no cycles**) |
| **Lifecycle** | Create → ACTIVE → ENDED |
| **ID** | Composite PK |
| **State** | `ACTIVE` \| `ENDED` |
| **Invariants** | `from ≠ to`; CONTAINS insert must reject cycles; inactive edges excluded from descendant closure |

## TenantOrganization

| | |
| --- | --- |
| **Tuple** | `(tenant_id, organization_id)` |
| **Lifecycle** | Create → ACTIVE → ENDED |
| **ID** | Composite PK |
| **State** | `ACTIVE` \| `ENDED` |
| **Meaning** | Org participates in Tenant’s digital space |
| **Forbids** | Implying named permissions; implying Membership; auto-merging orgs that share a name across tenants |

## OrgScope modes (authorization layer) / 组织范围模式（授权层）

Derived at Context/AuthZ time from Membership + OrganizationRelation (+ explicit grants). **Not** stored on Membership.

| Mode | Meaning |
| --- | --- |
| `NONE` | Explicit empty scope — **deny** org-scoped resources (fail-closed) |
| `UNRESTRICTED` | Explicit platform/break-glass or policy grant — not the default for missing data |
| `SELF` | Only organizations where subject has ACTIVE Membership |
| `SELF_AND_DESCENDANTS` | SELF plus CONTAINS closure |
| `EXPLICIT` | Caller- or policy-supplied organization id set |

**Missing / unspecified scope on an org-scoped check = fail-closed** (deny `org_scope_missing`, never silently `UNRESTRICTED`). O4 implements all five modes on `OrgScope`; `JdbcOrgDirectory.resolveSelf` / `resolveSelfAndDescendants` derive from Membership + OrganizationRelation (legacy dual-read when not fully backfilled). Empty ACTIVE memberships → `NONE`.
