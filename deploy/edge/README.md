# Edge recipe / 边缘配方（WP9-0, ADR 0021)

This folder is a **follow-along** public-edge recipe. It terminates TLS and applies rate limits with **Nginx or APISIX**. It is configuration only: no Java, and it does **not** turn `entry-gateway` into a gateway product.

本目录是可照着做的对公边缘配方：用 **Nginx 或 APISIX** 终结 TLS 并限流。仅配置，不改 Java，也不把 `entry-gateway` 改成网关产品。

Locked canvas (WP9): 写清边缘网关配方（Nginx 或 APISIX）：TLS、限流、控制台与终端路由分离；不要把现有入口转发器改造为网关产品。单副本仍可作为首次上线默认。

See ADR [`docs/adr/0021-observability-and-edge-recipe.md`](../../docs/adr/0021-observability-and-edge-recipe.md).

## Choose one / 二选一

- **Nginx (default walkthrough / 默认走查):** [`nginx/subjex.conf`](nginx/subjex.conf) + [`nginx/subjex-proxy.inc`](nginx/subjex-proxy.inc)
- **APISIX (peer alternative / 对等备选):** [`apisix/apisix.yaml`](apisix/apisix.yaml) + [`apisix/config-notes.yaml`](apisix/config-notes.yaml)

Both cover the same responsibilities / 两份配方覆盖相同职责:

- TLS termination (certificate and domain **placeholders** / 证书与域名为**占位**)
- HSTS (`max-age=31536000; includeSubDomains`; `preload` is open)
- Rate limits per route class: `/api/v1/auth/**`, `/api/v1/end-user/auth/**`, OTP `.../phone/request-code`, WeChat `/api/v1/end-user/auth/wechat/**`, general API
- Upload body size **52MB** on `/api/v1/end-user/objects` and `/api/v1/entities/` (50 MiB cap + Spring 52MB envelope)
- Real-IP / `X-Forwarded-*` headers; forward `traceparent`, `X-Correlation-Id`, `X-Request-Id`
- Block `/actuator` and `/actuator/**` from the public edge; do **not** Ingress management ports 8089/8090/8091
- Optional hostname split: `api.example.com` (API) vs `console.example.com` (operator `web/`)

Recommended default rates (changeable; not canvas-locked) / 建议默认速率（可改；画布未锁死）:

| Class | Path | Rate |
| --- | --- | --- |
| Operator auth | `/api/v1/auth/**` | 20 / minute / IP |
| End-user auth / WeChat | `/api/v1/end-user/auth/**` | 30 / minute / IP |
| OTP request-code | `/api/v1/end-user/auth/phone/request-code` | 5 / minute / IP |
| General API | `/api/v1/**` | 120 / minute / IP |

## Placeholders to replace / 使用前必换的占位

- `api.example.com`, `console.example.com`
- Certificate files or PEM blocks
- `platform-app:8080` / `console-web:80` upstreams
- Trusted hop CIDR in front of this edge (if any)

## Nginx quick start / Nginx 快速开始

```bash
# Install certificates at the paths in subjex.conf, copy subjex-proxy.inc into the include path,
# then test and reload. 把证书放到配置中的路径，复制 inc 到 include 路径，再测试并重载。
sudo cp deploy/edge/nginx/subjex-proxy.inc /etc/nginx/conf.d/subjex-proxy.inc
sudo nginx -t -c /path/to/subjex.conf
sudo nginx -s reload
```

HTTP port 80 redirects to HTTPS. Upstream is the business port only.

80 端口跳转到 HTTPS。上游只走业务口。

## APISIX quick start / APISIX 快速开始

Use standalone YAML (`config_provider: yaml`) as sketched in `apisix/config-notes.yaml`, replace PEM placeholders in `apisix.yaml`, and keep the Admin API off the public interface. Bind 443 for TLS; redirect 80 to HTTPS on the load balancer or a companion listener.

按 `config-notes.yaml` 用 standalone YAML 加载，替换 `apisix.yaml` 中的 PEM 占位，Admin API 不对公网开放。443 终结 TLS；80 跳转可在负载均衡或旁路监听上做。

## What this is not / 本配方不做

- It is not a Kubernetes Ingress manifest (see `deploy/k8s/` for in-cluster probes and NetworkPolicy).
- Audit export / SIEM is application-level (WP9-1), documented below; this edge does not terminate that path.
  审计导出 / SIEM 在应用侧（WP9-1），见下文；本边缘不承接这条投递路径。
- Grafana dashboards and the Prometheus scrape example live under [`deploy/observability/`](../observability/README.md) (WP9-2). This edge folder does not deploy them, and checking them in is not a live Grafana or TLS landing.
  Grafana 看板与 Prometheus 抓取样例在 [`deploy/observability/`](../observability/README.md)（WP9-2）。本边缘目录不部署它们，写入仓库也不等于已在真实环境落地 Grafana 或 TLS。
- Multi-replica (WP9-3) is a separate optional recipe under [`deploy/k8s/platform-app-shared-backends.snippet.yaml`](../k8s/platform-app-shared-backends.snippet.yaml) and [`deploy/compose/docker-compose.shared-backends.yml`](../compose/docker-compose.shared-backends.yml). Default k8s manifests stay at `replicas: 1`. This edge folder does not change that, and it is not a live deploy.
  多副本（WP9-3）是另一套可选配方，见上文路径。默认 k8s 清单仍是 `replicas: 1`。本边缘目录不改这一点，也不是一次真实部署。
- It does not change `entry-gateway`. That process remains an optional in-cluster HTTP forwarder without public TLS. A first launch may omit it and let this edge proxy `platform-app` directly.
  不修改 `entry-gateway`。它仍是可选的集群内 HTTP 转发器，不对公终结 TLS。首次上线可以省略它，由本边缘直接反代 `platform-app`。


## Audit NDJSON to a SIEM / 审计 NDJSON 送到 SIEM（WP9-1）

Application-level export is `POST /api/v1/audit/export` (permission `audit.export`) and an optional file/stdout NDJSON sink (`platform.audit.sink.enabled`, off by default). Field schema: [`docs/observability/audit-ndjson-schema.md`](../../docs/observability/audit-ndjson-schema.md).

应用侧导出是 `POST /api/v1/audit/export`（权限 `audit.export`）以及可选的文件/标准输出 NDJSON sink（`platform.audit.sink.enabled`，默认关闭）。字段模式见上文链接。

A collector (Vector, Fluent Bit, or similar) can tail the sink file and ship lines to a SIEM. This edge recipe does not have to terminate that shipping path; keep `/actuator/**` blocked as above.

可用 Vector、Fluent Bit 等采集器跟踪 sink 文件并送到 SIEM。本边缘配方不必承接这条投递路径；仍按上文拦截 `/actuator/**`。
