# Edge recipe / 边缘配方（ADR 0021, tightened in this slice）

This folder is a **follow-along** public-edge recipe. It terminates TLS and applies rate limits with **Nginx or APISIX**. It is configuration only: no Java, and it does **not** turn `entry-gateway` into a gateway product.

本目录是可照着做的对公边缘配方：用 **Nginx 或 APISIX** 终结 TLS 并限流。仅配置，不改 Java，也不把 `entry-gateway` 改成网关产品。

Locked canvas (WP9): 写清边缘网关配方（Nginx 或 APISIX）：TLS、限流、控制台与终端路由分离；不要把现有入口转发器改造为网关产品。单副本仍可作为首次上线默认。

See ADR [`docs/adr/0021-observability-and-edge-recipe.md`](../../docs/adr/0021-observability-and-edge-recipe.md).

**This machine is not a production cluster and has no live public edge to land on.** Checking these files in, or tightening the wording, is **not** a production TLS or rate-limit deploy. Do not apply Nginx or APISIX here as if it were production, and do not start a public-facing proxy for that purpose.

**本机不是生产集群，也没有可落地的对公边缘。** 把这些文件写入仓库或收紧表述，**都不等于**已在生产落地 TLS 或对外限流。不要在这里把 Nginx / APISIX 当成生产去应用，也不要为此启动对公代理。

## Choose one / 二选一

- **Nginx (default walkthrough / 默认走查):** [`nginx/subjex.conf`](nginx/subjex.conf) + [`nginx/subjex-proxy.inc`](nginx/subjex-proxy.inc)
- **APISIX (peer alternative / 对等备选):** [`apisix/apisix.yaml`](apisix/apisix.yaml) + [`apisix/config-notes.yaml`](apisix/config-notes.yaml)

Both cover the same responsibilities / 两份配方覆盖相同职责:

- TLS termination (certificate and domain **placeholders** / 证书与域名为**占位**)
- HTTP → HTTPS (`301` on port 80)
- HSTS (`max-age=31536000; includeSubDomains`; `preload` is open)
- Rate limits per route class: `/api/v1/auth/**`, `/api/v1/end-user/auth/**`, OTP `.../phone/request-code`, WeChat `/api/v1/end-user/auth/wechat/**`, general API
- Upload body size **52MB** on `/api/v1/end-user/objects` and `/api/v1/entities/` (50 MiB cap + Spring 52MB envelope)
- Real-IP / `X-Forwarded-*` headers; forward `traceparent`, `X-Correlation-Id`, `X-Request-Id`
- Block `/actuator` and `/actuator/**` from the public edge (including on port 80); do **not** Ingress management ports 8089/8090/8091
- Optional hostname split: `api.example.com` (API) vs `console.example.com` (operator `web/`)

Recommended default rates (changeable; not canvas-locked) / 建议默认速率（可改；画布未锁死）:

| Class | Path | Rate |
| --- | --- | --- |
| Operator auth | `/api/v1/auth/**` | 20 / minute / IP |
| End-user auth / WeChat | `/api/v1/end-user/auth/**` | 30 / minute / IP |
| OTP request-code | `/api/v1/end-user/auth/phone/request-code` | 5 / minute / IP |
| General API | `/api/v1/**` | 120 / minute / IP |

## Placeholders to replace / 使用前必换的占位

These names and files are **documentation placeholders** (IANA `example.com`, `PLACEHOLDER_*` PEM tokens, example certificate paths). They are not a production inventory.

这些名字与文件是**文档占位**（IANA `example.com`、`PLACEHOLDER_*` PEM 标记、示例证书路径）。不是生产清单。

| Replace / 替换 | Nginx | APISIX |
| --- | --- | --- |
| Hostnames / 主机名 | `server_name api.example.com` and `console.example.com` | `hosts` / `snis` with the same names |
| Certificates / 证书 | Paths `/etc/nginx/certs/api.example.com.crt` (and `.key`); same for `console.example.com` | PEM blocks in `ssl:` that currently say `PLACEHOLDER_API_CERT` / `PLACEHOLDER_API_KEY` (and the console pair) |
| Upstreams / 上游 | `platform-app:8080` (business port only) and optional `console-web:80` | The same node names; never `8089` / `8090` / `8091` |
| Trusted hop CIDR / 前一跳 CIDR | Uncomment and edit `set_real_ip_from` if a CDN/LB sits in front | Uncomment `real-ip` / `trusted_addresses` in `apisix.yaml` |

