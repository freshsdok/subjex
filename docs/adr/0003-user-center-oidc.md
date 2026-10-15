# ADR 0003 — User-center as callable identity (OIDC issuer + IdP broker)

- Status: **Accepted** (direction) — implementation via ID-1… slices
- Date: 2026-10-15 (Asia/Shanghai)
- Scope: First-party console + third-party API clients + service accounts; pluggable external IdPs (WeChat, generic OIDC)
- 状态：**方向已接受** — 由 ID-1… 切片落地
- 范围：控制台、第三方 API、服务账号；可插拔外部 IdP（微信、通用 OIDC）

## Context / 背景

### Inventory (ID-0) — 现状盘点

| Area | Today | Gap vs user-center |
| --- | --- | --- |
| **Spring Security** | Stateless `SecurityFilterChain`; CSRF off; `BearerTokenAuthenticationFilter` before optional HTTP Basic | No OAuth2 Authorization Server; no resource-server JWT validation for external clients |
| **Operator login** | `POST /api/v1/auth/login\|refresh\|logout\|mfa/verify` — password → opaque access/refresh (`JdbcOperatorTokenStore`); TOTP MFA; login lockout | Opaque tokens only; not OIDC-issued JWT; no Authorization Code for apps |
| **Console session** | Next.js httpOnly cookie → encrypted Redis/memory store of platform access/refresh; proxy adds Bearer | Still “operator app” session, not OIDC RP/client against a user-center |
| **OIDC today** | **Relying Party only** (`platform.oidc.*`, default off): Auth Code + PKCE against **one** external issuer; IdP subject must be **pre-linked** (`JdbcOperatorIdpLinkStore`); no auto-provision; SSO skips local TOTP | Not an issuer; single IdP config; no WeChat; no multi-IdP adapters |
| **Subjects / tenants** | `OperatorPrincipal` (`identityId`, `subjectId`, named permissions); per-operator tenant grants; `X-Tenant-Id` + `TenantEnforcementFilter` | No first-class **application client** or **service account** principal; no consent grants |
| **AuthZ** | Named permissions + Cedar/SQL `PolicyEngine`; declaration-scoped checks in endpoints | No OAuth **scopes** mapped to API; no third-party consent UX |
| **Service accounts** | Absent (operators are humans with passwords / IdP link) | Need non-human clients + client credentials |
| **Audit / tracing** | `AuditPort` / operator action audit; OTLP tracing hook (optional endpoint) | Follow-on wave after identity MVP — not redesigned here |

平台今日是「操作员目录 + 不透明 Bearer +（可选）外部 OIDC 依赖方」。控制台用 cookie 会话代理 Bearer。缺：用户中心作为签发方、服务账号、第三方同意/scope、微信等适配器。审计/追踪下一波。

## Decision / 决策

### 1. User-center is a first-class module / 用户中心为一等模块

Introduce a **user-center** capability (package/module naming TBD in ID-1: e.g. `identity` / `usercenter`) that:

1. Acts as an **OIDC Provider (OP)** for first-party apps (console, internal services) — issues tokens, exposes discovery / JWKS / userinfo / (later) introspection.
2. Acts as an **IdP broker** for external identity providers — WeChat Open Platform / mini-program (sketch), generic OIDC/OAuth2, and the existing single-issuer RP path folded into adapters.
3. Keeps **local password (+ TOTP)** as a first-class authentication method for operators (no forced SSO-only).

推荐形态：**subjex = 一等 OIDC 签发方（面向本平台应用）+ 外部 IdP 经纪人（微信/通用 OIDC）**；本地口令保留。

Rationale vs “broker-only”: third-party API callers and service accounts need a **stable issuer and consent store inside subjex**; re-exporting only upstream IdP tokens does not give tenant-bound scopes or permission confirmation.

仅做经纪人无法为第三方应用与服务账号提供租户内 scope/同意与稳定签发方。

### 2. Flows / 协议流

| Client | Flow | Notes |
| --- | --- | --- |
| Console (SPA/BFF) | **Authorization Code + PKCE** against user-center OP | Replace or wrap today’s password form as an OP “login UI” method; BFF may still hold refresh |
| Service accounts | **Client Credentials** | Bound to **tenant** + allowed **scopes**; no human password; secret/rotation |
| Third-party apps | Auth Code (+ PKCE) + **consent** screen | User/operator confirms scopes; tokens limited to granted scopes |
| Resource APIs | Bearer access token (JWT preferred for OP-issued) + existing AuthZ | Map scopes → platform permissions / resources; keep Cedar/SQL engine |
| Machine / backend validation | **Token introspection** and/or JWKS + **userinfo** | For opaque legacy tokens during migration; JWT verify for new OP tokens |

### 3. Consent & scopes / 同意与权限范围

- Define a small **scope catalog** (e.g. `openid`, `profile`, `tenant.{id}.read`, entity/form/page style scopes aligned with existing permission names where possible).
- Third-party clients must be **registered** (client_id, redirect_uris, allowed scopes).
- First API access with new scopes requires an explicit **consent** grant persisted per (subject, client, scope set); revoke supported.
- Service accounts skip interactive consent; admin grants scopes at registration.

第三方调用须注册客户端并经同意确认；服务账号由管理员预授 scope。

### 4. Pluggable IdP adapters / 可插拔 IdP

- SPI: `ExternalIdpAdapter` (start login, handle callback / code, normalize to `ExternalIdentity(issuer, subject, email?, claims)`).
- Adapters: **Generic OIDC** (covers today’s `platform.oidc.*`); **WeChat** (open-platform website + mini-program sketch — code2session / oauth2); future SMS out of this wave.
- Link policy: default **fail-closed** for operator console (must link to existing operator) unless a future “customer user” realm allows provision; document separately if end-user (non-operator) accounts are introduced.

### 5. Service accounts / 服务账号

- Stored as clients (or a dedicated `service_account` row) with `tenant_id`, hashed secret, scope allow-list, active flag.
- Authenticate only via client credentials; never appear as password login users.
- Audit as non-human actors (`identityId` / `client_id`).

### 6. Migration stance / 迁移姿态

- Keep issuing/accepting **current opaque operator tokens** during transition (console BFF unchanged initially).
- ID slices introduce OP endpoints alongside; console moves to Auth Code + PKCE when stable.
- Do not force-push identity schema without Flyway dual-mode care (existing pattern).

## Out of scope this wave / 本波不做

- Full IdP product UI polish (branded account portal, self-serve app gallery)
- SMS / phone OTP
- Social IdPs beyond WeChat sketch + generic OIDC
- Replacing Cedar/SQL AuthZ wholesale
- Deep audit/tracing redesign (scheduled **after** identity MVP)

## Consequences / 后果

- New tables: clients, consents, service accounts, auth codes, (optional) OP refresh/access JWT metadata; IdP adapter configs multi-row.
- Security surface: redirect URI allow-list, PKCE mandatory for public clients, secret storage, key rotation for JWT signing.
- Docs/PROGRESS track **ID-1…**; observability wave starts after callable user-center MVP.

## Slice plan (forward) / 后续切片

See [`docs/identity/id-progress.md`](../identity/id-progress.md) and `web/PROGRESS.md`.

