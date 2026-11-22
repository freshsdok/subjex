# Observability dashboards (WP9-2) / 可观测看板（WP9-2）

These files are a **repo-owned, versioned** operator kit for the metrics, health, and audit signals the platform already emits. They are not a new observability product, and they do not require a hosted Grafana Cloud account.

本目录是仓库内、可版本管理的运维套件，面向平台**已经在发**的指标、健康检查与审计信号。这不是一套新的可观测产品，也不需要托管的 Grafana Cloud 账号。

See ADR [`docs/adr/0021-observability-and-edge-recipe.md`](../../docs/adr/0021-observability-and-edge-recipe.md) and the progress note [`docs/observability/wp9-observability-edge-progress.md`](../../docs/observability/wp9-observability-edge-progress.md).

## What this is not / 本切片不做

- This machine is **not** a production cluster. Checking these files in does **not** land Grafana, Prometheus, TLS, or a multi-replica deploy on a live environment.
  本机**不是**生产集群。把这些文件写入仓库，**并不等于**已在真实环境落地 Grafana、Prometheus、TLS 或多副本。
- Do not point the public edge (Nginx / APISIX) at `/actuator` or management ports 8089 / 8090 / 8091. Scrape from inside the cluster or a trusted admin network only.
  不要把对公边缘指到 `/actuator` 或管理端口。只从集群内或受信任的管理网段抓取。
- `entry-gateway` stays an optional in-cluster HTTP forwarder. This slice does not turn it into a gateway product.
  `entry-gateway` 仍是可选的集群内 HTTP 转发器。本切片不把它改成网关产品。
- Default Kubernetes manifests stay at `replicas: 1`. The optional WP9-3 multi-replica recipe is under `deploy/k8s/platform-app-shared-backends.snippet.yaml` and `deploy/compose/docker-compose.shared-backends.yml`. It is not a live multi-replica deploy.
  默认 Kubernetes 清单仍是 `replicas: 1`。可选的 WP9-3 多副本配方见上述路径。那不是一次真实的多副本部署。
- Edge TLS and public rate limits are tightened recipes under [`deploy/edge/`](../edge/README.md). They are still placeholders, and they have not been applied to a real environment in this work.
  边缘 TLS 与对外限流是 [`deploy/edge/`](../edge/README.md) 下已收紧的配方，仍是占位，本工作并未在真实环境落地。

## What the platform already emits / 平台已发出的信号

| Signal | Where | Notes |
| --- | --- | --- |
| Liveness / readiness | `GET /actuator/health/liveness` and `/readiness` on the **management** port | Anonymous for kubelet; `show-details: never`. Ports: platform-app **8089**, entry-gateway **8090**, sample-consumer **8091**. |
| Operator health (same two states) | Authenticated `GET /admin/health` on the business port | Console operators already have this; it is not a public probe. |
| Prometheus metrics | `GET /actuator/prometheus` on the management port only | Micrometer registry. JVM, process, HTTP `http.server.requests`, HikariCP (platform-app), Logback, Tomcat. Production exposure is `health,prometheus` only (ADR 0021 D2). |
| Correlated logs | Console pattern with `%X{correlationId}` / `%X{traceId}` | Headers `X-Correlation-Id`, `X-Request-Id`, `traceparent`. |
| Optional traces | OTLP when `platform.tracing.otlp-endpoint` / `PLATFORM_OTLP_ENDPOINT` / `OTEL_EXPORTER_OTLP_ENDPOINT` is set | Empty keeps the discarding exporter. |
| Audit query | Console `/audit`, `GET /api/v1/audit` | Filters: tenant, actor, action, from, to, limit. |
| Audit export (WP9-1) | `POST /api/v1/audit/export` (`audit.export`) | `application/x-ndjson`. |
| Optional NDJSON sink (WP9-1) | `platform.audit.sink.enabled` (default `false`) | Schema: [`docs/observability/audit-ndjson-schema.md`](../../docs/observability/audit-ndjson-schema.md). |
| Dead letters | Console `/dead-letters`, `GET /admin/dead-letters`, `GET /api/v1/dead-letters` | Existing operator views; not a Grafana panel. |

## Scrape with Prometheus / 用 Prometheus 抓取

Import or adapt [`prometheus-scrape.example.yml`](prometheus-scrape.example.yml) into an operator-owned Prometheus (or Grafana Alloy / another Prometheus-compatible scraper). Replace hostnames. Keep scrapes off the public edge.

把示例抓取配置并入运维自己的 Prometheus（或 Grafana Alloy 等兼容抓取器）。替换主机名。不要从对公边缘抓取。

The example jobs are `platform-app`, `entry-gateway`, and `sample-consumer`, matching `spring.application.name`. Those job names are what the dashboard's `$job` variable expects.

示例里的 job 名与 `spring.application.name` 一致，也是看板 `$job` 变量所预期的值。

## Import the Grafana dashboard / 导入 Grafana 看板

File: [`grafana/subjex-platform-overview.json`](grafana/subjex-platform-overview.json)

1. Run **your own** Grafana OSS (a laptop Docker Compose, an in-cluster Grafana, or any self-hosted instance). A Grafana Cloud account is not required.
   使用**自己的** Grafana OSS（笔记本 Docker Compose、集群内 Grafana，或任何自建实例）。不需要 Grafana Cloud 账号。
2. Add a Prometheus datasource that scrapes the example jobs above.
   添加一个抓取了上述 job 的 Prometheus 数据源。
3. Grafana → Dashboards → Import → upload the JSON. When prompted, map `DS_PROMETHEUS` to that datasource.
   导入 JSON，并把 `DS_PROMETHEUS` 映射到该数据源。
4. The dashboard uid is `subjex-platform-overview`. Re-importing the same file updates that uid rather than inventing a second product dashboard.
   看板 uid 为 `subjex-platform-overview`。再次导入同一文件是更新该 uid，而不是再做一套产品看板。

Panels are grounded in stock Micrometer / Spring Boot 4 names (Prometheus exposition): `up`, `process_uptime_seconds`, `process_cpu_usage`, `http_server_requests_seconds_*`, `jvm_memory_used_bytes`, `jvm_memory_max_bytes`, `jvm_gc_pause_seconds_*`, `jvm_threads_live_threads`, `hikaricp_connections_*`, `logback_events_total`, `tomcat_threads_*`. HikariCP series appear on `platform-app` only (the gateway has no JDBC pool). Empty panels mean that process does not emit the series — not that a vendor agent is missing.

面板基于 Spring Boot 4 / Micrometer 的现成指标名。HikariCP 只在 `platform-app` 上出现（网关没有 JDBC 池）。面板空白表示该进程不发这个序列，并不表示缺某个厂商探针。

## Operator views without Grafana / 没有 Grafana 时也能看

The dashboard does not replace the operator console. Use these views directly:

看板不替代运营控制台。可以直接使用：

- Health: `GET /admin/health` (authenticated) or management-port probes as above.
- Audit list and filters: console `/audit` and `GET /api/v1/audit`.
- Audit export / SIEM: `POST /api/v1/audit/export`, optional NDJSON sink, schema doc above.
- Dead letters: console `/dead-letters`.
- Logs: search `correlationId` / `traceId` in the process stdout. The edge recipes already forward `traceparent` and `X-Correlation-Id`.

