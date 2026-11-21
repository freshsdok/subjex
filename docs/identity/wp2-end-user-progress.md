# WP2 end-user identity progress / 终端用户身份进度

- Date: 2026-11-17 (Asia/Shanghai)
- ADR: [`docs/adr/0014-end-user-identity.md`](../adr/0014-end-user-identity.md)
- Baseline tip when this note was opened: `8b3bde8` (WP1 closed; Q7 `2f23be8` below)
- 已完成：`1a4661d` / `41b6775`（WP2-0）…`0df2c8e` / `f87adb5`（WP2-5 跨领域隔离）；本包已闭合。
- 本切片提交：`5ed46c1`（`5ed46c1efe73320ac597d051f49ef5df7c109438`；WP2-6 uni-app 终端手机号与微信登录，WP2 收口）

This track is **end-user** (`END_USER`) identity. It is not the operator user-center track ([`id-progress.md`](id-progress.md), ADR 0003). Do not mix operator “unlinked IdP is denied” with WeChat auto-provision for end users. Medical/hospital domain stays out of the kernel. `web/` stays the operator console; clients stay `clients/uni-app/`.

本轨是终端用户身份，不是操作员用户中心轨。不要把操作员「未绑定即拒绝」与终端微信自动开户混用。医疗域不进内核。

## WP2-0 [done] — inventory + ADR

Docs-only. No login/register/token code, no schema migration, no uni-app pages in this slice.

本切片只写文档，不实现登录注册令牌、不写迁移、不改 uni-app 页面。

### Inventory / 盘点（事实与路径）

