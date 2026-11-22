# Tenant hardening progress — 多租户加固进度

Wave **after** Identity + Observability (`4fd3cc9`). Order: **TN hardening first**, then thin workflow.

身份/可观测性之后。先做多租户加固，再做薄工作流。

## TN-0 [done] 2026-10-16 CST — inventory + plan

### Inventory / 盘点

| Area | What exists | Gaps |
| --- | --- | --- |
| **Tenant filter** | `TenantEnforcementFilter` (`X-Tenant-Id`): fail-closed via `TenantGuard` + `OperatorTenantAccess`; SA bound-tenant match (ID-2). Exempt: `/api/v1/**`, `/admin/**`, OP `/.well-known/**` `/oauth2/**`, read-only console pages | JSON APIs under `/api/v1` rely on **per-endpoint** `requireGranted` / `TenantDeclarationContext` — not the filter. Must stay covered by tests (TN-3) |
| **Grants** | `operator_tenant_grant` + `OperatorTenantAccess` / `JdbcOperatorTenantAccess`; wildcard `*`; super-admin bootstrap; operator admin replace-grants | No per-tenant quota grant; no export/delete ACL yet |
| **P5 quotas** | `TenantQuotaService`: global `daily-submit-limit` (10k) + `storage-row-limit` (100k); checked on **task + form submit**; **429** + body `{reason}`; audit `tenant.quota` / `REFUSED` before throw. Docs: `docs/release/tenant-quota.md` | No **per-tenant override**; storage count = task+form+outbox only — **misses `entity_record` / `entity_blob`** (ES wave); no admin usage/override API; no dedicated `TenantQuota*Test` (only mocked in security slices) |
| **Tenant admin** | `JdbcTenantAdmin` / `TenantManagementEndpoint`: list/get/create/rename/disable/enable (soft SUSPENDED); `platform` immutable; audit on writes | Soft-disable only — **no hard delete / GDPR export** |
| **Isolation tests** | Grants: `OperatorTenantAuthorizationTest`. Entity: `GenericEntityStoreTest` / `HybridEntityStoreTest` / `EntityBlobStoreTest` / `GenericEntitySecurityTest`. Org: `JdbcOrgDirectoryTest`, ontology backfill MIG-03. Rate limit: per-tenant buckets | No single **TN isolation CI suite/gate** that fails the build on cross-tenant leakage across entity/blob/form/task; filter exemption of `/api/v1` is a standing risk if a new endpoint forgets `requireGranted` |
| **Export / delete** | TN-2: ZIP export + challenge/confirm hard erase (see below). Soft-disable still default. | — |

### Proposed slices / 计划切片

| ID | Scope (concrete) | Depends |
| --- | --- | --- |
| **TN-1** ✅ | **Quota harden**: include `entity_record` (+ optional `entity_blob` row count) in storage cap; per-tenant override table + admin GET usage / PUT override (`tenant.manage`); keep global defaults; prove **429 + audit** with a real `TenantQuotaServiceTest` (not mock-only); clarify `docs/release/tenant-quota.md` | TN-0 |
| **TN-2** ✅ | **Export / delete (GDPR-ish)**: operator-triggered tenant data **export** (JSON/ZIP of tasks, forms, entities, blobs metadata); **hard-delete / erasure** job with confirm + audit; soft-disable remains default | TN-1 |
| **TN-3** ✅ | **Cross-tenant isolation CI gates**: one Surefire group or Arch/IT suite that asserts wrong-tenant 403/empty for entity, blob, form, task, org; optional check that new `/api/v1/**` controllers call grant helpers; wire into `build.yml` | TN-1 |

### Out of scope this wave / 本波不做

- Billing / paid tiers
- Thin workflow (starts **after** TN-1…TN-3)
- Changing `/api/v1` filter exemption wholesale (prefer TN-3 gates over blanket filter)

## TN-1 [done] 2026-10-16 CST — quota harden

- Storage count += `entity_record` + `entity_blob`.
- Flyway **V36** `tenant_quota_override`; globals remain defaults.
- API: `GET/PUT /api/v1/tenants/{id}/quota` (read `admin.read`, write `tenant.manage`); audit `tenant.quota.override`.
- Tests: `TenantQuotaServiceTest` (count, override, **429** + audit REFUSED), `TenantQuotaApiSecurityTest`.
- Docs: `docs/release/tenant-quota.md` updated.

## TN-2 [done] 2026-10-16 CST — export / hard erase (GDPR-ish MVP)

