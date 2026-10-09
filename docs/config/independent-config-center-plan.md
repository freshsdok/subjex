# Independent config center MVP / 独立配置中心 MVP（Item 5）

**Status:** **Item 5 DONE** — Config-5d namespace UX + docs (2026-10-09 Asia/Shanghai)  
**Item 4:** DONE at Scale-4d (`a525ce8`)  
**Stance:** Deepen the existing **DB + HTTP** surface inside the modular monolith. “Independent” means a **dedicated module/API lifecycle** (`ConfigCenterPort` + clear endpoints), **not** a new microservice in the MVP.

---

## Inventory / 现状盘点（Config-5a）

### Contract (`platform-contract` … `config`)

| Type | Role |
| --- | --- |
| `ConfigSource` | Read one key → `Optional<String>` |
| `LocalApplicationConfig` | Base layer: this process’s Spring/application properties |
| `ConfigOverrideStore` | Writable override layer: `override` + `keys` (+ `lookup`) |
| `MemoryConfigOverride` | Test-only in-memory store |
| `OverridingConfigSource` | Override wins, else base |
| `ConfigListing` / `ConfigEntry` / `ConfigOrigin` | Effective row with origin `LOCAL` \| `OVERRIDE` |
| `HttpConfigSource` | Client to `GET/POST /config/entries` (Basic auth); connect failure → empty lookup (local fallback) |
| `ConfigEntryJson` | Wire codec for entries |

No namespaces, no revision/ETag, no history, no push/watch.

### Persistence

| Artifact | Notes |
| --- | --- |
| Flyway `V3__shared_registry_config_lock.sql` → `config_override` | `(config_key PK, config_value, overridden_at)` — flat, global keys |
| `JdbcConfigOverride` | Shared across processes on the same DB; restart-safe |

### platform-app HTTP + pages

| Surface | Authz | Behavior |
| --- | --- | --- |
| `GET /config` (`ConfigListPage`) | operator + page | Human list: watched keys + stored overrides; origin words |
| `GET/POST /config/entries` (`ConfigEntriesEndpoint`) | `config.read` / `config.write` | Cross-process get/put one key; audit `config.override` |
| `GET /api/v1/config` + `PUT /api/v1/config/{key}` (`ConfigApiEndpoint`) | same permissions | JSON twin for console; same `ConfigCatalog` |
| `ConfigCatalog` | — | Watched keys = static discovery host/port for `platform-app` + all override keys; **not** full Environment dump |

### sample-consumer

`ConsumerWiring` builds `OverridingConfigSource(HttpConfigSource → platform-app, LocalApplicationConfig)`. Unreachable platform → local only. No poll loop; each lookup is on-demand HTTP.

### Console (`web/`)

| Piece | Notes |
| --- | --- |
| `web/src/app/(console)/config/` | Table of keys; `ConfigOverrideCell` review → confirm → `PUT /api/v1/config/{key}` |
| Permissions | View always; edit requires `config.write` (read-only notice otherwise) |
| Forms | `config-override.form.yaml` → domain action writes override via catalog |

### Gaps vs “config center” (honest)

- No **namespace** (tenant/app/env isolation beyond key naming convention)
- No **revision / ETag / If-Match** — last write wins; clients cannot detect stale put
- No **history / rollback**
- No **push / long-poll / watch** — consumers pull on each lookup
- No separate config process — and **MVP will not add one**
- Marketing risk: title “配置中心” vs Nacos/Apollo — keep docs saying **shared override layer / config center port**

---

## Non-goals (MVP) / 非目标

- Full Nacos / Apollo / Spring Cloud Config Server replacement  
- Git-backed config, gray release, encrypted KMS vault product  
- Separate deployable config microservice (may be a later optional extract behind the same port)  
- Dumping the entire Spring `Environment` to operators  
- Real-time push fan-out (optional poll/ETag is enough for MVP)

---

## MVP shape / 目标形状

**Versioned namespaces + list/get/put + optional poll/ETag (or monotonic revision).**