| Area | What exists | Paths |
| --- | --- | --- |
| Primitives | `Account`, `Subject`, `SubjectKind` (`PERSON` / `ORGANIZATION`), `Identity`, `IdentityState`. A subject is not a login and not a tenant. | `platform-contract/src/main/java/com/subjex/platform/contract/identity/` |
| Tables | `account`, `subject`, `subject_identity`, `tenant` | `platform-app/src/main/resources/db/migration/V1__platform_schema.sql` |
| Operator as Subject | An operator is a Subject whose Identity lives in reserved tenant `platform`. Roles/permissions via `subject_role`. | `V2__operator_permission.sql` (comment + `INSERT` tenant `platform`) |
| Operator credential | Password hash only for operators | `operator_credential` in V2; `JdbcOperatorDirectory` |
| Operator MFA | TOTP + recovery; prod requires MFA (WP1-2) | `V9__operator_mfa.sql`; `JdbcOperatorMfaStore`; `OperatorMfaEndpoint` |
| Operator tokens | Opaque unprefixed URL-safe Base64; SHA-256 at rest; access default PT30M; refresh PT8H rotate; family reuse detection; logout revokes family | `V8__operator_tokens.sql`; `JdbcOperatorTokenStore`; `OperatorAuthEndpoint` (`/api/v1/auth/login`, `/refresh`, `/logout`, `/mfa/verify`) |
| Operator lockout | Per login name; default 5 / PT15M; 429 `reason=login-lockout` | `V17__operator_login_lockout.sql`; `JdbcOperatorLoginLockout`; `platform.auth.lockout-*` |
| Operator HTTP Basic | Off outside `local` | `PlatformSecurityConfiguration`; `application.yml` / `application-local.yml` |
| Operator console session | httpOnly cookie; memory or Redis (`OPERATOR_SESSION_SECRET`); **not** for end users (ADR 0007) | `web/` BFF session routes |
| Operator OIDC RP | Single issuer; **pre-link required**; no auto-provision; SSO skips local TOTP | `OperatorOidcService`; `OperatorOidcEndpoint`; `JdbcOperatorIdpLinkStore`; `V10__operator_oidc.sql` |
| Operator IdP broker | Generic OIDC + WeChat (open platform + mini `jsCode` / code2session). **Never auto-provisions operators.** | `platform-app/.../idp/` (`IdpAdapter`, `WeChatIdpAdapter` id `wechat`, `GenericOidcIdpAdapter`, `IdpLoginOrchestrator`, `IdpProvidersEndpoint` `POST /api/v1/auth/idp/{providerId}/start\|callback`, `MeIdpLinkEndpoint` `/api/v1/me/idp-link`) |
| Bearer filter | `eu.` prefix → end-user store → `EndUserPrincipal` (never the operator store); else opaque **operator** store, then OP JWT → `OperatorPrincipal` | `BearerTokenAuthenticationFilter` |
| Principal | `OperatorPrincipal` (humans + `sa:` service accounts) and `EndUserPrincipal` (WP2-2) | `OperatorPrincipal.java`; `EndUserPrincipal.java` |
| Tenant gate (operators) | `X-Tenant-Id`; grants in `operator_tenant_grant` (wildcard `*` allowed); missing header 403; missing tenant 403 `tenant-not-found` (WP1-3, no auto-create); suspended 403 | `TenantEnforcementFilter`; `JdbcOperatorTenantAccess`; `V6__operator_tenant_grant.sql`; `TenantNotFoundException` |
| Ontology membership | Org `Membership` has **no** tenant semantics. Tenant binding is `subject_identity`. | `docs/ontology/lifecycle-id-state.md`; ADR 0001 |
| Inbox `END_USER` | Same store, `recipient_realm`; `/api/v1/me/inbox` lists END_USER rows but auth is still `OperatorPrincipal` + `inbox.read` + operator tenant grant | ADR 0008; `EndUserInboxEndpoint`; `InboxService.listOwnEndUser`; `InboxRecipientRealm` |
| Questionnaire `AUTH_END_USER` | Collect mode + respondent realm; uni-app fill also has ANON token path (Q4/Q7). Authenticated submit still needs a real end-user principal (today would ride whatever Bearer `/api/v1` accepts). | ADR 0013; `QuestionnaireCollectMode.AUTH_END_USER`; `QuestionnaireEndpoint`; `clients/uni-app/src/pages/questionnaire/` |
| uni-app Bearer path | Thin `#3a` login: H5 password → **operator** `POST /api/v1/auth/login`; MP `jsCode` → **operator** `POST /api/v1/auth/idp/wechat/callback` (link-required, no auto-provision). Tokens stored as `subjex_end_user_bearer` / `subjex_end_user_refresh` but they **are** operator tokens. Logout = `clearTokens()` only (no `POST /auth/logout`). | `clients/uni-app/src/api/auth.ts`, `auth-token.ts`, `pages/login/login.vue`; [`docs/clients/end-user-auth-progress.md`](../clients/end-user-auth-progress.md) |
| Phone / SMS | WP2-3: generic `VerificationCodePort` + phone register/login/bind/unbind. No commercial SMS vendor in the kernel. Next free Flyway: **V50**. | `EndUserPhoneAuthEndpoint`; `end_user_phone_binding`; `end_user_login_lockout` |
| ADRs | 0003 user-center (operators); 0007 client triad (`web/` vs `clients/uni-app/`, no operator Redis session for clients, token shape was TBD); 0008 inbox realms; 0013 questionnaire `AUTH_END_USER` | `docs/adr/0003-user-center-oidc.md`, `0007-client-triad.md`, `0008-in-app-messaging.md`, `0013-questionnaire-platform-capability.md` |

### Key decisions recorded in ADR 0014 / ADR 已锁定

- Separate tables/sessions; reuse Subject primitives, lockout grade, opaque rotating-refresh **pattern**; do not share operator credential, MFA, token store, Redis session, HTTP Basic, operator IdP policy, or `operator_tenant_grant`.
- 表与会话隔离；复用主体原语、锁定级别、不透明轮换刷新模式；不共享操作员凭据栈。
- Phone + WeChat mini-program are first authenticators, with bind/unbind. Phone login needs a generic verification-code port later (no SMS vendor in the kernel).
- 手机号 + 微信小程序为首选认证器，可绑定解绑。
- WeChat first login **auto-provisions** an END_USER. Tenant must be an **explicit, already-existing ACTIVE** business tenant (`X-Tenant-Id` / `tenantId`); never auto-create a tenant; never default to reserved `platform`; no “patient tenant”. Invite token is recommended hardening.
- 微信首次登录自动开户；租户必须显式且已存在为 ACTIVE；不建租户；不用 `platform`；不发明患者租户。
- Tokens: `eu.at.` / `eu.rt.` prefix; `/api/v1/end-user/auth/**`; `EndUserPrincipal`; console management APIs deny end-user tokens (403); tenant-scoped APIs fail-closed.
- 令牌可区分；路径分离；跨领域拒绝；租户失败关闭。

