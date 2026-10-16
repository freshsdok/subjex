# Default auth matrix / 默认认证矩阵

One page for contributors: which credential each surface uses by default.
贡献者一页纸：各入口默认用哪种凭证。

Canonical detail: [`SECURITY.md`](../SECURITY.md) · [`docs/auth-roadmap.md`](auth-roadmap.md) · [`docs/adr/0003-user-center-oidc.md`](adr/0003-user-center-oidc.md)

---

## Matrix / 矩阵

| Principal / 主体 | Mechanism / 机制 | Default on? / 默认开？ | Typical use / 典型用途 |
| --- | --- | --- | --- |
| Operator (human) | **Opaque Bearer** access + rotating refresh (`POST /api/v1/auth/login`) | **Yes** — preferred | Scripts, APIs, console proxy |
| Operator (human) | **HTTP Basic** (`Authorization: Basic …`) | **Off** outside `local`; **on** under `SPRING_PROFILES_ACTIVE=local` or `PLATFORM_AUTH_HTTP_BASIC_ENABLED=true` | Local/script break-glass only; **no MFA prompt** |
| Operator (human) | **TOTP MFA** after password | Optional (`platform.mfa.required` default false) | Login returns `mfaToken` → `/auth/mfa/verify` |
| Operator (human) | **OIDC RP** (platform as client to external IdP) | Optional (`platform.oidc.*`) | SSO; IdP `sub` must be pre-linked; skips local TOTP |
| Operator / app | **OIDC Provider JWT** (platform as OP: Auth Code+PKCE) | When OP clients configured | First-party / third-party apps; dual-accept with opaque |
| Service account | **Client credentials** → OP JWT (`grant_type=client_credentials`) | When SA client registered | Machine-to-machine; tenant-bound scopes |
| Console (`web/`) | Session cookie → **Bearer only** (encrypted access/refresh in memory/Redis) | Always for console | Never stores Basic/password |
| `sample-consumer` | Own **HTTP Basic** gate for its probe/admin HTTP | Process-local | Not the operator console path |
| `entry-gateway` | Forwards `Authorization` unchanged; does **not** authenticate | N/A | Coarse rate limit only |

**Bearer dual-accept:** APIs try opaque operator tokens first, then OP JWT (JWKS / `preferred_username`) when applicable.

**Bearer 双认：** 接口先查不透明操作员令牌，再视情况验 OP JWT。

---

## What to show in examples / 示例应展示什么

- Prefer `Authorization: Bearer $TOKEN` from `/api/v1/auth/login` in docs and samples.
- 文档与样例优先 Bearer。
- Do not present Basic as the product default; label it local/script opt-in.
- 不要把 Basic 写成产品默认；标明本地/脚本可选。
- Console contributors: talk session cookie + server-side Bearer proxy, not browser-held tokens.
- 控制台贡献：讲会话 cookie + 服务端 Bearer 代理，不是浏览器持有令牌。

---

## Quick local path / 本机捷径

```shell
# Golden path console: see docs/quickstart.md — login UI uses password → Bearer under the hood.
# 黄金路径控制台：见 quickstart；登录 UI 底层仍是口令 → Bearer。

TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"loginName":"platform-operator","password":"change-me"}' | jq -r .accessToken)
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/me
```
