# Auth roadmap — console operators, tenants, tokens, SSO, MFA

Status: **design only** (2026-10-05). No token/SSO/MFA/UI implementation in the commit that adds this file.
状态：**仅设计**（2026-10-05）。本文件落盘的同一次提交不实现令牌 / SSO / MFA / 控制台改造。

Audience: operators of the reserved tenant `platform` (console + `/api/v1`), not end-user/customer identity.
范围：保留租户 `platform` 的操作员（控制台与 `/api/v1`），不是业务终端用户身份。

Related: `docs/operator-permissions.md`, `SECURITY.md`, `ARCHITECTURE.md`, `web/docs/design-notes.md`.

---

## 1. Current state inventory / 现状盘点

### 1.1 Platform-app authentication

| Area | What exists | Gaps |
| --- | --- | --- |
| Wire auth | HTTP Basic on every request (`PlatformSecurityConfiguration`, `SessionCreationPolicy.STATELESS`) | No Bearer/API tokens; no browser session on Java side |
| Identity store | `account` → `subject_identity` (tenant `platform`) → `subject`; `operator_credential` bcrypt (`{bcrypt}`) via `JdbcOperatorDirectory` | Password only; no IdP link, no MFA factors |
| Permissions | Named authorities: `admin.read`, `page.read`, `config.read/write`, `registry.read/write`, `task.write`, `operator.manage` via `subject_role` → `role_permission` | No online role editor (by design: declaration/SQL) |
| Tenant grants | `operator_tenant_grant` (`*` = all); `TenantEnforcementFilter` after `TenantGuard` — **fail-closed** | Grants API exists; console cannot edit yet |
| Operator admin API | `GET/POST /api/v1/operators`, `POST .../me/password`, `POST .../{login}/password\|disable\|enable`, `GET/PUT .../{login}/tenants` | Complete for slice-1 UI |
| Bootstrap | `local` seeder + one-shot `--platform.operator.bootstrap=true` (off by default); grants `*` | Still the only non-UI provision path outside APIs |
| Hardening called out in `SECURITY.md` | TLS termination expected in front | **No** lockout, **no** MFA, **no** OIDC/SSO, **no** platform-issued tokens |
| OpenAPI / probes | `/api/v1/openapi.json` authenticated; liveness/readiness/prometheus anonymous | — |

There are **no** OIDC, OAuth2 resource-server, JWT, refresh-token, TOTP, or MFA stubs in `platform-app` source. Sample-consumer uses its own Basic gate; entry-gateway forwards `Authorization` unchanged and does not authenticate.

### 1.2 Web console session

| Area | What exists | Gaps |
| --- | --- | --- |
| Browser | httpOnly `SameSite=Strict` cookie `subjex_session` (random id, 8h) | OK |
| Server store | Memory Map or Redis (`SESSION_REDIS_URL` / `REDIS_URL`); value is `{ loginName, credentialHeaderEnc, expiresAtMillis }` | **Still retains password capability**: AES-256-GCM ciphertext of the upstream **Basic** header (`OPERATOR_SESSION_SECRET`) |
| Proxy | `/api/session` login verifies via `/api/v1/me`; `/api/platform/*` decrypts Basic and forwards | Password-equivalent lives for session lifetime |
| Operators UI (`/operators`) | Self password change; with `operator.manage`: list, disable/enable, **read-only** tenant grant labels | **Missing:** create operator, admin password reset, edit tenant grants |
| Tenants UI | None | Only platform HTML/JSON `GET /admin/tenants` (admin.read); no `/api/v1/tenants` twin, no create/disable |

### 1.3 Tenant model

- Table `tenant (tenant_id, tenant_name, tenant_state)` from Flyway `V1`.
- Business tenants still **auto-created** on first granted task submission (`JdbcTaskMessagePort`).
- Reserved tenant `platform` seeded in `V2`.
- Operator–tenant grants already gate tenant-scoped paths; missing grant → 403.

### 1.4 Explicit non-goals of this roadmap

- Replacing named permissions with a free-form ACL UI.
- Customer/end-user IAM inside business tenants.
- Mutual TLS for outbox (already listed separately in `SECURITY.md`).
- Making entry-gateway an IdP or session store.

---

## 2. Target security properties / 目标安全属性

1. **No long-lived password in any session store** after login (memory, Redis, or browser). Login may use password or SSO once; afterward only tokens/session ids.
2. **Access token** short-lived; **refresh token** rotatable, revocable, stored hashed at rest.
3. **Fail-closed** tenant grants remain; tokens carry subject identity, not a bypass of grants.
4. **MFA**: TOTP (RFC 6238) for password login; recovery codes; audit enroll/disable.
5. **SSO**: OIDC Authorization Code + PKCE against an external IdP (Keycloak / Entra / etc.); platform remains the authorization source of truth (roles + grants in DB).
6. **CSRF**: console cookie session stays SameSite=Strict; state-changing console routes keep same origin; platform APIs stay Bearer (no cookie CSRF surface on Java).
7. **Deprecate Basic** for interactive console; keep Basic optional for break-glass / scripts until a documented cutoff, then disable by default outside `local`.

