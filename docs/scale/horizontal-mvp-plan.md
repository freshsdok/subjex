# Horizontal scale MVP / 水平扩展 MVP（Item 4）

**Status:** **Item 4 DONE** (Scale-4d 2026-10-09 Asia/Shanghai) — next pre-alpha **item 5** (independent config center MVP)  
**Goal:** Shared rate-limit + delivery breaker state so `replicas > 1` is *honest* for those limits — not a full HA claim.  
**Default stays `replicas: 1`**; advertising N>1 requires both JDBC shared backends (see gate doc).  
**Non-goals (4a–4d):** multi-region, sticky sessions for platform-app, rewriting object storage to S3, claiming HA without shared limits / shared object volume.

---

## Inventory / 现状盘点（Scale-4a）

| Component | Where | Shared across pods today? |
| --- | --- | --- |
| `RateLimitPort` | `platform-contract` — single port | Interface only |
| `SingleProcessRateLimit` / `JdbcRateLimitPort` | `platform-app` — tenant/action fixed window | **Opt-in Yes** via `platform.rate-limit.backend=jdbc` (default process = No) |
| `GatewayRateLimit` | `entry-gateway` — client/action fixed window | **No** (separate process map) |
| `DeliveryCircuitBreaker` / `JdbcDeliveryCircuitBreakerPort` | contract + `platform-app` — open/half-open streak | **Opt-in Yes** via `platform.delivery.circuit-breaker.backend=jdbc` (default process = No) |
| Outbox relay lock | `JdbcTaskMessagePort.relayPending` via `DistributedLockPort` / `platform_lock` | **Yes** (JDBC) — only one relay owner |
| Idempotency lock | same `JdbcRowLock` / `platform_lock` | **Yes** |
| Registry / config | `service_endpoint`, `config_override` | **Yes** (shared DB) |
| Object storage | `LocalDirectoryObjectStorage` → `platform.storage.directory` (default `object-store`) | **No** — local disk path; multi-pod needs shared volume or different SPI later |
| K8s / compose | default `replicas: 1`; optional shared-backends snippet | Advertise N>1 **only** with both jdbc backends |
| Redis | Compose + `web/` `SESSION_REDIS_URL` only | Console sessions **only**; **Java platform processes do not use Redis** (ARCHITECTURE / README) |
| Docs | `docs/release/single-replica-gate.md`, SECURITY §10 Scale-4d | P4 gate lifted only under dual jdbc backends |

### Implication

Dual `platform-app` pods already share DB rows (registry/config/lock/relay). Opt-in `platform.rate-limit.backend=jdbc` shares `rate_limit_window`; opt-in `platform.delivery.circuit-breaker.backend=jdbc` shares `delivery_circuit_breaker`. Defaults remain process. Entry-gateway replicas still each hold their own client buckets (jdbc backend rejected there).

---

## Redis vs JDBC (recommendation)

| Option | Pros | Cons in this repo |
| --- | --- | --- |
| **JDBC / shared DB** | Already required for every platform-app; `platform_lock` pattern exists; no new runtime for Java; matches ARCHITECTURE (“Java processes do not use Redis”) | Extra tables + careful SQL for atomic increment / breaker CAS; slightly higher DB load |
| **Redis** | Fast counters; already in compose for **web** sessions | Would introduce Redis as a **platform-app** dependency (new ops/failure mode); contradicts documented boundary; entry-gateway would need Redis too for shared gateway limits |

**Prefer JDBC-backed shared counters** for platform-app rate-limit + delivery breaker. Keep Redis optional for console sessions only. Entry-gateway shared limit (4c) can either (a) call platform-app for permit checks, or (b) use the same DB with a minimal schema — prefer (a) only if gateway→app coupling is acceptable; otherwise JDBC from gateway needs a datasource (heavier). Plan 4c as “document gateway still per-process until optional shared table or central permit API.”

---

## Slice plan / 切片计划

