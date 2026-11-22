# WP9 observability and edge recipe progress / 可观测性与边缘配方进度

- Date: 2026-11-22 (Asia/Shanghai) — WP9-3 optional multi-replica recipes landed as repo files; WP9-2 the same day; earlier WP9-0 / WP9-1 dates stay 2026-11-20.
- ADR: [`docs/adr/0021-observability-and-edge-recipe.md`](../adr/0021-observability-and-edge-recipe.md)
- Predecessor: ADR [`0004`](../adr/0004-observability-audit-trace.md) (audit query + correlation / traceparent), [`docs/observability/obs-progress.md`](obs-progress.md) (OBS-1 / thin OBS-2), WP1 probes and single-replica gate under [`docs/release/`](../release/).
- Baseline tip when this note was opened: `2be7e94` (WP8 consent/export package closed; formal acceptance `2569f52`)
- Locked canvas: 「Subjex 底座工作需求」WP9 section (read this slice, used verbatim). WP9 is **non-blocking** (P2): it may run in parallel with domain work and does not block a first public launch.
- 本切片提交：`eb60b4e`（`eb60b4ed215070a086ba6f231d587a775d860c73`；WP9-0 盘点、ADR 0021 与边缘配方）。不改 Java，不把 `entry-gateway` 改成网关产品，不启动 WP9-1。未改 uni-app / `web/`。
- WP9-1 提交：`4a9797d`（`4a9797dd9ec1ce2493623de90cff70ac9d4a0a4e`；审计 NDJSON 导出与可选写路径 SIEM sink）。画布验收「审计可导出」已满足。未改 `entry-gateway`，当时不启动 WP9-2/3。
- WP9-2 进度：见下文「WP9-2 [done]」。本机不是生产集群；未在真实环境落地 Grafana、Prometheus、边缘 TLS 或多副本。
- WP9-3 进度：见下文「WP9-3 [done]」。默认清单仍是 `replicas: 1`。可选配方已收紧并加诚实门禁；未向任何集群应用清单，也不是一次生产发布。

Canvas WP9 verbatim / 画布 WP9 原文：

- 优先级：P2。目标：可与医疗业务并行，不阻塞底座首次对外。
- 要求 1：审计导出或 SIEM 投递端口。
- 要求 2：写清边缘网关配方（Nginx 或 APISIX）：TLS、限流、控制台与终端路由分离；不要把现有入口转发器改造为网关产品。
- 要求 3：多副本部署配方和指标面板可后补。
- 验收：有可照着操作的边缘部署说明；审计可导出；单副本仍可作为首次上线默认。
- 待拍板（画布建议默认）：边缘网关用 Nginx 或 APISIX 终结 TLS 与限流；不扩建现有入口转发器；具体软件可在 WP9 写进配方。

## Inventory — what exists today / 盘点（今日事实与路径）

### Actuator / health / readiness

| Piece | Path | Notes |
| --- | --- | --- |
| Spring Boot Actuator + probe flags | `platform-app/src/main/resources/application.yml` (`management.*`) | `management.server.port` default **8089** (`MANAGEMENT_SERVER_PORT`). Exposure `include: health,prometheus`. `endpoint.health.probes.enabled: true`, `show-details: never`. Liveness/readiness state enabled. |
| Same pattern | `entry-gateway/src/main/resources/application.yml` (port **8090**), `sample-consumer/src/main/resources/application.yml` (port **8091**) | Prometheus is not on the business port. |
| Anonymous probes | `GET /actuator/health/liveness`, `GET /actuator/health/readiness` | Documented in `README.md`, `SECURITY.md`, `docs/release/probes-and-network.md`. |
| Operator health view (authenticated) | `platform-app/src/main/java/com/subjex/platform/app/admin/AdminReadEndpoint.java` — `GET /admin/health` | Same liveness/readiness names; not the anonymous probe. |
| Prod profiles | `application-prod.yml`, `application-production.yml` | No extra actuator exposure; they inherit the base `include: health,prometheus`. `env` / `heapdump` are **not** in the include list. |

### Micrometer / Prometheus

