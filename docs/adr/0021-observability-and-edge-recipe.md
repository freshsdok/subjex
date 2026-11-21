# ADR 0021 — Observability baseline and edge recipe (WP9)

**Status:** Accepted (WP9-0 inventory + recipe; export/SIEM not yet implemented)  
**Date:** 2026-11-20 (Asia/Shanghai)

## Context / 背景

WP8 is closed (`2be7e94`, acceptance `2569f52`). The locked base-work canvas’ next package is **WP9 · 可观测与扩展** (P2, non-blocking). Verbatim scope:

- 目标：可与医疗业务并行，不阻塞底座首次对外。
- 审计导出或 SIEM 投递端口。
- 写清边缘网关配方（Nginx 或 APISIX）：TLS、限流、控制台与终端路由分离；不要把现有入口转发器改造为网关产品。
- 多副本部署配方和指标面板可后补。
- 验收：有可照着操作的边缘部署说明；审计可导出；单副本仍可作为首次上线默认。
- 待拍板建议默认：用 Nginx 或 APISIX 终结 TLS 与限流；不扩建现有入口转发器；具体软件可在 WP9 写进配方。

Factual inventory: [`docs/observability/wp9-observability-edge-progress.md`](../observability/wp9-observability-edge-progress.md). In short: health/readiness and Prometheus already live on a **management** port; logs already carry `correlationId` / `traceId`; OTLP is opt-in; audit can be **queried** (`GET /api/v1/audit`) but not **exported** to a SIEM; `entry-gateway` is a TLS-less HTTP forwarder; `deploy/k8s` defaults to one replica.

WP8 已关闭。画布 WP9 不阻塞首次对外。今日已有管理口探针与 Prometheus、关联日志与可选 OTLP；审计可查询尚未导出；入口转发器不终结 TLS。单副本仍是默认。

## Decision / 决策

### D1 — Metrics, health, tracing, and log-correlation baseline

Keep and document the existing baseline; do not replace it in WP9-0.

沿用并写清现有基线；WP9-0 不替换它。