**How to replace certificates / 如何替换证书**

1. Obtain a certificate for **your** hostname from your usual issuer (the recipe does not run ACME, certbot, or a vendor dashboard).
   用你常用的颁发者给**自己的**主机名签发证书（本配方不跑 ACME、certbot 或厂商控制台）。
2. Nginx: install the cert and key at the paths you put in `ssl_certificate` / `ssl_certificate_key` (or change those paths). The repository only stores the path strings, never the key file.
   Nginx：把证书和私钥放到配置中的路径（或改路径）。仓库只保存路径字符串，不保存私钥文件。
3. APISIX: copy `apisix.yaml` **outside the repo** (or into a private, untracked location), replace every `PLACEHOLDER_*` token with the real PEM, and load that private copy. Do **not** paste a real PEM back into this git tree.
   APISIX：把 `apisix.yaml` **复制到仓库之外**（或未跟踪的私有位置），把所有 `PLACEHOLDER_*` 换成真实 PEM，再加载那份私有副本。**不要**把真实 PEM 贴回本 git 树。
4. `EdgeRecipeHonestyTest` rejects committed private-key PEM that is not a `PLACEHOLDER_*` token, and rejects hostnames that are not documentation placeholders (`example.com` / `example.net` / `example.org`) or in-cluster service names.
   门禁测试会拒绝仓库里非 `PLACEHOLDER_*` 的私钥 PEM，也会拒绝不是文档占位域名或集群内服务名的主机名。

## HTTP to HTTPS / HTTP 跳转到 HTTPS

- Nginx: the port-80 server returns `301 https://$host$request_uri`, except `/actuator` which is `404` on port 80 as well.
  Nginx：80 端口返回 `301`，但 `/actuator` 在 80 端口同样是 `404`。
- APISIX: `global_rules` includes `redirect.http_to_https` (301). Listen on port 80 as sketched in `config-notes.yaml`, or do the same 301 on a front load balancer.
  APISIX：全局规则含 `redirect.http_to_https`（301）。按 `config-notes.yaml` 监听 80，或在前置负载均衡上做同样的 301。

## Management ports and `/actuator` / 管理端口与 `/actuator`

The public edge must not publish `/actuator`, `/actuator/**`, or management ports **8089 / 8090 / 8091**. Upstream is the business port only (`platform-app:8080`). Probes and Prometheus stay on the management port, inside the cluster (see [`docs/release/probes-and-network.md`](../../docs/release/probes-and-network.md)).

对公边缘不得发布 `/actuator`、`/actuator/**` 或管理端口 **8089 / 8090 / 8091**。上游只走业务口（`platform-app:8080`）。探针与 Prometheus 仍在集群内的管理端口。

## Operator checklist / 运维清单

- [ ] Pick **one** of Nginx or APISIX (not both on the same public ports).
      二者选一（不要在同一对公端口上同时开）。
- [ ] Replace placeholder hostnames and certificate paths / PEM tokens in a **private** copy.
      在**私有**副本里替换占位主机名与证书路径 / PEM。
- [ ] Point upstreams at the business port only; do not Ingress `:8089` / `:8090` / `:8091`.
      上游只指业务口；管理口不要走 Ingress。
- [ ] Confirm `/actuator` is 404 on the public host names (including via port 80).
      确认对公主机名上 `/actuator` 为 404（含 80 端口）。
- [ ] Confirm HTTP on port 80 redirects to HTTPS (except the actuator block).
      确认 80 端口 HTTP 跳到 HTTPS（actuator 拦截除外）。
- [ ] Review the four rate-limit classes before going public; the numbers above are defaults, not a canvas lock.
      对公前复核四档限流；上表是默认值，不是画布锁死的数字。
- [ ] Keep the APISIX Admin API off the public interface if you use APISIX.
      若用 APISIX，Admin API 不对公开放。
- [ ] Do not commit real certificates, private keys, or production hostnames to this repository.
      不要把真实证书、私钥或生产主机名提交进本仓库。

## Nginx quick start / Nginx 快速开始

