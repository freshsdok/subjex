# ADR 0014 — First-class end-user identity / 一等终端用户身份

- Status: **Accepted** (direction) — implementation via WP2-1… slices; **this ADR is docs only**
- Date: 2026-11-17 (Asia/Shanghai)
- Relates: ADR 0003（用户中心 / 操作员 OIDC 与 IdP；本 ADR 补上其 §4 预留的终端领域）、ADR 0007（客户端三分；本 ADR 锁定终端令牌形态）、ADR 0008（站内信 `END_USER` 域）、ADR 0013（问卷 `AUTH_END_USER`）、WP1-3（提交时不自动建租户）、[`docs/identity/wp2-end-user-progress.md`](../identity/wp2-end-user-progress.md)
- Amends: ADR 0003 §4（终端领域不再「另行文档」；操作员仍为未绑定即拒绝、不自动开户）；ADR 0007 §D3（终端令牌形态由 TBD 改为本 ADR 的不透明短效访问 + 可轮换刷新，带可区分前缀）
- 状态：**方向已接受** — 由 WP2-1… 切片落地；**本 ADR 只定方向，不改代码**
- 日期：2026-11-17（Asia/Shanghai）

## Context / 背景

The locked Subjex base work asks for first-class **end-user** accounts (Subject / `END_USER`), isolated from the operator table and operator sessions: phone + WeChat mini-program register/login, bind/unbind, short-lived access tokens and rotating refresh tokens, logout revocation, lockout at the same grade as operators, and a hard rule that end-user tokens cannot call operator console management APIs. WeChat first login **may auto-provision** an end-user account (unlike operators). Acceptance is register/login/refresh/logout on H5 and the mini-program, cross-realm access denied, tenant missing fail-closed.

底座工作已锁定：一等终端用户账号（Subject / `END_USER`），与操作员表和操作员会话隔离；手机号与微信小程序注册登录、绑定解绑；短效访问令牌与可轮换刷新令牌，退出吊销；登录失败锁定与操作员同级；终端令牌不能调用操作员控制台管理接口。微信首次登录**允许自动开户**（与操作员「未绑定即拒绝」不同）。验收：H5 与小程序完成注册、登录、刷新与退出；跨领域访问被拒绝；租户缺失失败关闭。

Inventory of what already exists is in [`docs/identity/wp2-end-user-progress.md`](../identity/wp2-end-user-progress.md) (file paths there). In short:

盘点详见进度说明（含路径）。摘要：

- The identity *primitives* already exist: `Account` / `Subject` / `Identity` (`platform-contract` … `/identity`), tables `account`, `subject`, `subject_identity` (`V1__platform_schema.sql`). An **operator** is a Subject whose Identity lives in the reserved tenant `platform` (`V2__operator_permission.sql`).
- 身份三分已存在。操作员是身份落在保留租户 `platform` 的主体。
- Operator sign-in is a separate stack: `operator_credential`, MFA, `JdbcOperatorTokenStore` (opaque unprefixed URL-safe Base64, SHA-256 at rest, rotating refresh, family reuse detection), `JdbcOperatorLoginLockout` (default 5 / PT15M), HTTP Basic (local/script only), console httpOnly Redis/memory session (ADR 0007 — **do not reuse** for end users).
- 操作员登录是独立栈：口令、MFA、不透明令牌、锁定、本机 HTTP Basic、控制台 Redis 会话。终端**不得**复用该会话模型。
- Operator IdP (including `WeChatIdpAdapter` / `wechat` mini `jsCode` → code2session) is **link-required, no auto-provision** (`IdpLoginOrchestrator`).
- 操作员 IdP（含微信小程序）须预先绑定，未绑定即拒绝，不自动开户。
- `clients/uni-app/` already has a thin login page (`#3a`) that calls **operator** APIs (`POST /api/v1/auth/login`, `POST /api/v1/auth/idp/wechat/callback`) and stores the resulting operator Bearer under `subjex_end_user_*` keys. Logout currently clears local storage only. Inbox `/api/v1/me/inbox` and questionnaire `AUTH_END_USER` are realm-labelled but still authenticate as `OperatorPrincipal`.
- uni-app 现有薄登录走的是**操作员**接口与操作员令牌；站内信/问卷虽有 `END_USER` 域，认证仍是 `OperatorPrincipal`。这是 WP2 要切断的过渡态。
- There is **no** end-user credential table, **no** phone authenticator, and **no** `SmsPort` in the kernel. Tenant auto-create on submit is **off** (WP1-3). Ontology `Membership` is org membership, not tenant binding; tenant binding is `subject_identity`.
- 内核尚无终端凭据表、手机号认证或短信端口。提交时自动建租户已关闭。组织 Membership 不含租户语义。

