# Single-replica gate / 单副本门禁（P4 → Scale-4d）

## Decision / 决定

**Publish manifests default to `replicas: 1`.** That stays the product default.
**发布清单默认 `replicas: 1`。** 这仍是产品默认。

Advertising `platform-app` **`replicas > 1`** is honest **only when both** shared backends are enabled:

对外宣称 `platform-app` **`replicas > 1`** 诚实的前提是**同时**启用：

| Backend | Config | Env |
| --- | --- | --- |
| Shared submit rate-limit | `platform.rate-limit.backend=jdbc` | `PLATFORM_RATE_LIMIT_BACKEND=jdbc` |
| Shared delivery circuit breaker | `platform.delivery.circuit-breaker.backend=jdbc` | `PLATFORM_DELIVERY_CIRCUIT_BREAKER_BACKEND=jdbc` |

Defaults for both remain **`process`** (per JVM). With defaults, dual pods each hold their own counters/breaker — experiment only, **not** an advertised multi-replica capability.
两者默认仍是 **`process`**（按 JVM）。默认下双副本各算各的——仅可试验，**不是**对外多副本能力。

Plan / inventory: [`docs/scale/horizontal-mvp-plan.md`](../scale/horizontal-mvp-plan.md) (**Item 4 DONE** at Scale-4d).

---

## What is shared vs not / 共享与不共享

| Concern | With defaults (`process`) | With both backends `jdbc` |
| --- | --- | --- |
| Registry / config / lock / outbox relay lock | Shared DB | Shared DB |
| Submit `RateLimitPort` | Per pod | Shared `rate_limit_window` |
| Outbox `DeliveryCircuitBreakerPort` | Per pod | Shared `delivery_circuit_breaker` |
| `entry-gateway` client rate-limit | Per gateway process (jdbc rejected) | Still per process |
| Object storage (`platform.storage.type`) | **Dev `local`:** per-pod disk (`object-store`). **Prod default `s3`:** shared bucket (MinIO / S3 API). | Same — production must use S3-compatible storage, not a local-disk PVC, as the multi-instance plan. JDBC backends are still required to advertise replicas>1. |

Object storage: production defaults to S3-compatible (`platform.storage.type=s3`, e.g. MinIO). Local directory storage is for development only and is not a multi-instance plan — each pod would see a different `object-store` tree. Do not advertise shared blobs on local disk.
对象存储：生产默认 S3 兼容（如 MinIO）。本地目录仅用于开发，不得作为多实例方案——各副本本地树互不可见。不要在本地盘上宣称共享对象。

---

## Manifests / 清单

- Default: [`deploy/k8s/platform-app.yaml`](../../deploy/k8s/platform-app.yaml) keeps **`replicas: 1`**.
- Optional opt-in snippet (not applied by CI): [`deploy/k8s/platform-app-shared-backends.snippet.yaml`](../../deploy/k8s/platform-app-shared-backends.snippet.yaml) — shows `replicas: 2` **plus** both `jdbc` env vars and the object-storage warning.
- Compose: one `platform-app` by default; `--scale platform-app=2` without both `jdbc` backends remains a known limit, not a claim. See [`deploy/compose/README.md`](../../deploy/compose/README.md).

默认清单保持单副本；可选片段仅在显式打开双 `jdbc` 后端时才谈多副本。Compose 默认仍一个副本。

---

## Not claimed / 不宣称

- Full HA, multi-region, sticky sessions for platform-app, HPA.
- Shared gateway rate-limit across `entry-gateway` replicas.
- Shared local object-store (local disk is not a multi-instance plan; production uses S3-compatible storage).
