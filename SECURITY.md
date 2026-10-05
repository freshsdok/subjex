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
2. **Console sessions still need the Basic header in process memory; Redis stores it encrypted.** The `web/` console keeps only a random session id in the browser (httpOnly, `sameSite=strict`). With no Redis, the server holds `{ loginName, credentialHeader }` in process memory (dev / single-node / unit tests). When `SESSION_REDIS_URL` (or `REDIS_URL`) is set, Redis stores `{ loginName, credentialHeaderEnc, expiresAtMillis }` where `credentialHeaderEnc` is AES-256-GCM ciphertext; decryption uses `OPERATOR_SESSION_SECRET` (≥ 32 characters, required whenever Redis sessions are enabled — multi-replica must share the same secret). A Redis dump alone is not enough to recover the Basic header without that secret, but protect Redis anyway (auth, network isolation, TLS) and treat the secret like a master key. Sessions expire after 8 hours. Platform-issued operator API tokens (no password retained after login) remain a future hardening step.
   控制台会话：浏览器只持有随机会话号。未配 Redis 时服务端进程内存仍持有 Basic 头（开发/单机/单测）。配置 `SESSION_REDIS_URL`（或 `REDIS_URL`）后，Redis 只存 AES-256-GCM 加密后的 `credentialHeaderEnc`；解密依赖 `OPERATOR_SESSION_SECRET`（至少 32 字符，启用 Redis 会话时必填，多副本须共用同一密钥）。仅有 Redis 转储、没有该密钥拿不到 Basic 头；仍应给 Redis 做认证/隔离/TLS，并把密钥当主密钥保管。会话 8 小时过期。登录后改用平台签发的操作员 API 令牌（不再保留口令）仍是后续加固项。
3. **Outbox socket has no TLS and carries operator credentials.** `platform-app` pushes outbox events to `sample-consumer` over plain TCP; each frame includes the configured operator name and password so the consumer can authenticate the sender. Keep that socket on a private network segment.
   出箱套接字无 TLS，且帧里带操作员凭据。`platform-app` 经明文 TCP 推送出箱事件，每帧包含配置的操作员名与口令。该套接字必须留在私有网段。
4. **Gateway client id and `X-Forwarded-For`.** By default `entry-gateway` ignores `X-Forwarded-For` and rate-limits by the TCP remote address. Set `gateway.trusted-proxies` (env `GATEWAY_TRUSTED_PROXIES`, comma-separated IPs/CIDRs) to the proxies that connect **directly** to the gateway; only then is the header read, right to left, skipping trusted hops, and the first untrusted hop is the client. List only proxies you control that append the real peer address — a too-broad range (e.g. `0.0.0.0/0`) brings back header spoofing. Without trusted proxies, every client behind one NAT/proxy shares one bucket. Tomcat's own forwarded-header handling is pinned off (`server.forward-headers-strategy: none`). The header is still forwarded to `platform-app` unchanged; do not use it there for security decisions. Limits are counted per gateway process.
   网关客户端标识与 `X-Forwarded-For`：默认忽略该头，按 TCP 远端地址限流。把**直连**网关的代理写进 `gateway.trusted-proxies`（环境变量 `GATEWAY_TRUSTED_PROXIES`，逗号分隔的 IP/CIDR）后才读该头：从右往左、跳过可信跳，第一个不可信跳即客户端。只填你掌控、且会追加真实来源地址的代理；范围过宽（如 `0.0.0.0/0`）等于重新允许伪造。不配置时，同一 NAT/代理后的客户端共用一个计数。Tomcat 自带的转发头处理已固定关闭（`server.forward-headers-strategy: none`）。该头仍原样转发给 `platform-app`，那边不要据此做安全判断。计数按网关进程各算各的。
5. **Tenants are auto-created on first task submission.** The tenant guard only checks that `X-Tenant-Id` is present on `/tasks`; it does not check that the operator may act for that tenant, and `JdbcTaskMessagePort` inserts an unknown tenant as `ACTIVE`. There is no operator–tenant authorization yet.
   首次提交任务会自动建租户。租户拦截只检查 `/tasks` 是否带 `X-Tenant-Id`，不检查操作员是否有权代表该租户；未知租户会被直接写成 `ACTIVE`。尚无操作员—租户授权关系。
6. **Metrics and probes are anonymous.** `/actuator/prometheus`, `/actuator/health/liveness` and `/actuator/health/readiness` need no auth on all three processes. Rely on network isolation.
   指标与探针端点匿名，靠网络隔离。
7. **Local seed operator.** Profile `local` seeds `platform-operator` / `change-me`; `deploy/compose` enables `local`. Never enable `local` outside a laptop. Elsewhere operators are inserted as table rows (`docs/operator-permissions.md`); there is no provisioning, password-change or disable API yet.
   `local` profile 会写入 `platform-operator` / `change-me`，compose 默认启用 `local`。开发机以外不要启用。其它环境以表行开通操作员，暂无开通、改密、禁用接口。
8. **Images are not published or scanned.** Build them yourself from the Dockerfiles (non-root uid 10001).
   镜像未发布、未做漏洞扫描，需自行构建（非 root uid 10001）。

The full assessment these items come from is `docs/pre-release-assessment.md`.
以上条目来自 `docs/pre-release-assessment.md`。