## WP2-1 [done] — account schema

Flyway **`V49__end_user_identity.sql`** (confirmed no prior V49 in-tree; LCS V49 was not reintroduced). Thin store only — no HTTP, no token issue/refresh/logout, no phone/WeChat login, no uni-app pages.

表名（与 ADR 示例一致，本切片落定）：

| Table | Role | Uniqueness |
| --- | --- | --- |
| `end_user_phone_binding` | Phone authenticator (ready for WP2-3) | **Global** unique `phone`; unique `account_id` (one phone per account). Follows ADR D2 (one phone → one Account), not per-tenant. |
| `end_user_idp_link` | WeChat / IdP link (ready for WP2-4) | Unique `(issuer, idp_subject)` — same model as `operator_idp_link` / `WeChatIdpAdapter` (`wechat:mini` / `wechat:open`, subject = unionId if present else openId). Unique `account_id` (one IdP row per account this wave; phone is a separate table). **Not** unique-per-tenant: the link is on the account; tenant membership is `subject_identity`. |
| `end_user_access_token` / `end_user_refresh_token` | Created for WP2-2 (hash, family, revoke columns). This slice does not insert or issue tokens. | Unique `token_hash`. |
| `end_user_login_lockout` | Created for WP2-3 (`lockout_key` PK). This slice does not run the lockout algorithm. | PK `lockout_key`. |

Reuse: `account` / `subject` (`PERSON`) / `subject_identity` in an explicit **ACTIVE** business tenant. `login_name` is `eu:{accountId}` (not a product username). Reserved tenant `platform` is refused as a home (`EndUserAccountStore.RESERVED_TENANT`). Missing tenant → `TenantMissingException` / `TenantNotFoundException`; `SUSPENDED` → `TenantDisabledException`. No writes to `operator_*`. No medical columns.

Store: `EndUserAccountStore` + `JdbcEndUserAccountStore` + `MemoryEndUserAccountStore`. Tests: `EndUserAccountStoreTest` (memory + H2 PostgreSQL/MySQL modes).

## WP2-2 [done] — token issue / refresh / logout

Opaque short-lived access + rotating refresh, following the operator pattern but **not** sharing `JdbcOperatorTokenStore` or writing `operator_*` token tables.

不透明短时访问令牌 + 轮换刷新；复用操作员模式，但不共用操作员令牌仓库，不写操作员令牌表。

- Prefixes: **`eu.at.`** / **`eu.rt.`**. Hash is SHA-256 hex of the full raw token (including prefix).
  前缀可区分；落库只存完整原始令牌的 SHA-256。
- TTL defaults (same order of magnitude as operators, independently configurable): access **PT30M**, refresh **PT8H**. Config: `platform.end-user.auth.access-token-ttl` / `refresh-token-ttl`. Env: `PLATFORM_END_USER_ACCESS_TOKEN_TTL` / `PLATFORM_END_USER_REFRESH_TOKEN_TTL`. Does not overwrite `platform.auth.*`.
  默认 TTL 与操作员同量级，独立配置。
- `JdbcEndUserTokenStore.issue(accountId, tenantId, userAgent, clientIp)` is an **internal** helper for later login slices and tests. No public login HTTP, no phone or WeChat authenticator in this slice.
  签发是内部方法，本切片不提供登录 HTTP，不加手机号/微信认证器。
- Issue fail-closed: reserved tenant `platform`, blank tenant, missing tenant, or non-ACTIVE tenant refused (`IllegalArgumentException` / `TenantMissingException` / `TenantNotFoundException` / `TenantDisabledException`). Account must be ACTIVE and have an ACTIVE identity in that ACTIVE business tenant.
  签发失败关闭：拒绝 `platform`、空/缺失/非 ACTIVE 租户；账号与身份必须 ACTIVE。
- Refresh rotates and invalidates the old refresh. Reuse of a revoked refresh revokes the family and does **not** start a new session (side effects commit even when refusing).
  刷新轮换并作废旧刷新；重用已吊销刷新则吊销整族，不开新会话。
