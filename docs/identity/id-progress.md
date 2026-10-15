# Identity / user-center progress — 身份与用户中心进度

Track for ADR 0003. Order: **identity callable user-center first**, then audit/tracing observability.

先做可调用用户中心，再做审计/追踪可观测性。

## ID-0 [done] 2026-10-15 CST — inventory + ADR

- Inventory: Spring Security stateless; opaque Bearer + optional Basic; password/MFA/lockout; OIDC **RP only** (single issuer, link-required); tenant grants; no service accounts / consent / WeChat.
- ADR: [`docs/adr/0003-user-center-oidc.md`](../adr/0003-user-center-oidc.md) — OP + IdP broker; Auth Code+PKCE; client credentials; consent/scopes; WeChat + generic OIDC adapters.
- Docs-only commit. **No push.**

## Proposed slices / 计划切片

| ID | Scope (concrete) | Depends |
| --- | --- | --- |
| **ID-1** ✅ | **OP skeleton** (done): discovery+JWKS; `subjex-console`; Auth Code+PKCE JWT access+id_token; dual-accept opaque+JWT; password via authorize | ID-0 |
| **ID-2** ✅ | **Client credentials / SA** (done): tenant-bound clients, rotate secret, CC JWT, scopes↔permissions bridge | ID-1 |
| **ID-3** ✅ | **Consent + third-party AC** (done): scope catalog, consent challenge/grants, minimal `/oauth/consent` page | ID-1 |
| **ID-4** ✅ | **IdP SPI + WeChat** (done): GenericOidc + WeChat adapters, providers API, self-link MVP | ID-1 |
| **ID-5** | *(folded into ID-4 WeChat sketch)* further WeChat UX / unionid admin | ID-4 |
| **ID-6** | **Introspection + userinfo** for 3rd-party / gateway validation; document integration | ID-1..3 |
| **OBS-1** ✅ | Audit query API + identity audit coverage + console filters | ID-4 |
| **OBS-2** ✅ thin | Correlation id + traceparent extract + MDC; OTLP docs | OBS-1 |

## ID-1 [done] 2026-10-15 CST — thin OIDC OP skeleton

- Discovery `GET /.well-known/openid-configuration` + JWKS `GET /oauth2/jwks` (RS256 PEM in `oauth_signing_key`).
- Flyway **V33**: `oauth_client`, `oauth_auth_code`, `oauth_signing_key`; seed public PKCE client `subjex-console`.
- Auth Code + PKCE (S256): `GET /oauth2/authorize` (Bearer login required) → `POST /oauth2/token` → JWT access + minimal id_token.
- Dual-accept Bearer: opaque `JdbcOperatorTokenStore` first, then OP JWT via JWKS/`preferred_username`.
- Password remains OP auth method (operator login → opaque Bearer on authorize). No WeChat/consent UI.
- Config: `platform.op.issuer` (+ TTLs, console client id). Tests: `OpOidcSkeletonTest`.
- Local commit only (**no push**).

## ID-2 [done] 2026-10-15 CST — client credentials / service accounts

- V34: `oauth_client.tenant_id`; confidential SA clients (`client_credentials`, secret SHA-256).
- Admin: `POST/GET /api/v1/oauth/clients`, `POST .../rotate-secret` (`operator.manage`).
- `POST /oauth2/token` grant_type=client_credentials → JWT (`client_id`, `tenant_id`, `scope`); no human subject.
- Dual-accept: SA JWT → `OperatorPrincipal.serviceAccount` with scopes↔`OperatorPermission` bridge; bound tenant for tenant filter.
- Tests: `OpClientCredentialsTest`. Local only (no push). No WeChat/consent.

## ID-3 [done] 2026-10-15 CST — consent + third-party Auth Code

- V35: `oauth_scope` catalog (openid/profile + API scopes ↔ OperatorPermission); `oauth_client.first_party`; `oauth_consent` + `oauth_consent_challenge`.
- Third-party AC clients via admin `kind=authorization_code` (redirect allowlist, PKCE); console remains first-party (no consent).
- Authorize: third-party without covering consent → **409** JSON `{consent_required, challenge_id, scopes…}`; approve/deny at `/api/v1/oauth/consent/decision`.
- List/revoke: `GET/DELETE /api/v1/oauth/consent/grants`; tokens only carry consented scopes.
- Minimal console page: `/oauth/consent?challenge_id=…`. Tests: `OpConsentTest`. Local only (no push). No WeChat.

## ID-4 [done] 2026-10-15 CST — IdP adapter SPI + WeChat

- SPI: `IdpAdapter` (`startAuth` / `handleCallback` → `IdpExternalIdentity`); registry + `IdpLoginOrchestrator` (link-required, no auto-provision).
- `GenericOidcIdpAdapter` wraps `platform.oidc.*`; legacy `/api/v1/auth/oidc/*` kept.
- `WeChatIdpAdapter`: open-platform QR + mini-program `jsCode`→code2session; issuers `wechat:open` / `wechat:mini`; config `platform.idp.wechat.*`.
- Unified: `GET /api/v1/auth/idp/providers`, `POST .../{id}/start|callback`; self-link `GET/PUT/DELETE /api/v1/me/idp-link`.
- Console: providers probe + WeChat button. Tests: `IdpWeChatAdapterTest` (+ OIDC regression). Local only (no push).

## OBS wave / 可观测性

See [`docs/observability/obs-progress.md`](../observability/obs-progress.md) and ADR 0004.

## Next slice recommendation / 下一片建议

**ID-6** introspection + userinfo, or Micrometer/dashboard polish if ops need metrics next.