### APIs (`tenant.manage`)

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/api/v1/tenants/{id}/export` | Sync ZIP: manifest, tasks, forms, outbox, entity_records, entity_blobs (+ `blobs/*` bytes) |
| `POST` | `/api/v1/tenants/{id}/erase/challenge` | Requires **SUSPENDED**; returns one-time `{challengeId,token,expiresAt}` (15m) |
| `POST` | `/api/v1/tenants/{id}/erase` | Body `{challengeId,token}`; wipe scoped rows + ObjectStorage; tenant stays SUSPENDED |

Refuse reserved tenant `platform`. Soft-disable remains the default off-board path.

### Wipe set / 擦除范围

`platform_task`, `form_submission`, `outbox_event`, `entity_record`, `entity_blob` (+ objects), `tenant_quota_override`, `oauth_client` (tenant-bound), `declaration_revision` / promote / migration / approval, `tenant_organization`, `operator_tenant_grant` for that tenant, erase challenges.

### Runbook / 操作手册

1. `POST .../disable` (soft) → verify business stopped.
2. `POST .../export` → archive ZIP offline.
3. `POST .../erase/challenge` → copy token once.
4. `POST .../erase` with challengeId+token → confirm counts in response + audit `tenant.erase`.

Flyway **V37** `tenant_erase_challenge`. Tests: `TenantLifecycleTest`, `TenantLifecycleApiSecurityTest`.

## TN-3 [done] 2026-10-16 CST — cross-tenant isolation CI gates

### Suite / 套件

JUnit 5 tag `@Tag("tenant-isolation")` via `TenantIsolationTags.TENANT_ISOLATION`. Covered classes:

| Area | Tests |
| --- | --- |
| Grants | `OperatorTenantAuthorizationTest` |
| Entity / blob / hybrid | `GenericEntitySecurityTest`, `GenericEntityStoreTest`, `HybridEntityStoreTest`, `EntityBlobStoreTest` |
| Form / declaration | `DeclarationSecurityTest`, `FormDomainActionRunnerTest` |
| Task | `TaskSubmissionSecurityTest` |
| Org | `JdbcOrgDirectoryTest`, `OrganizationScopeResolverTest`, `OrganizationOntologyBackfillGatesTest` |
| Arch | `TenantIsolationArchTest` |

Run locally / CI: `mvn -pl platform-app -am test -Dgroups=tenant-isolation -Dsurefire.failIfNoSpecifiedTests=false` (~79 tests).

### ArchUnit / 架构门禁

`TenantIsolationArchTest`: every `@RestController` that depends on `OperatorTenantAccess` must call `requireGranted(OperatorPrincipal, String)`.

**Exempt (documented, no port inject):** `TaskSubmissionEndpoint` (`TenantEnforcementFilter`); `TenantQuotaEndpoint` / `TenantLifecycleEndpoint` (role `tenant.manage` / `admin.read` + service-layer checks).

### CI

`.github/workflows/build.yml` job `build` step **tenant-isolation gates** after full `mvn test`.

## Wave complete / 本波完成

TN-0…TN-3 done. Next wave: **thin workflow** — see [`docs/workflow/wf-progress.md`](../workflow/wf-progress.md) (WF-0 inventory landed).


## Progress pointer

Release checklist P5 remains valid; this wave **extends** it rather than replacing it. Isolation suite is the standing gate for `/api/v1` filter exemption.

## Tenant custom hosts (ops first cut) — 2026-11-23 CST

Independent tenant hostnames for Host-header routing. This is a vertical first cut:
schema + resolve + operator manage + tests. It does **not** provision public DNS or TLS,
and it does not move the operator console onto tenant domains.

租户独立主机名（按 Host 头解析）的首个完整切口：表结构 + 解析 + 操作台管理 + 测试。
**不**办理公网 DNS 或证书，也不把操作台改到租户域名上。

### Bindings / 绑定

- Flyway **V60** `tenant_host` (`host_name` unique, lowercase, no port/scheme/path).
- APIs: `GET/POST /api/v1/tenants/{id}/hosts` and `DELETE .../hosts/{hostName}`. List is `admin.read`; writes are `tenant.manage`.
- Reserved tenant `platform` cannot have bindings. `localhost`, loopback IPs, and `platform.hosts.reserved` (`PLATFORM_RESERVED_HOSTS`) cannot be bound.
- Invalid names (one label, path, scheme, wildcard, IP literal) are rejected.

### Resolution / 解析

- `TenantHostResolutionFilter` reads the request **`Host` header only**. It does **not** honor `X-Forwarded-Host` or `Forwarded` — platform-app has no trusted-proxy list for those (entry-gateway only has one for `X-Forwarded-For`).
- The Nginx recipe already forwards the original public name with `proxy_set_header Host $host`. Production must add those public API/console names to `PLATFORM_RESERVED_HOSTS`, and the edge TLS `server_name` (and certificates) must actually accept tenant hostnames; this repository still uses placeholders only.
- Bound + active Host → injects `X-Tenant-Id` so existing APIs and the end-user shell can identify the tenant without a manual header. Bound Host + a *different* `X-Tenant-Id` → **409** (fail closed). Bound Host + disabled/missing tenant → **403**.
- Several distinct `Host` values → **409**. Unknown hosts do **not** invent a tenant; header-based calls on an unbound name keep working as before. Platform/loopback hosts also stay header-based.
- Public discovery: `GET /api/v1/public/host-tenant` (permitAll) returns whether *this* request Host is bound. No query-parameter override (that would be spoofable). If the H5 page and the API use *different* hostnames, the API will not see the page hostname (browsers cannot set `Host`); keep using the existing tenant field in that setup.

硬删除会一并清掉该租户的主机绑定，导出 ZIP 含 `hosts.json`。