Constraints: strengthen the generic platform only. Do not put hospital catalogs, appointments, visa escort, or insurance into the kernel. `web/` stays the operator console; end-user clients stay `clients/uni-app/`.

约束：只加强通用平台；不把医院目录、预约、签证陪诊或保险写入内核。`web/` 仍是操作员控制台；终端客户端仍是 `clients/uni-app/`。

## Decision / 决策

### D1 — Separate accounts and sessions; reuse primitives, not operator stores

**Reuse**

- Subject / Account / Identity types and the existing `account` / `subject` / `subject_identity` tables. An end user is a `Subject` (`SubjectKind.PERSON`) whose **business** `Identity` rows live in ordinary tenants — **not** in reserved tenant `platform`.
- 复用身份三分与现表。终端用户是 `PERSON` 主体，业务身份落在普通租户，**不**落入保留租户 `platform`。
- The lockout *algorithm and grade* (consecutive failures, `locked_until`, default **5** failures / **PT15M**) — same numbers as `platform.auth.lockout-*`.
- 锁定算法与级别与操作员相同（默认 5 次 / 15 分钟）。
- The opaque token *pattern*: short-lived access, rotating refresh, SHA-256 at rest, family id, reuse of a revoked refresh revokes the family, logout revokes the family.
- 复用不透明令牌模式（短效访问、轮换刷新、摘要存储、族、重用吊销、退出吊销族）。

**Do not share**

- `operator_credential`, operator TOTP/MFA tables, `JdbcOperatorTokenStore` / `operator_access_token` / `operator_refresh_token`, operator Redis / httpOnly console session, operator HTTP Basic, `operator_idp_link` / `IdpLoginOrchestrator` operator policy, `operator_tenant_grant` as the end-user tenant gate, `OperatorPrincipal` as the end-user principal.
- 不共享操作员口令、MFA、令牌表与仓库、控制台会话、HTTP Basic、操作员 IdP 链接与「未绑定即拒绝」策略、`operator_tenant_grant`、以及 `OperatorPrincipal`。

End-user credentials, IdP links, phone bindings, tokens, and lockout rows live in **new** tables (names illustrative, next free Flyway is **V49**): e.g. `end_user_phone_binding`, `end_user_idp_link`, `end_user_access_token`, `end_user_refresh_token`, `end_user_login_lockout`. A later slice may add a password authenticator; it is **not** a first authenticator in this ADR.

终端凭据、IdP 链接、手机号绑定、令牌与锁定使用**新表**（下一块 Flyway 为 V49）。本 ADR 不以口令作为首选认证器。

Tenant authorization for end users is an **ACTIVE `subject_identity`** in the requested business tenant (plus the tenant itself ACTIVE). Missing identity or missing/disabled tenant is fail-closed. Wildcard `*` operator grants must not apply to end users.

终端租户授权看该业务租户上的 ACTIVE `subject_identity`，外加租户本身为 ACTIVE。缺失或停用即失败关闭。操作员通配授权不适用于终端。

### D2 — Phone + WeChat mini-program are the first authenticators

- First authenticators: **phone number** and **WeChat mini-program**. Bind and unbind are first-class (an account may hold one or both; unbind must not leave the account with zero authenticators unless a later slice defines account deletion).
- 首选认证器：手机号与微信小程序。绑定/解绑为一等能力；解绑不得使账号失去全部认证器（账号删除属后续切片）。
- Phone uniqueness is **global on the account** (one phone → one end-user `Account`); the same account may have `subject_identity` rows in more than one business tenant, each created only with explicit tenant context (D3).
- 手机号在账号上全局唯一；同一账号可在多个业务租户拥有身份，但每次进入新租户都必须有显式租户上下文（D3）。
- There is no SMS vendor or `SmsPort` in the kernel today. Phone login in WP2-3 must introduce a **generic verification-code port** (local/dev may record or display codes; production plugs an SMS/adapter later). Do not hard-code a cloud or medical SMS vendor into the kernel.
- 内核目前没有短信端口。手机登录须引入通用验证码端口；不要把某个云厂商或医疗短信写进内核。
- WeChat mini-program continues to use `jsCode` → code2session (reuse protocol knowledge in `WeChatIdpAdapter` / `HttpWeChatApiClient`), but **not** the operator link-required orchestrator.
- 小程序仍走 `jsCode` → code2session，但**不**走操作员「未绑定即拒绝」编排。
- Operator WeChat / generic OIDC policy is unchanged: unlinked IdP subjects are still denied; operators are not auto-provisioned.
- 操作员微信/OIDC 策略不变：未绑定仍拒绝，不自动开户。不要混用两套策略。