1. **Namespace** — e.g. `default` or `tenant:{id}` / `app:platform` (exact scheme in 5b). Keys remain strings within a namespace.  
2. **Revision** — monotonic per namespace (or per key); expose on get/list; support `If-Match` / query `since=` for cheap poll.  
3. **API** — deepen existing paths (prefer `/api/v1/config/...` + keep `/config/entries` compatible or versioned).  
4. **Port** — `ConfigCenterPort` is the lifecycle surface (list/get/put; later revision/namespace). Store stays JDBC in platform-app.  
5. **Clients** — `HttpConfigSource` learns namespace + optional `If-None-Match` / revision header; sample-consumer keeps local fallback.

---

## Slice plan / 切片计划

| Slice | Deliverable | Done when |
| --- | --- | --- |
| **5a** (this) | Inventory + this plan; extract `ConfigCenterPort`; `ConfigCatalog` implements it; package-info points here | Plan committed; port compiles; default behavior unchanged |
| **5b** ✅ | Flyway `V28__config_override_namespace_revision`; `ConfigCenterPort`/`JdbcConfigOverride` namespace-aware; put returns revision; optional API `namespace` | `JdbcConfigNamespaceRevisionTest` isolation + revision bump; flat default ns kept |
| **5c** ✅ | HTTP: list/get/put with revision; `ETag` / `If-Match` / `If-None-Match` (304); `HttpConfigSource` + console If-Match; history table skipped | `ConfigETagsTest` + `ConfigETagHttpTest` + HttpConfigSource conditional tests |
| **5d** ✅ | Console UX for namespace (at least `default`); docs/ARCHITECTURE honesty; **Item 5 DONE**; still no separate microservice | Operators list/edit under a namespace; non-goals restated |

**Out of scope for 5a–5d:** push websocket, Git sync, multi-datacenter, secret encryption product.

---

## Scale-5b delivered

1. Flyway `V28__config_override_namespace_revision.sql` — table recreate to `(namespace, config_key)` PK + `revision`; backfill `default` / revision 1.  
2. `ConfigCenterPort` / `ConfigOverrideStore` / `JdbcConfigOverride` / `MemoryConfigOverride` namespace-aware; put returns / stores revision.  
3. HTTP: optional `namespace` query/body (default `default`); GET `/config/entries` and `/api/v1/config` responses include `namespace` + `revision`; POST `/config/entries` stays **204**.  
4. Test: `JdbcConfigNamespaceRevisionTest` (H2 PG + MySQL modes).

## Config-5c delivered

1. `ConfigETags` — strong tag `"N"` from revision; parse `If-Match` / `If-None-Match` (weak `W/"N"` accepted).
2. GET `/config/entries` → `ETag`; `If-None-Match` → **304**; PUT `/api/v1/config/{key}` and POST `/config/entries` → `If-Match` mismatch **412**; success responses carry new `ETag`. Missing `If-Match` still allowed (compat).
3. `HttpConfigSource` — unconditional `lookup`/`read`; `readIfNoneMatch`; `override` sends `If-Match` when revision known; tracks `knownRevisions`.
4. Console — revision column; override cell sends `If-Match`; proxy forwards `If-Match`/`If-None-Match` and returns `ETag`. History table **skipped** (ETag enough for MVP).

## Config-5d delivered — **Item 5 DONE**

1. Console `/config`: namespace picker (at least `default`); list/PUT honor `namespace` query/body; honesty notice (DB+HTTP, not Nacos).  
2. Docs: `ARCHITECTURE` §7/§10/§16 + `docs/config-ui.md` link this plan; non-goals restated.  
3. Still no separate config microservice.

**Next track (release):** CI fix + docs收口 + alpha tag. Pre-alpha items **1–5 complete** (AuthZ → MigUX → AI confirm → scale → config center).

---

## Cross-links

- UI notes: [`docs/config-ui.md`](../config-ui.md)  
- ARCHITECTURE §7 / §10 config thin slice  
- Pre-release assessment: config = shared table, not Nacos  
- Permissions: `config.read` / `config.write`; audit `config.override`  
