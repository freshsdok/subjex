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
- **Writes (new API):** ontology first, then `OrganizationOntologyBackfill.syncOrganizationToLegacy` / `syncMembershipToLegacy` / `endMembershipToLegacy` so O3 dual-read and legacy clients stay consistent.
- **Legacy API:** unchanged (legacy tables + O3 write-through into ontology).

## Console / 控制台

`/org` loads and writes via `/api/platform/organizations` (proxied to `/api/v1/organizations`). Field names: `organizationId`, `parentOrganizationId`, `organizationName`, `organizationState`.

## Non-goals / 非目标

No HR/Position/SCIM; no Picker rename (O6); no legacy table DROP (O7).