| Piece | Path | Notes |
| --- | --- | --- |
| Dependencies | `platform-app/pom.xml` (`spring-boot-starter-actuator`, `micrometer-registry-prometheus`) | In-process registry exists. |
| Scrape endpoint | `/actuator/prometheus` on the **management** port only | `docs/release/probes-and-network.md`, `SECURITY.md`. |
| Dashboards (WP9-2) | [`deploy/observability/`](../../deploy/observability/README.md) | **Repo-owned files, not an in-platform product.** Versioned Grafana JSON (`grafana/subjex-platform-overview.json`) plus a Prometheus scrape example for management ports 8089 / 8090 / 8091. Import into operator-owned Grafana OSS; a Grafana Cloud account is not required. Grounded in stock Micrometer / Spring Boot metrics already scraped at `/actuator/prometheus`, plus pointers to existing health, audit, NDJSON, and dead-letter views. Checking the files in is **not** a live Grafana or Prometheus deploy. |

### Tracing / OpenTelemetry

| Piece | Path | Notes |
| --- | --- | --- |
| SDK + OTLP wiring | `platform-app/src/main/java/com/subjex/platform/app/trace/TracingConfiguration.java` (also `sample-consumer/.../TracingConfiguration.java`) | Discarding exporter unless `platform.tracing.otlp-endpoint` / `PLATFORM_OTLP_ENDPOINT` / `OTEL_EXPORTER_OTLP_ENDPOINT` is set (`application.yml`). Root BOM: `opentelemetry.version` in the repo `pom.xml`. |
| HTTP extract / span | `platform-app/src/main/java/com/subjex/platform/app/trace/RequestObservabilityFilter.java` | OBS-2: mint or honor `X-Correlation-Id` / `X-Request-Id`; extract W3C `traceparent`; SERVER span; echo headers. Tests: `RequestObservabilityFilterTest`, `TracingExporterChoiceTest`. |
| Outbox inject | sample-consumer / outbox path (ADR 0004) | Gateways should forward `traceparent` (and may forward `X-Correlation-Id`). |

### Log format and correlation id

| Piece | Path | Notes |
| --- | --- | --- |
| Console pattern | `platform-app/src/main/resources/application.yml` `logging.pattern.console` | `%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] %logger{36} correlationId=%X{correlationId:-} traceId=%X{traceId:-} - %msg%n` |
| MDC keys | `RequestObservabilityFilter` | `correlationId`, `traceId`. |

### Audit

| Piece | Path | Notes |
| --- | --- | --- |
| Write port | `platform-contract/.../audit/AuditPort.java`, `platform-app/.../jdbc/JdbcAuditPort.java` → table `audit_entry` | Single write path (ADR 0004). Helpers: `OperatorActionAudit`, `ObjectAccessAudit`. |
| Query API | `platform-app/.../admin/AuditApiEndpoint.java` — `GET /api/v1/audit` | Filters: tenant, actor, action, from, to, limit (OBS-1). |
| Console page | `platform-app/.../admin/AuditPage.java` — `GET /audit` | Same filters; requires operator permission. Also `GET /admin/audit` (newest 200). |
| Export / SIEM | `POST /api/v1/audit/export`; `AuditSink` + `NdjsonAuditSink` | **WP9-1 done.** Tenant-scoped NDJSON export (`audit.export`, operator only). Optional write-path file/stdout sink, off by default. Schema: [`audit-ndjson-schema.md`](audit-ndjson-schema.md). |

### Outbox / DLQ visibility

| Piece | Path | Notes |
| --- | --- | --- |
| Bus | `outbox_event` (existing table); `TaskMessagePort` / `JdbcTaskMessagePort.java` | WP6+ locked: no second outbox table. |
| Operator DLQ API | `platform-app/.../admin/DeadLetterApiEndpoint.java` — `GET /api/v1/dead-letters`, replay / discard | Authenticated operator routes. |
| Admin lists | `AdminReadEndpoint` — `GET /admin/tasks`, `GET /admin/dead-letters` | Read-only operator view. |
| K8s delivery | `deploy/k8s/platform-app.yaml`, `deploy/k8s/sample-consumer.yaml`, `deploy/k8s/README.md` | Outbox TLS fail-closed via Secrets; NetworkPolicy sample restricts who may reach the consumer. |