| Slice | Deliverable | Done when |
| --- | --- | --- |
| **4a** (this) | Inventory + this plan; extract `DeliveryCircuitBreakerPort`; keep `replicas: 1` | Plan committed; breaker tests green |
| **4b** ✅ | `JdbcRateLimitPort` + Flyway `V26__rate_limit_window`; `platform.rate-limit.backend=process\|jdbc` (default **process**); gateway stays process-only (jdbc fail-closed) | Dual `JdbcRateLimitPort` on one H2 share budget; default still process |
| **4c** ✅ | `JdbcDeliveryCircuitBreakerPort` + Flyway `V27__delivery_circuit_breaker`; `platform.delivery.circuit-breaker.backend=process\|jdbc` (default **process**); OutboxSocketPublisher / Kafka use port | Dual instances on one H2 share open state; default still process |
| **4d** ✅ | Gate doc + SECURITY/ARCHITECTURE; default manifests `replicas: 1`; optional `platform-app-shared-backends.snippet.yaml`; object-storage multi-pod caveat; **Item 4 DONE** | Advertise N>1 only when both backends `jdbc`; defaults still process + replicas 1 |

**Out of scope:** distributed tracing HA, Kafka consumer group tuning, shared local object-store without PVC.

---

## Scale-4b delivered

1. Flyway `V26__rate_limit_window.sql` — `(bucket_key, window_started_at, permit_count)`.  
2. `JdbcRateLimitPort` implementing `RateLimitPort.permit(tenantId, actionName)`.  
3. `platform.rate-limit.backend=process|jdbc` (default `process`). Entry-gateway: `gateway.rate-limit.backend` process-only (jdbc rejected at startup).  
4. Test: two `JdbcRateLimitPort` instances on one H2 (PG + MySQL modes) share one budget (`JdbcRateLimitPortTest`).

## Scale-4c delivered

1. Flyway `V27__delivery_circuit_breaker.sql` — `(destination_key, consecutive_failures, opened_at)`.  
2. `JdbcDeliveryCircuitBreakerPort` implementing `DeliveryCircuitBreakerPort`.  
3. `platform.delivery.circuit-breaker.backend=process|jdbc` (default `process`); destination-key default `outbox`.  
4. OutboxSocketPublisher + Kafka publisher take the port; PlatformWiring switches backend.  
5. Test: two instances on one H2 share open / half-open / success clear (`JdbcDeliveryCircuitBreakerPortTest`).

## Scale-4d delivered — Item 4 DONE

1. [`docs/release/single-replica-gate.md`](../release/single-replica-gate.md) — advertise `replicas > 1` only when **both** `platform.rate-limit.backend=jdbc` and `platform.delivery.circuit-breaker.backend=jdbc`.  
2. SECURITY §10 + ARCHITECTURE P4 + checklist P4 wording updated (zh/en).  
3. Object-storage multi-pod caveat (local directory not shared without PVC / different SPI).  
4. Default K8s/compose stay **`replicas: 1`**; optional [`deploy/k8s/platform-app-shared-backends.snippet.yaml`](../../deploy/k8s/platform-app-shared-backends.snippet.yaml).  

## Next — Item 5 (independent config center MVP)

Pre-alpha track: deepen beyond the thin `config_override` slice (version/history/rollback, clearer “not Nacos/Apollo”, or a process-boundary plan). Suggested first slice **Config-5a**: inventory current ConfigSource/override/HTTP/console + plan doc with honest non-goals.

---

## Config (4b–4d)

```yaml
platform:
  rate-limit:
    backend: process   # process | jdbc — both jdbc required to advertise replicas>1
    permits: 60
  delivery:
    circuit-breaker:
      backend: process  # process | jdbc
      destination-key: outbox
```

Default publish manifests remain **`replicas: 1`**. Opt-in snippet sets both backends to `jdbc` when intentionally advertising N>1.

---

## Cross-links

- P4 gate: [`docs/release/single-replica-gate.md`](../release/single-replica-gate.md)  
- ARCHITECTURE shared DB / no Redis for Java  
- `RateLimitPort`, `SingleProcessRateLimit`, `GatewayRateLimit`  
- `DeliveryCircuitBreaker` / `DeliveryCircuitBreakerPort`  
- Outbox relay: `JdbcTaskMessagePort#relayPending` + `platform_lock`  