### D3 — WeChat first login auto-provisions an END_USER; tenant is never invented

**Locked:** WeChat first login **auto-provisions** an end-user `Account` + `Subject` + `subject_identity` when no `end_user_idp_link` exists. This is intentionally different from operator IdP policy, because self-service registration is required.

**锁定：** 微信首次登录在尚无终端 IdP 链接时**自动开户**（账号 + 主体 + 身份）。这与操作员策略不同，因为终端需要自助注册。

**Also locked:** auto-provision does **not** create a tenant. If the tenant is missing, disabled/suspended, or not provided, the write **fail-closes**. There is no implicit “patient tenant” and no use of reserved tenant `platform` as a default home.

**同时锁定：** 自动开户**不**创建租户。租户缺失、停用或未提供则失败关闭。不发明「患者租户」，也不把保留租户 `platform` 当作默认归属。

**Default tenant context (concrete):**

1. The client **must** present an explicit business `tenantId` (`X-Tenant-Id` and/or JSON `tenantId`). If absent → existing fail-closed `tenant-missing` (403).
2. That tenant must **already exist** and be **ACTIVE**. Missing → 403 `tenant-not-found` (WP1-3). `SUSPENDED` / `DISABLED` → existing disabled-tenant refusal. Id `platform` is refused for end-user auto-provision.
3. On success, WP2 writes the end-user account (if new), the WeChat link, and an ACTIVE `subject_identity` in **that** tenant only. Subsequent WeChat logins of the same OpenID/unionId reuse the account; entering an **additional** tenant again requires an explicit, existing ACTIVE tenant (same rules) and creates another identity row — it still does not create tenants.
4. **Recommended hardening** (same track, may land with or just after the WeChat slice): an operator-issued, short-lived **invite** that embeds `tenantId`. If both invite and header are present, they must name the same tenant or the request is denied. A later optional check may bind a uni-app / mini-program client id to a tenant allowlist (similar in spirit to `oauth_client.tenant_id`). Invite is not required to state the default; explicit existing tenant is the minimum.
5. Phone registration follows the same tenant rules.

默认租户上下文（具体约定）：客户端必须给出已存在且 ACTIVE 的业务 `tenantId`；缺头走 `tenant-missing`；无行或停用失败关闭；禁止 `platform`。成功后只在该租户写身份。推荐后续用操作员签发的短时邀请令牌（内嵌 tenantId）加固。手机号注册同一套规则。

### D4 — Token shape and isolation from operator tokens

- End-user APIs issue **opaque** short-lived access (default align with operators: **PT30M**) and rotating refresh (default **PT8H**). Logout (`POST` refresh family revoke) is server-side, not only local storage clear.
- 终端签发不透明短效访问（默认 30 分钟）与可轮换刷新（默认 8 小时）。退出必须在服务端吊销族，不能只清本地存储。
- Raw tokens **must be distinguishable** from operator tokens. Operator access/refresh today are unprefixed URL-safe Base64 (`JdbcOperatorTokenStore.randomToken`). End-user tokens **SHALL** use a stable prefix, e.g. `eu.at.` / `eu.rt.`, then random bytes. `BearerTokenAuthenticationFilter` (or a sibling filter) routes `eu.` to the end-user store **only**; everything else stays on the operator opaque store then OP JWT. An end-user token must never resolve to `OperatorPrincipal`.
- 原始令牌必须可与操作员令牌区分。终端令牌使用稳定前缀（如 `eu.at.` / `eu.rt.`）。过滤器按前缀分流；终端令牌不得解析为 `OperatorPrincipal`。
- Principal type is a new **`EndUserPrincipal`** (subject id, account id, identity id for the current tenant). It is not `OperatorPrincipal` and holds no operator named permissions.
- 主体类型为新的 `EndUserPrincipal`，无操作员具名权限。
- HTTP paths are separate, e.g. `/api/v1/end-user/auth/**` (login / refresh / logout / phone / WeChat). Operator `/api/v1/auth/**` stays operator-only. The `#3a` uni-app shortcuts onto operator login are transitional and must be replaced in the uni-app slice.
- HTTP 路径与操作员分离（`/api/v1/end-user/auth/**`）。uni-app 现有对接操作员登录的捷径须在客户端切片替换。
- **Cross-realm denial:** end-user tokens calling operator console management APIs (`/api/v1/operators/**`, tenant writes / `tenant.manage`, operator MFA, OAuth client admin, declaration promote, and other operator-permission routes) receive **403**. Operator tokens (and OP JWTs / service accounts) must not be accepted as end-user authenticators on `/api/v1/end-user/auth/**` bind/unbind or as the respondent principal for `AUTH_END_USER` once that path is wired to `EndUserPrincipal`. Inbox `/api/v1/me/inbox` must list `END_USER` rows for an end-user principal (and must stop treating an operator Bearer as an end-user mailbox owner).
- **跨领域拒绝：** 终端令牌调用操作员控制台管理接口一律 403。操作员令牌也不得充当终端认证器或 `AUTH_END_USER` 答卷主体。`/api/v1/me/inbox` 须按终端主体列 `END_USER` 域，不再把操作员 Bearer 当作终端邮箱主人。
- Console `web/` BFF continues to store only operator tokens; it must reject `eu.` prefixes.
- 控制台 BFF 只存操作员令牌，须拒绝 `eu.` 前缀。

