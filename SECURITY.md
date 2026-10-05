# Security policy / 安全策略

## Supported versions / 支持的版本

No release has been published. Only the `main` branch (`0.1.0-SNAPSHOT`) receives fixes.
尚未发布任何版本，只有 `main`（`0.1.0-SNAPSHOT`）接受修复。

## Reporting a vulnerability / 报告漏洞

Please do **not** open a public issue for a vulnerability. Use GitHub private vulnerability reporting on this repository (Security → Report a vulnerability). If that option is not available, open an issue titled "security contact request" without any details and a maintainer will reply with a private channel.
请**不要**用公开 issue 报告漏洞。请使用本仓库 GitHub 的私密漏洞报告（Security → Report a vulnerability）。如果该入口不可用，请开一个不含细节、标题为 “security contact request” 的 issue，维护者会回复私下联系方式。

## Deployment boundary / 部署边界

subjex is a pre-release framework skeleton. **Run it only on a trusted, private network.** The defaults below are acceptable for a laptop or an internal evaluation environment and are not acceptable on the public internet.
subjex 是发布前的框架骨架。**只应运行在受信的私有网络中。** 下列默认行为适合开发机或内网评估，不适合公网。

## Known limitations / 已知安全限制

1. **Operator auth is HTTP Basic on every request.** No SSO/OIDC, no MFA, no lockout after failed sign-ins. Terminate TLS in front of every process; the processes themselves do not serve TLS.
   操作员认证是每次请求带 HTTP Basic；无 SSO/OIDC、无 MFA、无登录失败锁定。进程本身不提供 TLS，需在前面终结 TLS。
2. **Console sessions store the Basic header encrypted at rest (memory and Redis).** The `web/` console keeps only a random session id in the browser (httpOnly, `sameSite=strict`). Both the process-memory Map and Redis store `{ loginName, credentialHeaderEnc, expiresAtMillis }` where `credentialHeaderEnc` is AES-256-GCM ciphertext — plaintext Basic is never kept in the session store. Redis requires `OPERATOR_SESSION_SECRET` (≥ 32 characters; multi-replica must share it). Without Redis, the same secret is used when set; otherwise a process-ephemeral key encrypts memory sessions (a Map dump alone is not enough without that key). Decryption happens only while building the upstream `Authorization` header for a request. Sessions expire after 8 hours. Platform-issued operator API tokens (no password retained after login) remain a future hardening step.
   控制台会话在静态存储中加密 Basic 头（内存与 Redis 相同形状）。浏览器只持有随机会话号。进程内存 Map 与 Redis 都只存 `credentialHeaderEnc`（AES-256-GCM），会话存储中从无明文 Basic。Redis 必填 `OPERATOR_SESSION_SECRET`（≥32 字符，多副本共用）。无 Redis 时：有密钥则用；否则用进程内临时密钥加密内存会话。仅在拼上游 `Authorization` 时解密。会话 8 小时过期。平台签发操作员 API 令牌仍是后续加固项。
3. **Outbox delivery uses HMAC; TLS is required outside `local`.** Frames are `SUBJEX-OUTBOX 2` and authenticate with HMAC-SHA256 over shared secret `OUTBOX_HMAC_SECRET` (≥ 32 characters) — the operator password is **not** on the wire. Timestamp skew is limited to 5 minutes. TLS (consumer server PKCS12 + publisher truststore) is required when the `local` profile is not active and `platform.delivery.allow-insecure` is false; set `OUTBOX_TLS_ENABLED=true` plus `OUTBOX_TLS_KEYSTORE_*` / `OUTBOX_TLS_TRUSTSTORE_*` (see `deploy/k8s`). **Local escape hatch:** `SPRING_PROFILES_ACTIVE=local` (compose/quickstart) allows plaintext TCP with the default local HMAC in `application-local.yml` (`change-me-outbox-hmac-secret-local!!`); override the secret before any shared network. Mutual TLS (client certificate) and short-lived platform-issued delivery tokens remain future hardening.
   出箱用 HMAC 认证；非 `local` 必须 TLS。帧为 `SUBJEX-OUTBOX 2`，以共享密钥 `OUTBOX_HMAC_SECRET`（≥32 字符）做 HMAC-SHA256，**帧上不再带操作员口令**；时间戳偏差上限 5 分钟。未启用 `local` 且未显式 `allow-insecure` 时必须开 TLS（消费者服务端 PKCS12 + 发布方信任库）。**本机逃生舱：** `SPRING_PROFILES_ACTIVE=local` 允许明文 TCP，并使用 `application-local.yml` 中的本地默认 HMAC；上共享网络前请覆盖密钥。双向 TLS（客户端证书）与平台签发的短时投递令牌仍是后续加固项。