### `deploy/k8s`

| File | Role |
| --- | --- |
| `deploy/k8s/README.md` | Manifests are descriptive, not applied by tests. `prod` profile, no `local` seed. Default **`replicas: 1`**. |
| `deploy/k8s/platform-app.yaml` | Business 8080, management 8089, probes `/actuator/health/liveness` and `/readiness` on 8089. |
| `deploy/k8s/entry-gateway.yaml` | Optional HTTP entry forwarder (8088 / 8090). |
| `deploy/k8s/sample-consumer.yaml` | Outbox consumer. |
| `deploy/k8s/networkpolicy-sample.yaml` | Business HTTP only from entry-gateway; probes/metrics only from cluster CIDR; no Ingress on management ports. |
| `deploy/k8s/platform-app-shared-backends.snippet.yaml` | **WP9-3:** optional honest multi-replica patch (`replicas: 2` **and** both `jdbc` env vars). Not the default, not applied by CI. See `docs/release/single-replica-gate.md`. |
| `deploy/compose/docker-compose.yml` | Default local stack: **one** `platform-app`. Do not `--scale` this file alone. |
| `deploy/compose/docker-compose.shared-backends.yml` | **WP9-3:** optional honest Compose override (both `jdbc` backends + intended `deploy.replicas: 2`). Not a production deploy. |

### `entry-gateway`

| Piece | Path | Notes |
| --- | --- | --- |
| Process | `entry-gateway/` (`EntryGatewayApplication`, `UpstreamProxyFilter`, `GatewayRateLimitFilter`, `TrustedProxies`, `application.yml`) | Forwards HTTP to `platform-app`. Does **not** replace operator auth. Process-local rate limit (`GATEWAY_RATE_LIMIT_PERMITS` default 120 / 60s). Believes `X-Forwarded-For` only from `GATEWAY_TRUSTED_PROXIES`. **No TLS termination.** |
| Image / manifest | `entry-gateway/Dockerfile`, `deploy/k8s/entry-gateway.yaml` | In-cluster forwarder, not a public gateway product. Canvas + this package: do **not** grow it into Nginx/APISIX. |

### Upload cap (edge body size must align)

Generic cap is `platform.storage.upload.max-bytes` / `ObjectUploadLimits.DEFAULT_MAX_BYTES` = **50 MiB**. Spring `spring.servlet.multipart.max-file-size` defaults to 50MB; `max-request-size` defaults to **52MB** envelope slack (`application.yml`). Edge `client_max_body_size` / APISIX `client_max_body_size` should be at least 52MB on upload routes.

## WP9-0 [done] — inventory + ADR + edge recipe files

This slice writes the inventory, ADR 0021, and config-only recipes under `deploy/edge/`. No Java, no `entry-gateway` product change, no WP9-1.

本切片只写盘点、ADR 0021 与 `deploy/edge/` 配置配方。不改 Java，不把入口转发器改成网关产品，不启动 WP9-1。

Files / 文件:

- [`docs/adr/0021-observability-and-edge-recipe.md`](../adr/0021-observability-and-edge-recipe.md)
- [`deploy/edge/README.md`](../../deploy/edge/README.md)
- [`deploy/edge/nginx/subjex.conf`](../../deploy/edge/nginx/subjex.conf), [`deploy/edge/nginx/subjex-proxy.inc`](../../deploy/edge/nginx/subjex-proxy.inc)
- [`deploy/edge/apisix/apisix.yaml`](../../deploy/edge/apisix/apisix.yaml), [`deploy/edge/apisix/config-notes.yaml`](../../deploy/edge/apisix/config-notes.yaml)

Nginx syntax was validated on this box with nginx 1.26.3: dummy certificates, a temp prefix, `sudo /usr/sbin/nginx -t -p <prefix> -c <rewritten-conf>` → `syntax is ok` / `test is successful`. (Unprivileged `nginx -t` also reports `syntax is ok` but then fails to bind :80, which is expected.)

