# O5 API / Console — Organization ontology surface

**Status:** Implemented. Legacy `/api/v1/org/**` kept (deprecated headers).  
**状态：** 已落地。旧 `/api/v1/org/**` 保留（响应带弃用头）。

## New API / 新接口

Base: `/api/v1/organizations` (`OrganizationApiEndpoint`)

| Method | Path | Permission | Notes |
| --- | --- | --- | --- |
| GET | `/api/v1/organizations?tenantId=` | `org.read` | Orgs with ACTIVE `TenantOrganization`; parent from ACTIVE CONTAINS |
| GET | `/api/v1/organizations/{organizationId}?tenantId=` | `org.read` | 404 if not linked to tenant; scope-filtered |
| PUT | `/api/v1/organizations/{organizationId}?tenantId=` | `org.write` + tenant grant | Upsert org + tenant link + CONTAINS parent; reverse write-through to legacy |
| POST | `/api/v1/organizations/{organizationId}/move/preview?tenantId=` | `org.write` + tenant grant | Preview CONTAINS reparent (wouldCycle, inScope, counts); no write |
| POST | `/api/v1/organizations/{organizationId}/move?tenantId=` | `org.write` + tenant grant | Apply CONTAINS reparent; cycle→400; out-of-scope→403; audit `org.organization.move` |
| POST | `/api/v1/organizations/moves/preview?tenantId=` | `org.write` + tenant grant | Batch move preview (S1) |
| POST | `/api/v1/organizations/moves?tenantId=` | `org.write` + tenant grant | Batch CONTAINS reparent (S1) |
| POST | `/api/v1/organizations/{organizationId}/children/order?tenantId=` | `org.write` + tenant grant | Reorder ACTIVE CONTAINS children (S2) |
| POST | `/api/v1/organizations/{organizationId}/merge/preview?tenantId=` | `org.write` + tenant grant | Preview merge into target (S5) |
| POST | `/api/v1/organizations/{organizationId}/merge?tenantId=` | `org.write` + tenant grant | Merge: rehang members/children, end source (S5) |
| POST | `/api/v1/organizations/{organizationId}/split/preview?tenantId=` | `org.write` + tenant grant | Preview split (S5) |
| POST | `/api/v1/organizations/{organizationId}/split?tenantId=` | `org.write` + tenant grant | Split children/members into new org (S5) |
| PUT | `/api/v1/organizations/{organizationId}/manager?tenantId=` | `org.write` + tenant grant | Set department manager; ensures Membership (S6) |
| DELETE | `/api/v1/organizations/{organizationId}/manager?tenantId=` | `org.write` + tenant grant | Clear department manager (S6) |
| GET | `/api/v1/organizations/memberships?tenantId=` | `org.read` | Optional `subjectId`; excludes ENDED |
| PUT | `/api/v1/organizations/memberships?tenantId=` | `org.write` + tenant grant | Body: `subjectId`, `organizationId`, `membershipState` |
| DELETE | `/api/v1/organizations/memberships?tenantId=&subjectId=&organizationId=` | `org.write` + tenant grant | Ontology → ENDED; legacy row deleted |

Org scope: `JdbcOrgDirectory.resolveOrganizationSelfAndDescendants` (organization id space). Empty ACTIVE memberships → `NONE` (fail-closed).

## Legacy / 旧接口

`/api/v1/org/units|memberships` still works. Responses include:

- `Deprecation: true`
- `Link: </api/v1/organizations>; rel="successor-version"`
- `Warning: 299 - "Deprecated: use /api/v1/organizations …"`

Controller marked `@Deprecated`. Filter `LegacyOrgApiDeprecationFilter` also stamps `/api/v1/org/**`.

## Dual-read / write-through / 双读写透

- **Reads (new API):** always ontology (`organization` / `membership` / relations / `tenant_organization`).
- **Writes (new API):** ontology first; `syncOrganizationToLegacy` ensures remap aliases only (O7; no `org_unit` writes).
- **Legacy API:** thin adapter via `JdbcOrgDirectory` (ontology + map); deprecated headers unchanged.

## Console / 控制台

`/org` loads and writes via `/api/platform/organizations` (proxied to `/api/v1/organizations`). Field names: `organizationId`, `parentOrganizationId`, `organizationName`, `organizationState`.

## Non-goals / 非目标

No HR/Position/SCIM. Legacy table DROP completed in O7.
