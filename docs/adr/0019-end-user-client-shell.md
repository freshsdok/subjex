# ADR 0019 — End-user client shell / 终端用户客户端壳

- Status: **Accepted** — direction only (WP7-0). Implementation starts at WP7-1.
- Date: 2026-11-20 (Asia/Shanghai)
- Scope: End-user shell on `clients/uni-app` (H5 + WeChat mini-program, HBuilderX-compatible): login (already landed), profile, inbox, questionnaires, and files, on the WP2 end-user realm. Not the operator console, not a platform rewrite, not domain checkout (hospital, appointment, visa, insurance, payment)
- Relates: ADR [0007](0007-client-triad.md) (client triad), ADR [0014](0014-end-user-identity.md) (EndUserPrincipal, realm gate, never default `platform`), ADR [0015](0015-object-storage-authorization.md) (ObjectGrantStore, signed download, 50 MiB cap, MIME allowlist), ADR [0008](0008-in-app-messaging.md) / [0009](0009-inbox-attachments-and-group-broadcast.md) (inbox), ADR [0013](0013-questionnaire-platform-capability.md) (questionnaires), inventory in [`docs/clients/wp7-end-user-shell-progress.md`](../clients/wp7-end-user-shell-progress.md)

## Context / 背景

WP6 is closed (`2353fde`, acceptance `0ab8160`). The base work doc’s next package is the **end-user client shell**: login, profile, inbox, forms (including questionnaires), and files, using the WP2 realm (`EndUserPrincipal`, `eu.at.` / `eu.rt.`, `/api/v1/end-user/**`). The shell must stay generic. `web/` stays the operator console. The platform is not rewritten.

Factual inventory lives in the progress note. In short:

- `clients/uni-app` already has H5 + mini-program scaffolding, `pages/login/login.vue`, token handling that accepts only `eu.at.` / `eu.rt.`, and API modules for auth, tenant, inbox, questionnaires, and UI permissions. There is no profile or files page, and no domain pages.
- `EndUserRealmGateFilter` allows end-user tokens only on `/api/v1/end-user/**`, `/actuator/health`, and `/api/v1/questionnaires/public/**`. Other paths return 403 `operator-authentication-required`.
- `/api/v1/me/inbox/**` and AUTH_END_USER questionnaire list/fetch/submit still require `OperatorPrincipal`; end-user tokens therefore get 403 (realm gate and/or method principal). Public ANON collect works. Phone/WeChat bind/unbind already exist under `/api/v1/end-user/auth/**`. There is no profile HTTP.
- `POST /api/v1/end-user/objects/download-links` exists (WP3). There is no end-user upload or list route. Inbox attachments and unscoped entity blobs are not on `ObjectGrantStore`. Upload cap (default 50 MiB) and MIME allowlist (`ObjectContentTypes`) already exist.

WP6 已关闭。下一包是终端客户端壳。盘点见进度笔记：登录与令牌已落地；站内信与认证问卷接口仍要操作员主体，终端令牌会 403；下载签发已有，上传/列表与资料接口还没有。

## Decision / 决策

### D1 — End-user variants under `/api/v1/end-user/**`; do not mix realms on operator routes

Keep the ADR 0014 isolation: an `EndUserPrincipal` is not an `OperatorPrincipal`. Membership in a tenant is not authorization (ADR 0015). This package does **not** open operator routes (`/api/v1/inbox/**`, `/api/v1/questionnaires` admin/list, `/api/v1/forms/**`, `/api/v1/objects/download-links`, `/api/v1/entities/**/blobs`, `/api/v1/ui-permissions`) to end-user tokens by widening `EndUserRealmGateFilter` onto those operator paths.

New or replacement shell APIs (profile, inbox list/read, AUTH_END_USER questionnaire list/fetch/submit, object list/upload) are added as **end-user variants under `/api/v1/end-user/**`**, which is already on the realm-gate allowlist, and take `EndUserPrincipal`. Operator consoles keep the existing operator paths and principals.

The current IM-6 surface `/api/v1/me/inbox/**` is a leftover: it is named as an end-user mailbox but is wired to `OperatorPrincipal` and sits **outside** the allowlist. WP7-1 moves the product path to `/api/v1/end-user/inbox/**` (or an equivalent under that prefix). What happens to `/api/v1/me/inbox` after the new path works is an open point (retire, or leave as a deprecated operator-principal curiosity); this ADR does not invent a shared “`/me`” for both realms.