4. **Gateway client id and `X-Forwarded-For`.** By default `entry-gateway` ignores `X-Forwarded-For` and rate-limits by the TCP remote address. Set `gateway.trusted-proxies` (env `GATEWAY_TRUSTED_PROXIES`, comma-separated IPs/CIDRs) to the proxies that connect **directly** to the gateway; only then is the header read, right to left, skipping trusted hops, and the first untrusted hop is the client. List only proxies you control that append the real peer address — a too-broad range (e.g. `0.0.0.0/0`) brings back header spoofing. Without trusted proxies, every client behind one NAT/proxy shares one bucket. Tomcat's own forwarded-header handling is pinned off (`server.forward-headers-strategy: none`). The header is still forwarded to `platform-app` unchanged; do not use it there for security decisions. Limits are counted per gateway process.
   网关客户端标识与 `X-Forwarded-For`：默认忽略该头，按 TCP 远端地址限流。把**直连**网关的代理写进 `gateway.trusted-proxies`（环境变量 `GATEWAY_TRUSTED_PROXIES`，逗号分隔的 IP/CIDR）后才读该头：从右往左、跳过可信跳，第一个不可信跳即客户端。只填你掌控、且会追加真实来源地址的代理；范围过宽（如 `0.0.0.0/0`）等于重新允许伪造。不配置时，同一 NAT/代理后的客户端共用一个计数。Tomcat 自带的转发头处理已固定关闭（`server.forward-headers-strategy: none`）。该头仍原样转发给 `platform-app`，那边不要据此做安全判断。计数按网关进程各算各的。
5. **Tenants are auto-created on first task submission; operator–tenant grants are required.** `X-Tenant-Id` must be present on tenant-scoped paths (`/tasks/**`, and declaration `tenantScoped` APIs). The operator must also hold a row in `operator_tenant_grant` for that tenant id, or the wildcard `*`. Missing header or missing grant → 403 (fail-closed). Local seed and one-shot bootstrap grant `*` to the provisioned operator so laptop flows keep working. `JdbcTaskMessagePort` still inserts an unknown tenant as `ACTIVE` when a granted submission arrives — there is still no separate tenant admin UI.
   首次提交任务仍会自动建租户；但操作员必须先有租户授权。租户作用域路径要带 `X-Tenant-Id`，且 `operator_tenant_grant` 中须有该租户或通配 `*`；缺头或缺授权 → 403。本地种子与一次性开通给开通的操作员写 `*`。未知租户在获准提交时仍会写成 `ACTIVE`；尚无独立租户管理界面。
6. **Metrics and probes are anonymous.** `/actuator/prometheus`, `/actuator/health/liveness` and `/actuator/health/readiness` need no auth on all three processes. Rely on network isolation.
   指标与探针端点匿名，靠网络隔离。
7. **Local seed and one-shot bootstrap; runtime operator management APIs exist.** Profile `local` seeds `platform-operator` / `change-me` (with `*` tenant grant); `deploy/compose` enables `local`. Never enable `local` outside a laptop. Outside `local`, provision with the gated bootstrap: `java -jar platform-app.jar --platform.operator.bootstrap=true` plus `PLATFORM_OPERATOR_LOGIN` / `PLATFORM_OPERATOR_PASSWORD` (optional `PLATFORM_OPERATOR_ROLE`); it upserts one operator (role + `*` grant), prints `BOOTSTRAP` warnings, and exits. The flag defaults to **false** — do not leave it on in compose/k8s. After bootstrap, use `/api/v1/operators/**` (permission `operator.manage`) to list/create/disable/enable, assign tenant grants, and change another operator's password; any signed-in operator may `POST /api/v1/operators/me/password` with the current password. The `web/` console has an Operators page for self password change and (with `operator.manage`) list/disable/enable. Hand-written SQL still works (`docs/operator-permissions.md`).
   `local` 会写入 `platform-operator` / `change-me`（含 `*` 租户授权），compose 默认启用 `local`。开发机以外不要启用。非 local 用一次性开通（写角色与 `*` 授权后退出，默认关）。开通后用 `/api/v1/operators/**`（`operator.manage`）做列表/创建/禁用/启用、租户授权、改他人口令；任何人可带当前口令 `POST .../me/password`。控制台有操作员页。手写 SQL 仍可用。
8. **Images are not published or scanned.** Build them yourself from the Dockerfiles (non-root uid 10001).
   镜像未发布、未做漏洞扫描，需自行构建（非 root uid 10001）。

The full assessment these items come from is `docs/pre-release-assessment.md`.
以上条目来自 `docs/pre-release-assessment.md`。