本机用 nginx 1.26.3 做了语法校验：占位证书 + 临时前缀，`sudo nginx -t` 通过。非特权用户 `nginx -t` 会在绑定 :80 时失败，属预期。

SHA：`eb60b4e`（`eb60b4ed215070a086ba6f231d587a775d860c73`）

## WP9-1 [done] — audit export and NDJSON SIEM sink / 审计导出与 NDJSON SIEM 投递

Date: 2026-11-20 (Asia/Shanghai). Feature SHA `4a9797d` (`4a9797dd9ec1ce2493623de90cff70ac9d4a0a4e`).

Canvas item 1 and the remaining acceptance “审计可导出” are implemented. No vendor SDK, no second outbox, no cursor table, no `entry-gateway` change, no dashboards or multi-replica recipe (WP9-2 / WP9-3).

画布要求 1 与验收「审计可导出」已落地。无厂商 SDK、无第二张出箱、无游标表、未改 `entry-gateway`，未启动仪表盘或多副本配方（WP9-2 / WP9-3）。

- Operator API: `POST /api/v1/audit/export?tenantId=&from=&to=&limit=` returns `application/x-ndjson`. Permission `audit.export` (V56, seeded to `platform-operator` only, not `platform-reader`). `OperatorTenantAccess.requireGranted` enforces tenant isolation; reserved tenant `platform` is denied unless the operator has that grant. Window cap P31D; row cap default 1000 / max 5000. The export itself is recorded as `audit.export`.
- `AuditSink` SPI with built-in file/stdout `NdjsonAuditSink` (`platform.audit.sink.enabled=false` by default). Fed from the existing write path via `SinkingAuditPort` wrapping `JdbcAuditPort`. Sink failures do not roll back `AuditPort.record`.
- Schema and SIEM shipping notes (Vector / Fluent Bit, generic wording): [`audit-ndjson-schema.md`](audit-ndjson-schema.md), [`deploy/edge/README.md`](../../deploy/edge/README.md).
- Tests: `AuditExportTest` 3/3 (own-tenant window, missing permission / foreign tenant / `platform` / window cap, sink on/off and no secret fields). Regression: `AuditQueryApiTest` 4, `ObjectAccessAuditTest` 5, `RepairTicketWorkflowAuditTest` 2, `OperatorPermissionMigrationTest` 2, `EndUserCrossRealmIsolationTest` 34 — **50/50**. No other permission-set assertion was updated. The known `OperatorBootstrapTest.readerRoleGetsOnlyReadPermissions` was not fixed.

## WP9-2 [done] — observability dashboards / 可观测看板

Date: 2026-11-22 (Asia/Shanghai). Canvas item “指标面板可后补” is now a repo-owned pack operators can actually import. No new observability product, no Grafana Cloud account, no extra production Actuator endpoints, no `entry-gateway` product change, no multi-replica or live edge landing.

画布「指标面板可后补」现已落成仓库内、运维可实际导入的套件。没有新的可观测产品，不要求 Grafana Cloud 账号，不增加生产 Actuator 暴露，不把 `entry-gateway` 改成网关产品，不启动多副本，也不宣称已在真实环境落地边缘 TLS。

This machine is **not** a production cluster. The files below are versioned recipes and a dashboard definition. They were not applied to a live Prometheus or Grafana, and they do not terminate TLS on a public edge.

本机**不是**生产集群。下面只是版本化的配方与看板定义，并未应用到一套正在运行的 Prometheus 或 Grafana，也没有在对公边缘终结 TLS。

Files / 文件:

- [`deploy/observability/README.md`](../../deploy/observability/README.md) — scrape, import, and operator views already in the product (health, `/audit`, NDJSON export/sink, dead letters, correlated logs)
- [`deploy/observability/prometheus-scrape.example.yml`](../../deploy/observability/prometheus-scrape.example.yml) — example jobs `platform-app` / `entry-gateway` / `sample-consumer` on `/actuator/prometheus`
- [`deploy/observability/grafana/subjex-platform-overview.json`](../../deploy/observability/grafana/subjex-platform-overview.json) — uid `subjex-platform-overview`; panels use stock series (`up`, `process_*`, `http_server_requests_seconds_*`, `jvm_*`, `hikaricp_*`, `logback_events_total`, `tomcat_threads_*`)

