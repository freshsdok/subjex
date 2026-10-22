# Config center deepen progress / 配置中心加深进度

Date: 2026-10-22 Asia/Shanghai. Parent: Item 5 DONE — [`independent-config-center-plan.md`](independent-config-center-plan.md). ADR: [`../adr/0012-config-center-deepen.md`](../adr/0012-config-center-deepen.md).

| Slice | Status | Notes |
| --- | --- | --- |
| **Config-6a** | **done** (local) | ADR 0012 Accepted; Flyway `V46__config_override_history`; JDBC/Memory put appends history; `history(ns,key)` on store; test |
| **Config-6b** | **done** (local) | `GET /api/v1/config/{key}/history`; console thin panel via `?historyKey=`; uni-app out of scope |
| **Config-7a** | **done** (local) | `POST …/history/{revision}/restore`; console restore button; audit `config.restore` |
| Config-8 | later | Push/watch only if still needed |

**Non-goals:** microservice, Nacos, silent rewind, websocket in 6a.

## Config-6a delivered

1. ADR 0012 locks deepen order: history → restore put → optional push.  
2. `config_override_history` PK `(namespace, config_key, revision)`.  
3. `ConfigOverrideStore.history` + append on `JdbcConfigOverride` / `MemoryConfigOverride` put.  
4. Focused test: two puts → two history rows, newest revision last.

本地提交；未随 Part A 二次推送。

## Config-6b delivered

1. `GET /api/v1/config/{key}/history?namespace=` — `config.read`; empty list when never overridden.  
2. Security: GET `/api/v1/config/**` under `config.read`.  
3. Console `/config`: History link per row → thin panel (`?historyKey=`); no restore (Config-7).  
4. **uni-app / H5:** out of scope this slice (web console only).  
5. Tests: `JsonApiSecurityTest.configHistoryNeedsConfigRead` + OpenAPI mapping coverage.

本地提交；未二次推送。Next = **Config-7a** restore put, or polish 6b phrases if needed.

## Config-7a delivered

1. `POST /api/v1/config/{key}/history/{revision}/restore?namespace=` — `config.write`; optional If-Match on **current** revision; 404 if history missing.  
2. `ConfigCatalog.restore` → put historical value → new revision + history append (no rewind).  
3. Audit action `config.restore` target `key@fromRevision` (or `ns/key@rev`).  
4. Console history panel: restore column when `config.write` / Manifest button allows; confirm step.  
5. uni-app still out of scope.

本地提交。Next = **Config-8** optional push/watch only if still needed after ETag poll; else stop deepen.