Public ANON questionnaire collect (`/api/v1/questionnaires/public/**`) stays `permitAll` and on the allowlist. Signed-download redeem (`GET /api/v1/end-user/objects/content`) stays signature-as-credential.

端用户接口放在已放行的 `/api/v1/end-user/**` 下，使用 `EndUserPrincipal`。不把操作员路由扩给终端令牌。现有 `/api/v1/me/inbox` 是残留，产品路径迁到 `/api/v1/end-user/inbox/**`。租户成员关系不是授权。

### D2 — Minimal profile API: read and update display fields; binding stays on auth routes

Add `GET` and `PATCH` (or `PUT`) `/api/v1/end-user/profile`, authenticated as `EndUserPrincipal`.

- **Read** returns at least: `accountId`, `subjectId`, `loginName`, display name (`Subject.subjectName` on `EndUserRecord`), and whether a phone / WeChat binding exists if that can be derived from existing `EndUserAccountStore` data. It does not invent new identity columns.
- **Update** is limited to display fields (at least display name / `subject_name`). It does not change `accountId`, `subjectId`, or login identity here.
- Phone and WeChat **bind / unbind already exist** (`POST /api/v1/end-user/auth/phone/bind|unbind`, `/wechat/bind|unbind`) and stay there. The profile API does not re-implement binding.

资料接口只读写展示字段；手机号/微信绑定仍走已有认证路由。

### D3 — Inbox and questionnaire visibility is own subject + current business tenant only

Every end-user inbox and AUTH_END_USER questionnaire call requires an explicit business `tenantId` (header or body/query). The port never defaults reserved `platform`. The caller may only see and mutate rows for **their `subjectId` in that tenant**. Cross-tenant access is denied. Seeing another subject’s inbox or responses is denied. Tenant membership alone does not grant access to another person’s mailbox, files, or submissions.

- **Inbox:** list / get / mark-read of own `InboxRecipientRealm.END_USER` entries in that tenant. No send, compose, broadcast, or group-admin on the end-user surface.
- **Questionnaires:** list and fetch **published** `AUTH_END_USER` questionnaires in that tenant; submit as the caller’s end-user subject; list/read **own** responses only. Public ANON collect stays the token + client-key path. Operator publish/close/stats/export stay on operator routes.

站内信与认证问卷只可见/可改本人在当前业务租户下的数据；成员关系不能看他人数据。

### D4 — End-user upload and list go through `ObjectGrantStore`, with the existing cap and MIME allowlist

Add end-user **list** and **upload** under `/api/v1/end-user/objects/**`. Uploads are registered on the authorized-object track (`ObjectGrantStore` / object descriptor): the caller’s subject is the owner (or an equivalent owner grant). Authorization for later download stays grant-based; membership is not enough.

Reuse the existing signed-download issue path `POST /api/v1/end-user/objects/download-links` and local redeem. Do **not** send end-user uploads down the unscoped `entity_blob` path or the operator inbox-attachment path (those are still not on `ObjectGrantStore`).

Size and type reuse what WP3 already locked, not a second policy:

- Cap: `ObjectUploadLimits` / `platform.storage.upload.max-bytes` (default 50 MiB).
- MIME: `ObjectContentTypes` allowlist (`application/pdf`, `image/png`, `image/jpeg`, `image/webp`, `text/plain`, Office Open XML docx/xlsx/pptx). Types not on the list fail closed and are not stored. Medical/imaging types have no special status.

终端上传与列表走 `ObjectGrantStore`，复用已有 50 MiB 上限与 MIME 白名单；不走未授权的 entity_blob 或操作员附件路径。

### D5 — uni-app page set and navigation stay a generic H5 + mini-program shell

`clients/uni-app` remains the only end-user client (ADR 0007). One project emits H5 and 微信小程序; keep `pages.json` / `manifest.json` compatible with HBuilderX. `web/` stays the operator console; this package does not add end-user pages there.

Intended shell pages (existing + this package):

- Already present: home, login, inbox, questionnaire list, questionnaire fill.
- To add: **profile**, **files** (list / download via signed link / upload).
- Navigation stays generic (home / login / inbox / questionnaires / profile / files). No hospital, medical, appointment, visa, insurance, or payment pages, and no operator-console clone.

