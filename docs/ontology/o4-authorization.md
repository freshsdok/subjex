# O4 Authorization — OrgScope from Membership + OrganizationRelation

**Status:** Implemented. Gates AUTH-01..05 in `OrgAuthorizationGatesTest`.

## Modes

| Mode | Source |
| --- | --- |
| `NONE` | No ACTIVE membership (or explicit empty) — deny org-scoped resources |
| `UNRESTRICTED` | Explicit grant only — never implied by missing data |
| `SELF` | ACTIVE Membership orgs in tenant (`resolveSelf`) |
| `SELF_AND_DESCENDANTS` | SELF + ACTIVE CONTAINS closure (`resolveSelfAndDescendants`) |
| `EXPLICIT` | Caller/policy-supplied id set |

## Fail-closed

- `AccessChecker` / `SqlRbacPolicyEngine`: resource org id + null scope → `org_scope_missing`
- `NONE` → `org_out_of_scope`; `UNRESTRICTED` allows any non-blank org id
- Context tenant ≠ resource `tenantId` attribute → `tenant_mismatch`
- Relationship (Membership / TenantOrganization / OrganizationRelation) alone never grants permission (AUTH-01)

## Dual-read

Unchanged from O3: prefer ontology when tenant fully backfilled; legacy `org_unit` / `org_membership` otherwise. Descendant expansion cannot escape the CONTAINS graph or the tenant map (AUTH-05).

## Non-goals

No Zanzibar; no full Cedar/Casbin rewrite beyond existing `PolicyEngine` port.