---

## 3. Slice plan (implementation order) / 切片计划

Each slice: small PR-sized change, local git commit, update `web/PROGRESS.md` (and this file’s checklist). Do **not** push from the Debian box.

### Slice A — Console operator admin UI (APIs already exist)

**Scope:** UI only against `/api/v1/operators/**`.

| Work | Detail |
| --- | --- |
| Create | Form: login, password (≥8), role (`platform-operator` / `platform-reader`); review → confirm; `POST /operators` |
| Admin password | Per-row action → `POST /operators/{login}/password` (new password only); review → confirm |
| Tenant grants editor | Multi-select / tag input + `*` wildcard; load `GET .../tenants`, save `PUT .../tenants`; review → confirm |
| i18n | zh/en phrases; keep disable/enable + self password |

**Success criteria:** With `operator.manage`, an operator can create another account, reset its password, set grants including `*`, and see audits (`operator.create`, `operator.password.change`, `operator.tenants.replace`). Without permission, create/edit controls absent; nav still greyed elsewhere as today.

**Deferred:** Role catalog UI; bulk import.

---

### Slice B — Tenant management API + console UI

**Scope:** First-class tenant admin (stop relying only on auto-create + `/admin/tenants`).

| Work | Detail |
| --- | --- |
| Permission | Reuse `admin.read` for list; add `tenant.manage` (or reuse `operator.manage` — **prefer new `tenant.manage`** held by `platform-operator`) for writes |
| API | `GET /api/v1/tenants` (paged later; v1: ordered list + search query); `POST /api/v1/tenants` `{ tenantId, tenantName }`; `POST /api/v1/tenants/{id}/disable\|enable` (sets `tenant_state`); optional `PATCH` name |
| Rules | Deny mutate reserved `platform`; disable does not delete rows or grants; task submit to DISABLED tenant → 403; auto-create only if tenant missing **and** operator grant allows (unchanged fail-closed) |
| Console | `/tenants` page under `(console)`; nav gated on `admin.read` / `tenant.manage`; list + create + disable/enable with review → confirm |
| OpenAPI | Regenerate `web/openapi.json` + `gen:api` |

**Success criteria:** Can list tenants in console; create a tenant without submitting a task; disable blocks new task writes; enable restores; audits recorded.

**Deferred:** Tenant quotas, per-tenant IdP, hard delete, paging/search at Keycloak scale (design-notes: must paginate before hundreds of rows).

---

### Slice C — Platform-issued tokens (stop retaining Basic)

**Scope:** Industry login + refresh; console and API clients stop storing password material.

| Work | Detail |
| --- | --- |
| Storage (Flyway) | `operator_refresh_token` (id, subject/account, token_hash, family_id, expires_at, revoked_at, user_agent/ip optional); optional `operator_access_token` if opaque introspection table preferred |
| Issue | `POST /api/v1/auth/login` `{ loginName, password }` → `{ accessToken, refreshToken, expiresIn, tokenType: "Bearer" }` after credential check; same permissions resolved as today |
| Refresh | `POST /api/v1/auth/refresh` with refresh token → new pair; **rotate** refresh (reuse of old refresh → revoke family) |
| Logout | `POST /api/v1/auth/logout` revokes refresh family; console deletes session |
| Resource server | Accept `Authorization: Bearer` **or** Basic (compat); Bearer resolves to `OperatorPrincipal` via token store / JWT validation |
| Access token shape | **Default: opaque** random token, server-side lookup (fits single `platform-app` AuthZ + easy revoke). JWT (signed, short TTL, `sub` + `sid`) deferred unless gateway must authorize offline |
| Console | On login: call token endpoint; session store holds `{ loginName, accessTokenEnc, refreshTokenEnc, accessExpiresAt }` (still AES-GCM at rest) — **no Basic**; proxy sends Bearer; silent refresh on 401 once |
| Clients | Update sample-consumer / docs to prefer Bearer; Basic remains for bootstrap scripts until Slice C exit criteria |
| Tests | Login wrong password 401; refresh rotation; revoked refresh 401; disabled account rejects refresh; proxy never writes `credentialHeader` plaintext or Basic ciphertext |

**Success criteria:** Fresh console login leaves **zero** Basic ciphertext in Redis/memory; `/api/v1/me` works with Bearer; password change / disable invalidates refresh families for that account.

**Deferred:** Device/session list UI; JWT; DPoP; public OAuth client for third parties.

---