Login remains phone (H5) + WeChat `jsCode` (mini-program) with an explicit business tenant. Token storage stays `eu.at.` / `eu.rt.` only.

页面保持通用壳：已有登录/站内信/问卷，新增资料与文件；H5 与小程序同一工程。不做域页面，不改写 `web/`。

### D6 — Small slices; backend first, then uni-app, then acceptance

Implementation is sliced (below). This ADR does not start WP7-1, does not rewrite the platform, and does not put domain product rules in the kernel or the shell.

## Proposed implementation slices / 拟定实现切片

| Slice | Intent | Not in that slice |
| --- | --- | --- |
| **WP7-0** | This ADR + inventory progress note | Code / schema |
| **WP7-1** | Backend: profile API; `/api/v1/end-user/inbox/**` with `EndUserPrincipal`; AUTH_END_USER questionnaire list/fetch/submit under `/api/v1/end-user/questionnaires/**`; own subject + tenant scope | Upload/list files; uni-app pages; generic `form_submission` end-user API |
| **WP7-2** | Backend: end-user object list + upload through `ObjectGrantStore`; reuse download-links; existing cap + MIME | uni-app files page; S3 multipart protocol changes; grant-console HTTP |
| **WP7-3** | uni-app: profile + files pages; point inbox/questionnaire clients at the new end-user routes; nav for H5 + mini-program | Formal acceptance; domain pages; rewriting `web/` |
| **WP7-4** | Formal acceptance: signed-in end-user can use profile, inbox, questionnaire, and files through the shell; cross-tenant and operator-route access stay denied. Close the WP7 package | Domain products; production WeChat `mp-weixin.appid` provisioning as a platform feature |

## Consequences / 后果

- **Positive:** The shell uses one realm prefix and one principal type; operator isolation is unchanged; file access stays grant-based with existing caps.
- **Negative:** Until WP7-1, the existing inbox and authenticated questionnaire uni-app calls remain 403 with a real end-user token. `/api/v1/me/inbox` is not the long-term product path. Generic form-render / `form_submission` is not in WP7-1…WP7-4 unless a later brief says so.

## Out of scope / 非目标

- Hospital, medical, appointment, visa, insurance, or payment pages or kernel rules (WP6 payment port stays a separate backend; this shell does not add a pay sheet).
- Rewriting `web/`, merging operator and end-user tokens, or defaulting tenant `platform`.
- A second mobile stack (Taro / RN / Flutter) — ADR 0007 still holds.
- Production vendor payment or notification adapters.
- Automatically widening `EndUserRealmGateFilter` to operator management APIs.

## Open points not settled from existing docs / 现有文档无法锁死、故不臆造

- Exact retirement of `/api/v1/me/inbox` once `/api/v1/end-user/inbox/**` exists — later settled as HTTP 410 (`inbox-path-retired`), not a redirect and not a second working mailbox.
- Whether AUTH_END_USER listing is “all published questionnaires in the tenant” (the only model that exists today) or a later assignment/ACL product — this ADR does not invent assignment.
- Whether a later slice adds end-user variants of generic `GET/POST /api/v1/forms/**` / form-render. WP7 ships questionnaires first; a general form shell is not invented here.
- Whether the shell needs an end-user-safe `ui-permissions` route, or whether local menu fallback in uni-app is enough (`GET /api/v1/ui-permissions` is operator-gated today).
- Inbox attachment download for end-users (today attachments are on the operator inbox path, not `ObjectGrantStore`). A later slice may attach via the authorized-object track; not designed here.
- WeChat mini-program `mp-weixin.appid` provisioning, release-channel config, and store listing — not a kernel product.
- Profile fields beyond display name (avatar, email) if no column exists.

## References / 引用

- [`docs/clients/wp7-end-user-shell-progress.md`](../clients/wp7-end-user-shell-progress.md)
- [`docs/clients/end-user-auth-progress.md`](../clients/end-user-auth-progress.md)
- [`docs/identity/wp2-end-user-progress.md`](../identity/wp2-end-user-progress.md)
- [`docs/storage/wp3-object-auth-progress.md`](../storage/wp3-object-auth-progress.md)
- ADR 0007 / 0014 / 0015 / 0008 / 0009 / 0013