- Logout (`revokeByRefreshToken`) revokes the access token and its refresh family. Unknown or already-revoked refresh is a no-op (not a new session). Subsequent resolve/refresh fail.
  退出吊销访问令牌及刷新族；未知或已吊销不是新会话。
- Principal: `EndUserPrincipal` (not `OperatorPrincipal`). Tokens are account-scoped (V49 has no `tenant_id` on token tables). Refresh/resolve require the account still has at least one ACTIVE identity in an ACTIVE non-`platform` tenant.
  新主体，不是操作员主体。令牌按账号计。
- HTTP (permitAll, token in body): `POST /api/v1/end-user/auth/refresh` (200 pair or 401) and `POST /api/v1/end-user/auth/logout` (204). No `/login` yet.
  HTTP 仅刷新与退出。
- Thin Bearer routing: raw tokens starting with `eu.` go only to the end-user store (optional via `ObjectProvider`, so existing tests keep working). Formal cross-realm isolation tests against operator console APIs wait for WP2-5.
  薄过滤器按前缀分流；跨领域隔离正式测试留到 WP2-5。
- Tests: `EndUserTokenStoreTest` (H2 PostgreSQL/MySQL + standalone MockMvc). Operator token table row counts stay 0.

Store: `JdbcEndUserTokenStore`. Endpoint: `EndUserAuthEndpoint`. Exception: `InvalidEndUserTokenException` (401, not `InvalidOperatorTokenException`).

## WP2-3 [done] — phone register/login + lockout

Phone is **globally unique** on the account (one phone → one Account). First register provisions account + subject + identity + binding in an explicit existing **ACTIVE** business tenant via `EndUserAccountStore`. Never creates a tenant; never defaults to reserved `platform`. Existing phone + another explicit ACTIVE tenant adds an identity row and issues tokens.

手机号在账号上全局唯一。首次注册在显式且已存在的 ACTIVE 业务租户中开户。不建租户，不用 `platform`。

- Verification-code port: `VerificationCodePort` + `MemoryVerificationCodePort` (SHA-256 at rest, default TTL **PT5M**, env `PLATFORM_END_USER_VERIFICATION_CODE_TTL`). `DevVerificationCodeSender` is a no-op for tests/`local` (codes are not logged; peek or `devCode` in the response when reveal is on). Under `prod`/`production` the sender is `FailClosedVerificationCodeSender`: `request-code` returns **503** `reason=verification-code-sender-unconfigured`. Reveal-in-response is false in base YAML and **true only** in `application-local.yml`; prod + reveal fails startup.
  通用验证码端口，内核无短信厂商。生产未配置真实发送器则失败关闭，不打印验证码。
- HTTP (`/api/v1/end-user/auth/phone/**`): `POST /request-code`, `POST /login` (register-or-login, permitAll), `POST /bind`, `POST /unbind` (require `EndUserPrincipal`). Tenant from JSON `tenantId` or `X-Tenant-Id` (must match if both present). Success issues WP2-2 `eu.at.` / `eu.rt.` pairs. Operator tokens and HTTP Basic are not end-user authenticators (bind/unbind → 403 `reason=end-user-authentication-required`).
  绑定/解绑只接受终端主体。
- Lockout: `JdbcEndUserLoginLockout` writes `end_user_login_lockout` only (`lockout_key=phone:{phone}`). Default **5 failures / PT15M** (`platform.end-user.auth.lockout-max-failures` / `lockout-duration`, env `PLATFORM_END_USER_LOCKOUT_MAX_FAILURES` / `_DURATION`). While locked, request-code and login return **429** `reason=login-lockout`. Successful login clears the counter; tests may also call `clear` (explicit unlock) or wait for the window.
  锁定与操作员同级，不共用操作员锁定表。
- Unbind deletes the binding so the number cannot log in as that account and may bind to another account that does not already have a phone (V49 unique `account_id` still means one phone per account). This slice allows unbind even when it is the only authenticator (WeChat arrives in WP2-4; last-authenticator protection can land with that or account deletion).
  解绑释放号码；本切片允许解绑唯一认证器，账号保留但无法再用该号登录。