- **Health:** `GET /actuator/health/liveness` and `GET /actuator/health/readiness` on `management.server.port` (platform-app **8089**, entry-gateway **8090**, sample-consumer **8091`). Anonymous for kubelet only; `show-details: never`. Operator `GET /admin/health` stays authenticated.
- **Metrics:** Micrometer Prometheus registry; scrape `GET /actuator/prometheus` on the management port only. Grafana / pre-built dashboards stay deferred (canvas: 指标面板可后补).
- **Tracing:** Existing OpenTelemetry SDK. OTLP export remains opt-in via `platform.tracing.otlp-endpoint` / `PLATFORM_OTLP_ENDPOINT` / `OTEL_EXPORTER_OTLP_ENDPOINT`. Empty endpoint keeps the discarding exporter.
- **Correlation:** `RequestObservabilityFilter` mints or honors `X-Correlation-Id` / `X-Request-Id`, extracts W3C `traceparent`, writes MDC `correlationId` / `traceId`, echoes headers. The edge **must** forward `traceparent` and `X-Correlation-Id` / `X-Request-Id` unchanged.
- **Logs:** Keep the console pattern in `application.yml` (`correlationId=%X{correlationId:-} traceId=%X{traceId:-}`).

### D2 — Actuator exposure in `prod` (minimal)

Production (`prod` / `production` profiles, inherited from base `application.yml`) exposes **only**:

生产环境（`prod` / `production`，继承基础 `application.yml`）只暴露：

- `health` (including liveness/readiness probes, `show-details: never`)
- `prometheus`

These listen on the **management port**, never on the business port (8080). The public edge **must not** publish the management port or `/actuator/**`.

**Never** expose `env`, `heapdump`, `threaddump`, `configprops`, `beans`, `mappings`, `logfile`, `shutdown`, or other diagnostic endpoints on any Internet-facing listener. They are not in `management.endpoints.web.exposure.include` today; WP9 must not add them to prod.

禁止在任何对公监听上暴露 `env` / `heapdump` 等诊断端点。今日未列入 include；WP9 不得把它们加入生产。

### D3 — Edge recipe: Nginx or APISIX, not `entry-gateway`

The public edge terminates TLS and applies rate limits with **Nginx or APISIX**. Both recipes ship under `deploy/edge/`. **Nginx is the default walkthrough**; APISIX is a peer alternative with the same responsibilities. The canvas does not pick a single vendor.

对公边缘用 **Nginx 或 APISIX** 终结 TLS 并限流。两份配方都在 `deploy/edge/`。**Nginx 为默认走查**；APISIX 是职责相同的对等备选。画布未指定唯一软件。

`entry-gateway` stays an optional in-cluster HTTP forwarder. It does not terminate TLS, is not the public Ingress, and **must not** be expanded into a gateway product (canvas locked). A first launch may skip it and let the edge proxy `platform-app:8080` directly.

`entry-gateway` 仍是可选的集群内 HTTP 转发器，不终结 TLS，不是对公 Ingress，**不得**扩建为网关产品。首次上线可以跳过它，由边缘直接反代 `platform-app:8080`。

Edge responsibilities (all recipes) / 边缘职责（两份配方均须覆盖）：

1. **TLS termination** at the edge; the business process itself still does not present public TLS (WP1). Certificates and domains are placeholders (`api.example.com`, `console.example.com`).
2. **HSTS** on HTTPS responses (`Strict-Transport-Security`, default `max-age=31536000; includeSubDomains`; `preload` is open).
3. **Rate limits per route class** (recommended defaults, changeable — exact numbers are not canvas-locked):
   - `/api/v1/auth/**` (operator login, MFA, OIDC, refresh, logout, IdP): tighter, default **20 requests / minute / IP**.
   - `/api/v1/end-user/auth/**` (refresh, logout, WeChat login/bind): default **30 / minute / IP**.
   - WeChat / OTP: `/api/v1/end-user/auth/phone/request-code` default **5 / minute / IP**; `/api/v1/end-user/auth/phone/login` and `/api/v1/end-user/auth/wechat/**` inherit the end-user auth class or the tighter OTP class where listed.
   - General API: default **120 / minute / IP** (aligned with `entry-gateway`’s process default).
   - Upload routes may share the general class but allow a large body (see below).
4. **Upload body size** aligned with the 50 MiB cap: allow at least **52MB** (`client_max_body_size 52m`) on upload prefixes (`/api/v1/end-user/objects`, `/api/v1/entities/`) to match `spring.servlet.multipart.max-request-size`. Other routes may use a smaller default (1m).
5. **Real-IP / forwarded headers:** set `X-Real-IP`, append `X-Forwarded-For`, set `X-Forwarded-Proto` and `X-Forwarded-Host`. If a trusted hop sits in front of this edge, honor its forwarded headers only from a placeholder trusted CIDR. Downstream `GATEWAY_TRUSTED_PROXIES` / app proxy trust must list this edge, never “all private ranges.”
6. **Block actuator from the public edge:** reject `/actuator`, `/actuator/**` (404/403). Do not route management ports (8089/8090/8091) on Ingress.
7. **Console vs end-user route separation:** path classes as above. Optional split hostnames: `console.example.com` for the operator `web/` console; `api.example.com` for `/api/v1/**` (end-user H5 / mini-program uses the API host only). The edge must not expose operator console static/admin routes on the end-user API server name if hosts are split.
8. **Trace headers:** forward `traceparent`, `X-Correlation-Id`, `X-Request-Id`.

HTTP→HTTPS redirect on port 80. Placeholders only for certs and server names.

### D4 — Single replica remains the first-launch default

Unchanged from WP1 / `deploy/k8s/platform-app.yaml`: **`replicas: 1`**. Multi-replica recipes and Grafana dashboards are explicitly later (canvas 要求 3). Do not advertise multi-replica unless shared JDBC backends for rate-limit and delivery circuit-breaker are on.

与 WP1 一致：首次上线默认单副本。多副本配方与指标面板后补。

### D5 — Audit export is a later slice, not this one

Canvas acceptance includes “审计可导出.” WP9-0 does not add a port or a SIEM sink. **WP9-1** will add an audit-export / SIEM delivery port on top of the existing `AuditPort` / `audit_entry` (no second audit bus). Format and vendor are open.

画布验收含「审计可导出」。WP9-0 不实现该端口；留给 WP9-1。格式与厂商待开放点。

## Consequences / 后果

- Operators can follow `deploy/edge/README.md` and the Nginx or APISIX files without turning `entry-gateway` into a product.
- Prod actuator stays minimal and cluster-internal; public clients never see `env` / `heapdump`.
- Correlation and traces continue to work if the edge forwards the existing headers.
- Audit export and dashboards remain unfinished until later slices.

## Proposed slices / 建议切片

- **WP9-0** — this slice: inventory, ADR 0021, `deploy/edge/` recipes (config only).
- **WP9-1** — audit export / SIEM delivery port (canvas item 1; remaining acceptance “审计可导出”).
- **WP9-2** — optional Prometheus scrape / Grafana dashboard pack (canvas: 指标面板可后补).
- **WP9-3** — optional multi-replica deploy recipe (canvas: 多副本部署配方可后补; still gated by WP1 shared backends).

## Open points / 开放点（画布未拍板）

The canvas does not settle the following; recommended defaults above may change later.

画布未拍死以下事项；上文建议默认以后可改。

- A single required edge binary (Nginx vs APISIX): both are valid; Nginx is the default walkthrough only.
- Exact rate-limit numbers and whether limits are per IP, per tenant, or per subject.
- HSTS `preload` and the exact `max-age`.
- Whether API and operator console **must** use separate hostnames, or path separation on one host is enough.
- Whether `entry-gateway` remains in the hop behind the edge, or is omitted.
- Audit export format (JSONL, CEF, syslog, HTTP webhook, …) and SIEM vendor.
- Trace sampling policy beyond today’s always-on in-process spans + opt-in OTLP.
- When (or whether) to ship Grafana dashboards and a multi-replica recipe.