```bash
# Install certificates at the paths in subjex.conf, copy subjex-proxy.inc into the include path,
# then test and reload on *your* edge host — not as a production landing on this machine.
# 把证书放到配置中的路径，复制 inc 到 include 路径，再在*你的*边缘主机上测试并重载——
# 不要把本机当成生产边缘去落地。
sudo cp deploy/edge/nginx/subjex-proxy.inc /etc/nginx/conf.d/subjex-proxy.inc
sudo nginx -t -c /path/to/subjex.conf
sudo nginx -s reload
```

HTTP port 80 redirects to HTTPS. Upstream is the business port only.

80 端口跳转到 HTTPS。上游只走业务口。

## APISIX quick start / APISIX 快速开始

Use standalone YAML (`config_provider: yaml`) as sketched in `apisix/config-notes.yaml`, replace PEM placeholders in a **private** copy of `apisix.yaml`, and keep the Admin API off the public interface. Bind 443 for TLS; redirect 80 to HTTPS in APISIX (global rule already in `apisix.yaml`) or on the load balancer.

按 `config-notes.yaml` 用 standalone YAML 加载，在 `apisix.yaml` 的**私有**副本中替换 PEM 占位，Admin API 不对公网开放。443 终结 TLS；80 跳转已写在 `apisix.yaml` 的全局规则中，也可改在负载均衡上做。

## What this is not / 本配方不做

- It is **not** a live production TLS or rate-limit landing, and this machine has no public edge to apply it on.
  **不是**一次真实的生产 TLS 或对外限流落地，本机也没有可应用的对公边缘。
- It does not include ACME, certbot, Let's Encrypt automation, a Kubernetes Ingress, a WAF, a CDN, DDoS protection, or mutual TLS.
  不含 ACME、certbot、Let's Encrypt 自动化、Kubernetes Ingress、WAF、CDN、DDoS 防护或双向 TLS。
- It is not a Kubernetes Ingress manifest (see `deploy/k8s/` for in-cluster probes and NetworkPolicy).
- Audit export / SIEM is application-level (WP9-1), documented below; this edge does not terminate that path.
  审计导出 / SIEM 在应用侧（WP9-1），见下文；本边缘不承接这条投递路径。
- Grafana dashboards and the Prometheus scrape example live under [`deploy/observability/`](../observability/README.md) (WP9-2). This edge folder does not deploy them.
  Grafana 看板与 Prometheus 抓取样例在 [`deploy/observability/`](../observability/README.md)（WP9-2）。本边缘目录不部署它们。
- Multi-replica (WP9-3) is a separate optional recipe under [`deploy/k8s/platform-app-shared-backends.snippet.yaml`](../k8s/platform-app-shared-backends.snippet.yaml) and [`deploy/compose/docker-compose.shared-backends.yml`](../compose/docker-compose.shared-backends.yml). Default k8s manifests stay at `replicas: 1`.
  多副本（WP9-3）是另一套可选配方。默认 k8s 清单仍是 `replicas: 1`。
- It does not change `entry-gateway`. That process remains an optional in-cluster HTTP forwarder without public TLS. A first launch may omit it and let this edge proxy `platform-app` directly.
  不修改 `entry-gateway`。它仍是可选的集群内 HTTP 转发器，不对公终结 TLS。首次上线可以省略它，由本边缘直接反代 `platform-app`。

## Audit NDJSON to a SIEM / 审计 NDJSON 送到 SIEM（WP9-1）

Application-level export is `POST /api/v1/audit/export` (permission `audit.export`) and an optional file/stdout NDJSON sink (`platform.audit.sink.enabled`, off by default). Field schema: [`docs/observability/audit-ndjson-schema.md`](../../docs/observability/audit-ndjson-schema.md).

应用侧导出是 `POST /api/v1/audit/export`（权限 `audit.export`）以及可选的文件/标准输出 NDJSON sink（`platform.audit.sink.enabled`，默认关闭）。字段模式见上文链接。

A collector (Vector, Fluent Bit, or similar) can tail the sink file and ship lines to a SIEM. This edge recipe does not have to terminate that shipping path; keep `/actuator/**` blocked as above.

可用 Vector、Fluent Bit 等采集器跟踪 sink 文件并送到 SIEM。本边缘配方不必承接这条投递路径；仍按上文拦截 `/actuator/**`。