- Unique phone conflict on bind returns **409** `reason=phone-already-bound`. Invalid/expired codes return **401** `reason=invalid-verification-code`.
- Tests: `EndUserPhoneAuthTest`. Operator token and lockout table row counts stay 0.

## WP2-4 [done] — WeChat mini-program auto-provision login

First WeChat mini-program login **may auto-provision** an END_USER account + PERSON subject + ACTIVE identity + `end_user_idp_link`. This is the opposite of the operator policy (unlinked IdP is denied; `IdpLoginOrchestrator` / `POST /api/v1/auth/idp/wechat/callback` / `operator_idp_link` are not used).

首次小程序登录可自动开户，与操作员「未绑定即拒绝」相反；不走操作员微信回调。

- Tenant: client must supply an existing **ACTIVE** business `tenantId` (JSON or `X-Tenant-Id`). Missing → fail closed; missing/disabled catalog row → fail closed; **never** default to `platform`; never create a tenant; no “patient tenant”.
  租户必须显式且已存在为 ACTIVE；不建租户，不用 `platform`。
- Identity key: unique `(issuer, idp_subject)` on the account, issuer `wechat:mini`, subject = **unionId if present else openId** (`WeChatApiClient.WeChatSession#subject()`). Membership is `subject_identity`, not a per-tenant openId. Subsequent login of the same subject reuses the account; an additional explicit ACTIVE tenant adds another identity row.
  身份键沿用现有适配器模型；租户成员关系是 `subject_identity`。
- Assertion: `EndUserWeChatAssertion`. Production/default redeems jsCode through the existing `WeChatIdpAdapter` (`AdapterEndUserWeChatAssertion`). Tests and `local` use `DevEndUserWeChatAssertion` (`platform.end-user.auth.wechat.dev-stub=true` in `application-local.yml` only; jsCode `dev:{openId}` or `dev:{openId}:{unionId}`). Under `prod`/`production`, missing mini-program config (`WeChatProperties.miniReady()` false) uses `FailClosedEndUserWeChatAssertion`: login returns **503** `reason=wechat-unconfigured` and does not provision. Prod + dev-stub fails startup.
  复用现有微信适配器的 code2session；生产未配置则失败关闭，不静默开户。
- HTTP: `POST /api/v1/end-user/auth/wechat/login` (permitAll, `{ jsCode, tenantId }`), `POST /bind` (`{ jsCode }`), `POST /unbind` (`{ jsCode }` and/or `{ issuer, idpSubject }`). Bind/unbind require `EndUserPrincipal` (operator tokens / HTTP Basic → 403). Success issues WP2-2 `eu.at.` / `eu.rt.` pairs.
- Lockout: same grade as operators, key `wechat:{issuer}:{idp_subject}` in `end_user_login_lockout` (not `operator_login_lockout`).
- Last authenticator: `EndUserAccountStore.requireNotLastAuthenticator` — unbind of the last phone or WeChat link returns **409** `reason=last-authenticator`. Phone unbind from WP2-3 now uses the same rule.
  不允许解绑最后认证器；手机号解绑同步此规则。
- Bind conflict: **409** `reason=idp-already-bound`. Invalid jsCode: **401** `reason=invalid-wechat-code`.
- Tests: `EndUserWeChatAuthTest` (plus regression of `EndUserPhoneAuthTest` / `EndUserTokenStoreTest`). Operator IdP/token/lockout/credential tables stay at 0.

## WP2-5 [done] — formal cross-realm isolation

End-user tokens must not call operator console / management APIs; operator tokens and HTTP Basic must not act as end-user authenticators. Denial is consistently HTTP **403** `reason=operator-authentication-required` (or `end-user-authentication-required` on the reverse bind/unbind paths already introduced in WP2-3/4). Tenant fail-closed from WP2-3/4 is unchanged.

终端令牌不得调用操作员控制台/管理接口；操作员令牌与 HTTP Basic 不得充当终端认证器。拒绝统一为 403，并给出明确 reason。租户失败关闭行为未削弱。