No new Java meters were added: ADR 0021 D1 asked to keep the existing Prometheus / health / audit baseline, and those series are already emitted by Spring Boot 4 / Micrometer 1.16. Tests parse the dashboard JSON and check the expected metric names and honesty wording; they do not start Grafana or scrape a cluster.

未新增 Java 指标：ADR 0021 D1 要求保持现有 Prometheus / 健康检查 / 审计基线，这些序列已由 Spring Boot 4 / Micrometer 1.16 发出。测试解析看板 JSON 并核对预期指标名与诚实表述；不启动 Grafana，也不抓取集群。

## WP9-3 [done] — optional multi-replica recipe / 可选多副本配方

Date: 2026-11-22 (Asia/Shanghai). Canvas item “多副本部署配方可后补” is now a tightened, test-gated optional recipe. The **published default stays `replicas: 1`**. Advertising `platform-app` `replicas > 1` is honest only when both shared JDBC backends are `jdbc`. This machine is **not** a production cluster: the files were not applied with `kubectl` or `docker compose`, and checking them in is not a live multi-replica deploy.

画布「多副本部署配方可后补」现已收成带测试门禁的可选配方。**已发布的默认仍是 `replicas: 1`**。对外宣称 `platform-app` 多副本，仅在两个共享 JDBC 后端都为 `jdbc` 时才诚实。本机**不是**生产集群：这些文件没有用 `kubectl` 或 `docker compose` 应用，写入仓库也不等于已在真实环境多副本上线。

Files / 文件:

- [`deploy/k8s/platform-app.yaml`](../../deploy/k8s/platform-app.yaml) — default `replicas: 1`, no silent JDBC-backend env (unchanged)
- [`deploy/k8s/platform-app-shared-backends.snippet.yaml`](../../deploy/k8s/platform-app-shared-backends.snippet.yaml) — optional patch: `replicas: 2` **and** `PLATFORM_RATE_LIMIT_BACKEND=jdbc` plus `PLATFORM_DELIVERY_CIRCUIT_BREAKER_BACKEND=jdbc`
- [`deploy/compose/docker-compose.yml`](../../deploy/compose/docker-compose.yml) — default still one `platform-app`; do not `--scale` this file alone
- [`deploy/compose/docker-compose.shared-backends.yml`](../../deploy/compose/docker-compose.shared-backends.yml) — optional override with both jdbc backends and intended `deploy.replicas: 2` (pass `--scale platform-app=2` on non-Swarm Compose)
- [`docs/release/single-replica-gate.md`](../release/single-replica-gate.md)

Still not claimed: full HA, HPA, shared `entry-gateway` rate limits, or shared blobs on local disk. Compose remains a laptop recipe (`local` profile, per-container object directory).

仍不宣称：完整 HA、HPA、入口转发器限流跨副本共享，或本地盘上的共享对象。Compose 仍是笔记本配方（`local` profile，对象目录按容器）。

Tests: `MultiReplicaRecipeHonestyTest` (default stays single-replica; official optional recipes include both jdbc backends; constructed dishonest recipes — N>1 with missing, `process`, or only-one backend — are rejected). The test parses files only. Regression: `ProductionConfigChecklistTest` still pins the default manifest at `replicas: 1` with no silent JDBC env.

### Next / 下一步

**Edge TLS and public rate-limit recipe tightening** — `deploy/edge/` is still a configuration sample (placeholder certificates and hostnames; Nginx / APISIX have not been landed on a real environment). A later slice may tighten those recipes. It must **not** pretend a production TLS or rate-limit deploy happened, and it must not start that work in this slice.

边缘 TLS 与对外限流配方收紧仍往后排：`deploy/edge/` 仍是配置样例（证书与域名为占位；Nginx / APISIX 尚未在真实环境落地）。后续切片可以收紧配方，但不得假装已在生产落地 TLS 或对外限流，本切片也不启动该项。
