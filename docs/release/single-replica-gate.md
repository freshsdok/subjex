# Single-replica gate / 单副本门禁（P4）

## Decision / 决定

Do **not** ship a distributed circuit breaker. Publish manifests default to `replicas: 1`.
**不做**分布式熔断。发布清单默认 `replicas: 1`。

| Concern | Scope |
| --- | --- |
| Outbox `DeliveryCircuitBreaker` | In-process, wraps only the selected `DeliveryPort` |
| Platform / gateway rate limits | In-process counters |
| Dual `platform-app` pods | Known limit — shared DB helps registry/config/lock, **not** breaker/rate-limit |

Running two replicas is allowed for experiments; it is **not** an advertised HA capability.
允许试验性双副本；**不是**对外高可用能力。