### D5 — Tenant fail-closed on every end-user API that is tenant-scoped

Existing filters and exceptions stay the source of truth: missing header → `TenantMissingException`; unknown tenant → `TenantNotFoundException`; `SUSPENDED`/`DISABLED` → `TenantDisabledException`. End-user routes that need a tenant must not guess, default to `platform`, or auto-create. This matches WP1-3 and inbox/questionnaire tenant rules.

租户作用域的终端接口沿用现有失败关闭信号：缺头、未知、停用。不得猜测、落到 `platform` 或自动建租户。

## Out of scope / 非目标（本 ADR）

- Implementing any WP2-1… slice in the same commit as this ADR.
- Hospital / appointment / visa / insurance domain, or a dedicated “patient” tenant type.
- Replacing `web/` or moving end-user UI into the operator console.
- Reusing operator Redis sessions (ADR 0007 already forbids it).
- Making `entry-gateway` an IdP.
- Full OIDC OP tokens as the *only* end-user credential (opaque platform tokens are the MVP; OP JWT for end users would need a later ADR).
- Choosing a commercial SMS vendor.

本 ADR 提交不含实现。不做医疗域、不把终端 UI 放进控制台、不复用操作员 Redis 会话、不把入口网关做成 IdP、不以 OP JWT 作为本波唯一凭证、不选定商业短信厂商。

## Consequences / 后果

- Operator and end-user auth stacks can evolve independently; mixing policies is a defect.
- 操作员与终端认证栈独立演化；混用策略视为缺陷。
- `#3a` / IM-6 / questionnaire fills that currently ride operator Bearers will need a migration in WP2 client and isolation slices.
- 当前借用操作员 Bearer 的薄登录、站内信与问卷填写须在后续切片迁移。
- Operators still provision tenants; end users cannot create tenants by logging in.
- 租户仍由操作员显式创建；终端登录不能建租户。

## Slice plan (forward) / 后续切片（本 ADR 不实现）

| ID | Scope | Depends |
| --- | --- | --- |
| **WP2-1** | Flyway (V49+) end-user tables; wire `account` / `subject` / `subject_identity` for END_USER; refuse reserved `platform` as home tenant | WP2-0 |
| **WP2-2** | End-user token store + `EndUserPrincipal`; prefixed opaque access/refresh; `/api/v1/end-user/auth/refresh` + `/logout` (server revoke); Bearer filter routing | WP2-1 |
| **WP2-3** | Phone register/login + bind/unbind; generic verification-code port (no vendor in kernel); lockout table at operator grade | WP2-2 |
| **WP2-4** | WeChat mini-program login with **auto-provision**; bind/unbind; D3 tenant context (no tenant insert) | WP2-2 |
| **WP2-5** | API isolation tests: console management 403 for `eu.` tokens; operator tokens denied as end-user; tenant missing/disabled fail-closed; inbox/questionnaire realm checks | WP2-2…4 |
| **WP2-6** | `clients/uni-app/` H5 + 小程序：注册/登录/刷新/退出对接新 API；停止调用操作员 `/api/v1/auth/login` 与操作员微信回调作为产品登录 | WP2-3, WP2-4, WP2-5 |

Lockout tests may land with WP2-3 and WP2-4; WP2-5 is the cross-realm gate.

锁定测试可随 WP2-3/2-4 落地；WP2-5 是跨领域门禁。

## Links / 链接

- Progress: [`docs/identity/wp2-end-user-progress.md`](../identity/wp2-end-user-progress.md)
- Operator identity track (unchanged policy): [`docs/identity/id-progress.md`](../identity/id-progress.md)
- Transitional uni-app auth: [`docs/clients/end-user-auth-progress.md`](../clients/end-user-auth-progress.md)