- Production fix (minimal, required for consistent 403): `EndUserRealmGateFilter` runs after `BearerTokenAuthenticationFilter`. If the principal is `EndUserPrincipal` and the path is outside the allowlist (`/api/v1/end-user/**`, `/actuator/health`, `/api/v1/questionnaires/public/**`), the filter writes 403 `reason=operator-authentication-required`. This covers `.authenticated()` and `permitAll` operator routes (login/refresh/logout, WeChat/OIDC start/callback) that a bare authority check would miss. `PlatformSecurityConfiguration` also registers an `accessDeniedHandler` that uses the same reason when an `EndUserPrincipal` fails an authority check (instead of an empty 403 body).
  生产侧最小修复：Bearer 之后加领域门；允许名单以外一律 403。操作员登录/刷新/退出与 IdP 路由即便是 permitAll 也被挡住。权限不足时的拒绝处理器使用同一 reason。
- Operator `POST /api/v1/auth/refresh` and `/logout` reject a body refresh token whose trimmed value starts with `eu.` with the same 403, so an end-user refresh token cannot jump realms even without an Authorization header. Logout of unknown / operator tokens is still 204.
  操作员刷新/退出若请求体是 `eu.` 前缀令牌也返回 403。
- Formal suite `EndUserCrossRealmIsolationTest` (table-driven, MockMvc + token fixtures). 34 tests, all passing. Representative operator-management routes with an `eu.at.` Bearer, all expecting 403 `operator-authentication-required`:
  - directory / self: `GET /api/v1/operators`, `GET /api/v1/me`, `POST /api/v1/operators/me/password`
  - MFA admin: `GET /api/v1/auth/mfa`, `POST /api/v1/auth/mfa/totp/start`, `POST /api/v1/auth/mfa/verify`
  - tenant.manage: `GET`/`POST /api/v1/tenants`
  - console UI-permission / manifest: `GET /api/v1/ui-permissions`, `GET /api/v1/ui-permissions/draft`, `POST /api/v1/ui-permissions/draft/promote`, `POST /api/v1/declarations/demo/1.0.0/promote`
  - OAuth client admin: `GET /api/v1/oauth/clients`
  - inbox compose/broadcast + mailbox listing (still operator-gated in this wave): `GET /api/v1/inbox`, `GET /api/v1/inbox/entries`, `POST /api/v1/inbox/system`, `GET /api/v1/me/inbox/entries`
  - questionnaire admin/write: `GET`/`POST /api/v1/questionnaires`
  - operator login/refresh/logout and WeChat/OIDC: `POST /api/v1/auth/{login,refresh,logout}`, `POST /api/v1/auth/oidc/{start,callback}`, `GET /api/v1/auth/oidc/status`, `POST /api/v1/auth/idp/wechat/{start,callback}`
  - operator IdP link: `GET /api/v1/me/idp-link`
- Reverse direction: operator Bearer and HTTP Basic on `POST /api/v1/end-user/auth/phone/{bind,unbind}` and `/wechat/{bind,unbind}` stay 403 `end-user-authentication-required`. An operator refresh token is not accepted by `POST /api/v1/end-user/auth/refresh` (401, not treated as `eu.rt.`). End-user refresh/logout on `/api/v1/end-user/auth/**` remain 200/204.
- Bearer prefix split is tested both ways: operator access token on `GET /api/v1/me` is 200; end-user access token on the same path is 403 `operator-authentication-required`.
- Tenant fail-closed (phone login): reserved `platform` stays 400; missing `X-Tenant-Id` / unknown tenant / suspended tenant stay 403; a missing tenant is not auto-created.
- Residual gaps (not product features in this slice): `/api/v1/me/inbox` and the AUTH_END_USER questionnaire respondent path still expect `OperatorPrincipal` in production wiring — ADR 0014 wants end-user mailbox/respondent access later; this slice only locks the isolation 403. The formal table is representative, not exhaustive of every `/api/v1/**` management route (config, flags, org, entity, pages, workflow, audit, search, capabilities, …). The allowlist gate still denies those because they are outside `/api/v1/end-user/**`. Public questionnaire collect remains reachable with an end-user Bearer by design.

## WP2-6 [done] — uni-app H5 + mini-program end-user auth

The end-user shell no longer calls operator auth APIs. `clients/uni-app` uses only `/api/v1/end-user/auth/**`:

本壳不再调用操作员认证接口，只走终端认证路径：