### Slice D — MFA (TOTP, RFC 6238)

**Depends on:** Slice C (MFA challenge sits on login before tokens are issued).

| Work | Detail |
| --- | --- |
| Storage | `operator_mfa_totp` (subject_id, secret_encrypted, confirmed_at); `operator_mfa_recovery` (code_hash, used_at) |
| Enroll | `POST /api/v1/auth/mfa/totp/start` → secret + otpauth URI; `POST .../confirm` with code; show one-time recovery codes |
| Login | After password OK: if MFA enrolled → `mfaToken` (short-lived) instead of tokens; `POST /api/v1/auth/mfa/verify` `{ mfaToken, code }` → access/refresh |
| Policy | Config `platform.mfa.required=false` initially; optional force for role `platform-operator` via config |
| Console | Enroll/disable under `/operators` (self); login second step |
| Audit | `operator.mfa.enroll`, `operator.mfa.disable`, failed verify |

**Success criteria:** Enrolled operator cannot obtain tokens with password alone; recovery code works once; disable requires current password (+ optional TOTP).

**Deferred:** WebAuthn/passkeys; SMS/email OTP; IdP-side MFA-only (see Slice E).

---

### Slice E — SSO (OIDC)

**Depends on:** Slice C (OIDC callback exchanges IdP identity for **platform** tokens).

| Work | Detail |
| --- | --- |
| Mode | Platform as OIDC **Relying Party** (Authorization Code + PKCE). External IdP (Keycloak, etc.) — do not embed a realm admin UI |
| Config | `platform.oidc.enabled`, issuer, client id/secret, redirect URI(s), scopes `openid profile email` |
| Linkage | Map IdP `sub` (or email) → `account` via new `operator_idp_link` table; first login: **deny by default** unless link pre-provisioned or allow-list email domain config |
| Flow | Console `/login` → IdP → `/api/session/oidc/callback` → platform issues same access/refresh as password login |
| MFA | Prefer IdP MFA when SSO used; local TOTP still applies to password path; do not double-prompt if IdP `amr` indicates MFA (optional later) |
| Basic/password | Remain for break-glass local operators when OIDC enabled |

**Success criteria:** Linked operator signs in via IdP with no password to console; unlinked IdP user gets a clear refusal; tokens identical in shape to Slice C.

**Deferred:** Multi-IdP; SAML; SCIM provisioning; per-tenant IdP.

---

### Suggested calendar order / 建议顺序

`A → B → C → D → E`

- A/B unlock operator day-2 ops without changing the auth wire format (safe while Basic+encrypted session remains).
- C removes password retention (called out in `SECURITY.md` as the next hardening step) and is the foundation for D/E.
- D/E attach to the token login state machine.

If calendar pressure forces a cut: ship **A + C** before B; tenant UI can wait behind grants editor in A.

---

## 4. What stays deferred / 明确延后

| Item | Why |
| --- | --- |
| Online permission/role editor | Architecture: permissions stay in SQL/declarations |
| JWT access tokens / remote introspection | Opaque enough while AuthZ lives in platform-app |
| Login rate-limit / lockout | Still needed; small follow-up after C (or tiny slice C′): per-login counters in DB/Redis |
| WebAuthn, passkeys | After TOTP |
| Console CSRF tokens | SameSite=Strict cookie + same-origin proxy is enough for now |
| End-user (non-operator) identity | Out of platform-operator scope |
| Pushing from Debian build box | User preference: Mac push packs later |

---

## 5. Product decisions / 产品决策

Settled by industry norms + existing docs (no user input required to start A–C):

- OIDC for SSO; TOTP for local MFA; opaque Bearer access + rotating refresh; fail-closed grants unchanged; reserved tenant `platform` immutable; console never holds plaintext password.
- New permission `tenant.manage` for tenant writes (keeps `admin.read` read-only).
- Auto-create-on-task-submit can remain for backward compatibility once grants exist; explicit create is additive.

Ask the user only if they disagree with these defaults:

1. **OIDC first-login policy:** deny unlinked IdP users (recommended) vs auto-provision read-only operator.
2. **MFA enforcement:** optional for all vs required for `platform-operator` when `platform.mfa.required=true`.
3. **Basic cutoff:** keep Basic indefinitely for scripts vs default-off outside `local` after Slice C + one release.

---

## 6. Checklist (fill as slices land) / 落地勾选

- [ ] A Operator console create / admin password / grant editor
- [ ] B Tenant API + `/tenants` UI + `tenant.manage`
- [ ] C Login/refresh tokens; console drops Basic-at-rest
- [ ] D TOTP MFA + recovery codes
- [ ] E OIDC RP + IdP link table
- [ ] Follow-up: login lockout / rate limit
- [ ] Docs: SECURITY.md + operator-permissions.md + OpenAPI after each slice