- Phone: `POST /api/v1/end-user/auth/phone/{request-code,login,bind,unbind}` (H5 and mini-program).
- WeChat: `POST /api/v1/end-user/auth/wechat/{login,bind,unbind}` with `uni.login` `jsCode` on the mini-program path. H5 hides WeChat login because `jsCode` is unavailable (it does not fall back to operator WeChat routes).
- Session: `POST /refresh` and `/logout`. Only `eu.at.` / `eu.rt.` are persisted under the existing `subjex_end_user_*` keys; leftover operator tokens are cleared. `App` launch calls `ensureSession()` to refresh silently when access is missing or near expiry. Logout revokes on the server, then always clears local storage.
- Tenant: required and fail-closed. Supplied by the login field, launch/page query `?tenantId=`, or `VITE_SUBJEX_TENANT_ID`. Never defaults to `platform`; the client never creates a tenant. Shared helper: `src/api/tenant.ts`.
  租户必填且失败关闭：登录框、启动查询或环境变量；永不默认 platform，不创建租户。
- Bind/unbind UI lives on the login page when signed in (phone on both targets; WeChat bind only on MP). Last-authenticator 409 is shown as returned by the server.
- Public ANON_LINK fill (`pages/questionnaire/fill`) still works without login. Authenticated questionnaire list and `/api/v1/me/inbox` remain operator-gated (WP2-5 residual); the shell shows a clear 403 message and does not force login for anon fill. Home falls back to local nav when `GET /api/v1/ui-permissions` returns 403 for an end-user token.
- Type-check: `vue-tsc --noEmit` has no new errors. Pre-existing `button type="primary"` vs HTML button typings remain on several pages (including `fill.vue`, unchanged in behavior). `npm run build:h5` succeeds. No dedicated uni-app unit tests.

### WP2 package [closed] / WP2 包已闭合

WP2-0…WP2-6 are done. End-user identity now has schema, tokens, phone, WeChat auto-provision, cross-realm isolation tests, and a uni-app client wired to the new APIs. Do not start WP3 (object storage ACL / multipart) in this note.

WP2-0 至 WP2-6 已完成。不要在本包之后立刻开始 WP3。

Residual risks that stay out of this close-out: `/api/v1/me/inbox` and AUTH_END_USER questionnaire paths still expect `OperatorPrincipal` in production wiring; UI-permission manifest remains operator-gated; SMS vendor and invite tokens are still open product points from earlier slices. WP9 Nginx/APISIX edge recipe remains after WP1.

### Proposed later slices / 后续切片

WP3 (object storage ACL / multipart) is the next package and is **not** started here. WP9 Nginx/APISIX edge recipe remains after WP1.

### Open points not settled from existing docs / 现有文档无法锁死、故不臆造的产品范围

- **SMS delivery vendor / product UX for verification codes** — WP2-3 added the generic port and a fail-closed production sender. Choosing and plugging a commercial provider is still out of ADR 0014 and this slice.
- **是否把邀请令牌列为生产必选项** — ADR 将「显式且已存在的 ACTIVE 租户」列为最低默认；邀请令牌是推荐加固，不在本切片发明完整邀请产品。
- **unionId vs openId as the stable WeChat key** when a subject uses more than one mini-program — WP2-4 stores whatever `WeChatApiClient.WeChatSession#subject()` returns (unionId if present, otherwise openId) under issuer `wechat:mini`. Further unionId admin across multiple mini-programs remains later work (`id-progress` ID-5); this slice does not invent a new WeChat product model.

## SHA

- WP2-0：`1a4661d` (`1a4661db53b76b0eacbc57c2ecf181a6f58235ce`)
- WP2-1：`d0c8187` (`d0c818718c6afc1337a364e43962503e49e6680f`)
- WP2-2：`00e8350` (`00e835082f31a2f8647bbd617ab276bba8d8ed4d`)
- WP2-3：`ad002fc` (`ad002fc93c65eb789162ecb070f4f10ae178f3e8`)
- WP2-4：`3d991ba` (`3d991ba1e26720599dc774cc879efe58f9cc22ac`)
- WP2-5：`0df2c8e` (`0df2c8e18ea6821e8ca1673317bb62acfd36f9f3`)
- WP2-6：`5ed46c1` (`5ed46c1efe73320ac597d051f49ef5df7c109438`)
