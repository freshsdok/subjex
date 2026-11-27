# WP7 end-user client shell progress / 终端用户客户端壳进度

- Date: 2026-11-20 (Asia/Shanghai)
- ADR: [`docs/adr/0019-end-user-client-shell.md`](../adr/0019-end-user-client-shell.md)
- Baseline tip when this note was opened: `2353fde` (WP6 payment port package closed; formal acceptance `0ab8160`)
- Predecessor: ADR [`0007`](../adr/0007-client-triad.md) (client triad: `web/` operator console, `clients/uni-app/` H5 + WeChat mini-program), ADR [`0014`](../adr/0014-end-user-identity.md) (EndUserPrincipal, `eu.at.` / `eu.rt.`, `/api/v1/end-user/**`), ADR [`0015`](../adr/0015-object-storage-authorization.md) (`ObjectGrantStore`, signed download, upload cap, MIME allowlist). Earlier uni-app auth/inbox notes: [`docs/clients/end-user-auth-progress.md`](end-user-auth-progress.md) (WP2-6 superseded the operator-token path; `/api/v1/me/inbox` and AUTH_END_USER listing remain residual)
- 本切片提交：`bf30bdf`（`bf30bdfceb08b51b49e657dd85b0d77b24adcf2d`；WP7-4 正式验收并**关闭 WP7 包**）。主按钮样式修复见 `d52f80f`（`d52f80f40f4ddc047d7fd5f8ae4565b22c4df9c7`）。不启动 WP8（同意与导出审批钩子）。未改 `web/`。

This track deepens the **end-user client shell** on `clients/uni-app` (H5 + WeChat mini-program, HBuilderX-compatible): login, profile, inbox, forms (questionnaires first), and files, using the WP2 end-user realm. It must stay generic: no hospital/medical, appointment, visa, insurance, or payment pages. `web/` stays the operator console. The platform is not rewritten.

本轨加深 `clients/uni-app` 终端壳（H5 + 微信小程序，兼容 HBuilderX）：登录、资料、站内信、表单（先问卷）与文件，走 WP2 终端领域。必须保持通用：无医院/医疗、预约、签证、保险或支付页面。`web/` 仍是操作员控制台。不改写平台。

Later shell-path polish (human home/login, Manifest coverage, post-login return) is in [`end-user-shell-polish-progress.md`](end-user-shell-polish-progress.md).
后续壳路径打磨（人话首页/登录、清单覆盖、登录后返回）见上述笔记。

## WP7-4 [done] — formal acceptance; WP7 package closed

This slice restores the uni-app primary button look (WP7-3 had dropped `type="primary"` so vue-tsc would pass) and locks formal acceptance, **closing the WP7 package**. WP8 (consent and export approval hooks) is not started. `web/` was not changed.

本切片恢复 uni-app 主按钮样式（WP7-3 为通过 vue-tsc 去掉了 `type="primary"`），并锁定正式验收，**关闭 WP7 包**。未启动 WP8（同意与导出审批钩子）。未改 `web/`。

Button typings / 按钮类型修复（`d52f80f`）：

- `vueCompilerOptions.nativeTags` lists the default HTML+SVG tags plus `hgroup` / `slot` / `component`, **excluding `button`**, so `<button>` resolves as the uni-app component rather than the HTML tag.
- `src/shime-uni.d.ts` declares `GlobalComponents.button` with `type?: "primary" | "default" | "warn"`.
- Main-action buttons on login, home, profile, files, inbox, and questionnaire pages again use `type="primary"`.
- `npx vue-tsc --noEmit`, `npx uni build` (H5) and `npx uni build -p mp-weixin` all passed (`DONE  Build complete`).

Acceptance met / 验收已满足：

- An end-user token in a business tenant can update its profile, read its own inbox entry, submit an `AUTH_END_USER` questionnaire once (a second submit is 409), and upload → list → download its own file.
- Operator tokens are rejected on end-user routes; another end user cannot see this user's inbox or files; reserved tenant `platform` and a missing tenant are rejected.
- Client guard: a static scan of `clients/uni-app/src` (comments stripped; text sources only) asserts no calls to `/api/v1/me/inbox`, operator questionnaire routes (`/api/v1/questionnaires` except `/public/`), or `/api/v1/auth/login`, and no domain pages (medical, appointment, payment, visa, or insurance).

Formal lock: `EndUserShellAcceptanceTest` 3/3. It does not repeat the WP7-1 / WP7-2 focused suites.

Regression (this turn, all passed): `EndUserShellAcceptanceTest` 3/3, `EndUserShellBackendTest` 6/6, `EndUserObjectUploadTest` 4/4, `EndUserCrossRealmIsolationTest` 34/34, `JdbcQuestionnairePortTest` 14/14, `QuestionnaireApiSecurityTest` 5/5. Total **66/66**. Pre-existing `OperatorBootstrapTest.readerRoleGetsOnlyReadPermissions` was not run and was not fixed.

Residual gaps (honest, not in this package) / 仍存缺口（本包不解决）：

- No chunked end-user upload (entity chunked sessions are not reused).
- Inbox attachments remain closed to end users.
- `/api/v1/me/inbox` was later retired (HTTP 410 `inbox-path-retired`); the end-user shell uses `/api/v1/end-user/inbox/**`.
- Questionnaire assignment / ACL later landed in a product-deepening slice: published `AUTH_END_USER` questionnaires are visible or fillable only when assigned to the person or an organization they belong to.
  问卷分配/ACL 已在后续产品加深切片落地：已发布的 `AUTH_END_USER` 问卷仅在分配给本人或其所在部门时可见可填。
- Profile includes `subjectName`, an authorized-track avatar (object id + short-lived download), and an email that must be verified before it is the profile address.
- Mini-program appid provisioning is still an operational concern, not delivered in this package.

Next package would be **WP8 (consent and export approval hooks)**, not started here.

## Slice status / 切片状态

- **WP7-0** — ✅ done.
- **WP7-1** — ✅ done.
- **WP7-2** — ✅ done.
- **WP7-3** — ✅ done.
- **WP7-4** — ✅ done (this slice). **WP7 package closed.**

## WP7-0 [done] — inventory + ADR

Docs-only. No schema, no routes, no uni-app pages added in this slice.

本切片只写文档，不改表、不加路由、不加页面。

### Inventory / 盘点（事实与路径）

#### uni-app pages (`clients/uni-app/src/pages.json`)

| Page | Path | What it does today |
| --- | --- | --- |
| Home / 首页 | `pages/index/index` | Shell home + nav to login, inbox, questionnaire list (`ui-permissions.ts` local labels; also fetches `GET /api/v1/ui-permissions?client=uni-app`) |
| Sign in / 登录 | `pages/login/login` | H5 phone request-code + login; mini-program also `uni.login` `jsCode` → WeChat login; explicit business `tenantId` (never `platform`) |
| Inbox / 站内信 | `pages/inbox/inbox` | Calls `/api/v1/me/inbox/**` (list / unread / mark-read) |
| Questionnaire list / 问卷列表 | `pages/questionnaire/list` | Calls `GET /api/v1/questionnaires?tenantId=` (authenticated list) and can open public collect by token |
| Questionnaire fill / 填写 | `pages/questionnaire/fill` | Public `GET`/`POST` `/api/v1/questionnaires/public/{tokenId}` (+ `/responses`); AUTH_END_USER submit HTTP is documented as not in that thin slice |

There is **no** profile page and **no** files page. There are no payment, hospital, appointment, visa, or insurance pages. `web/` is not part of this shell.

目前没有资料页与文件页，也没有支付或域业务页。

#### uni-app API modules and token handling

| Module | Path | Role |
| --- | --- | --- |
| `auth-token.ts` | `src/api/auth-token.ts` | Persist only `eu.at.` / `eu.rt.` in `uni` storage (`subjex_end_user_bearer` / `_refresh` / `_access_expires_at`). Leftover operator tokens are cleared. Does not use `web/` Redis / httpOnly sessions. |
| `auth.ts` | `src/api/auth.ts` | Calls only `/api/v1/end-user/auth/**` (phone request-code / login / bind / unbind; WeChat login / bind / unbind; refresh; logout). Does not call operator `/api/v1/auth/**`. |
| `tenant.ts` | `src/api/tenant.ts` | Explicit business `tenantId` in storage / `X-Tenant-Id`. Never defaults to reserved `platform`; client never creates a tenant. |
| `platform.ts` | `src/api/platform.ts` | Thin `uni.request` / fetch wrapper; `VITE_SUBJEX_API_BASE` or `http://127.0.0.1:8080`. |
| `inbox.ts` | `src/api/inbox.ts` | `/api/v1/me/inbox/entries`, `/unread-count`, `.../entries/{id}/read`. |
| `questionnaire.ts` | `src/api/questionnaire.ts` | Public collect by token; authenticated list still hits operator `GET /api/v1/questionnaires`. |
| `ui-permissions.ts` | `src/api/ui-permissions.ts` | `GET /api/v1/ui-permissions?client=uni-app`. |

The project is uni-app Vue3 + Vite (`package.json`: `dev:h5`, `dev:mp-weixin`). `manifest.json` has H5 hash routing and an empty WeChat `mp-weixin.appid`.

#### Backend realm gate (ADR 0014)

`EndUserRealmGateFilter` (`platform-app/.../security/EndUserRealmGateFilter.java`) allows an `EndUserPrincipal` only on:

- `/api/v1/end-user/**`
- `/actuator/health`
- `/api/v1/questionnaires/public/**`

Any other path, including `/api/v1/me/inbox/**`, `GET /api/v1/questionnaires`, `GET /api/v1/ui-permissions`, `/api/v1/inbox/**`, `/api/v1/forms/**`, and `/api/v1/objects/**`, returns HTTP 403 `reason=operator-authentication-required` when the Bearer is an end-user token.

#### Routes the shell needs — realm gate today

| Need | Route today | Principal / gate | Citation |
| --- | --- | --- | --- |
| Phone / WeChat login, bind, unbind, refresh, logout | `POST /api/v1/end-user/auth/phone/request-code`, `/phone/login`, `/phone/bind`, `/phone/unbind`; `POST /api/v1/end-user/auth/wechat/login`, `/wechat/bind`, `/wechat/unbind`; `POST /api/v1/end-user/auth/refresh`, `/logout` | End-user realm. Login/request-code/refresh/logout are `permitAll`; bind/unbind require `EndUserPrincipal`. Operator tokens are rejected on bind/unbind. | `EndUserPhoneAuthEndpoint`, `EndUserWeChatAuthEndpoint`, `EndUserAuthEndpoint` (`PATH=/api/v1/end-user/auth`) |
| Profile read / update display fields | **Missing.** No `GET`/`PATCH` under `/api/v1/end-user/`. Display name is `Subject.subjectName` on `EndUserRecord`. Phone/WeChat bind already exists (rows above). | — | `EndUserRecord`, `EndUserAccountStore.provision` / `bindPhone` / `bindIdp` |
| Inbox list / unread / read / mark-read | `GET/POST /api/v1/me/inbox/entries`, `GET .../unread-count`, `GET .../entries/{entryId}`, `POST .../entries/{entryId}/read` | Intended as IM-6 END_USER mailbox, but `@AuthenticationPrincipal OperatorPrincipal` and `inbox.read` + tenant grant. **Outside** the realm-gate allowlist, so an `eu.at.` token is 403 before the method. Operator `/api/v1/inbox/**` is a separate send/attachment surface, also `OperatorPrincipal`. | `EndUserInboxEndpoint.BASE=/api/v1/me/inbox`; `InboxEndpoint.BASE=/api/v1/inbox`; `EndUserRealmGateFilter.allowsEndUser`; `InboxService.listOwnEndUser` |
| Inbox attachment download | `GET /api/v1/inbox/entries/{entryId}/attachments/**` | Operator only (`InboxEndpoint`). Attachments are not on `ObjectGrantStore`. No end-user attachment route. | `InboxEndpoint`; inventory in ADR 0015 / WP3 progress |
| Questionnaire public collect (ANON_LINK) | `GET /api/v1/questionnaires/public/{tokenId}`, `POST .../public/{tokenId}/responses` (`X-Client-Key` required on submit) | `permitAll`; on the end-user allowlist. No principal. | `QuestionnaireEndpoint` |
| Questionnaire AUTH_END_USER list / fetch / submit | List used by uni-app: `GET /api/v1/questionnaires?tenantId=`. Other `/api/v1/questionnaires/**` (get by id, publish, responses, stats, collect-token, preview) | **`OperatorPrincipal` + questionnaire read/write authorities.** Not on the end-user allowlist → `eu.at.` gets 403. No `/api/v1/end-user/questionnaires/**`. Domain support already distinguishes `AUTH_END_USER` vs `ANON_LINK` (`QuestionnaireSubmitSupport`), but there is no authenticated end-user HTTP. | `QuestionnaireEndpoint.requireOperator`; `questionnaire.ts` comment: “AUTH_END_USER authenticated submit HTTP is a deepen” |
| Generic forms | `GET /api/v1/forms`, `GET /api/v1/forms/{formKey}`, `POST/GET /api/v1/forms/{formKey}/submissions` | `OperatorPrincipal`. Not on the end-user allowlist. uni-app has no form-render pages. | `FormsApiEndpoint`, `FormSubmissionEndpoint` |
| File download (signed link) | `POST /api/v1/end-user/objects/download-links` (WP3-2); redeem `GET /api/v1/end-user/objects/content` (`permitAll`, signature is the credential). Operator issue: `POST /api/v1/objects/download-links` (`OperatorPrincipal` only). | End-user issue requires `EndUserPrincipal` and is under the allowlisted prefix. Authorization is `ObjectGrantStore` (membership is not enough). | `ObjectDownloadEndpoint` |
| File list | **Missing** for end-users. | — | — |
| File upload | **Missing** for end-users. Operator upload is `POST /api/v1/entities/{entityKey}/records/{id}/blobs` (and multipart / chunked) via `EntityBlobEndpoint` (`OperatorPrincipal` + declaration AuthZ). Unscoped `entity_blob` and inbox attachments are **not** on `ObjectGrantStore`. Existing cap: `ObjectUploadLimits` / `platform.storage.upload.max-bytes` (default 50 MiB). Existing MIME allowlist (authorized track + inbox): `ObjectContentTypes` — `application/pdf`, `image/png`, `image/jpeg`, `image/webp`, `text/plain`, Office Open XML docx/xlsx/pptx. | `EntityBlobEndpoint`, `ObjectUploadLimits`, `ObjectContentTypes` |
| UI permission manifest | `GET /api/v1/ui-permissions?client=uni-app` | `OperatorPrincipal`. Outside the allowlist → end-user token 403. Shell has a local menu fallback. | `UiPermissionEndpoint` |

## WP7-1 [done] — backend profile, inbox, questionnaire

Feature SHA `b7131e5` (`b7131e5fa5a3047db54f78e00287105c80441b47`). Backend only: no uni-app or domain pages, no WP7-2 upload/list.

本切片仅后端；不改 uni-app、不加域页面、不启动 WP7-2。

### Conservative defaults (changeable later) / 保守默认（以后可改）

Recorded here so later slices can revisit them without treating this implementation as a product lock.

此处记录，便于后续切片回看，不把本次实现当成产品锁死。

- Questionnaire list (WP7 default, later tightened): only questionnaires that are **published** in the end user's **current business tenant** and are `AUTH_END_USER`. A later slice added assignment: unassigned published questionnaires are not visible to signed-in users.
  问卷列表（WP7 默认，后来收紧）：仅当前业务租户已发布且采集模式为 `AUTH_END_USER` 的问卷。后续切片增加分配：已发布但未分配的认证问卷不对登录用户可见。
- Leave `/api/v1/me/inbox` (operator-wired leftover) **unchanged**; no retirement in this slice.
  当时保留 `/api/v1/me/inbox`，本切片不退役。随后已退役为 HTTP 410。
- Inbox **attachments stay closed** to end users (not exposed on the new routes).
  终端收件箱不暴露附件。
- Profile: display name, authorized-track avatar, and email stored pending until a verification code is consumed.
  资料含展示名、授权轨头像，以及须验证后才生效的邮箱。
- No end-user `ui-permissions` endpoint.
  不加终端 `ui-permissions` 接口。

### Routes / 路由（均需 `EndUserPrincipal` + 显式业务租户）

- `GET` / `PATCH /api/v1/end-user/profile` — own subject display name; PATCH validates 1–256 chars and audits via `AuditPort` (`end-user.profile.update`).
- `GET /api/v1/end-user/inbox/entries`, `GET .../unread-count`, `GET .../entries/{entryId}`, `POST .../entries/{entryId}/read` — own `END_USER` subject + current tenant; reuse `InboxPort`; another user's message is 404.
- `GET /api/v1/end-user/questionnaires`, `GET .../{questionnaireId}`, `POST .../{questionnaireId}/responses` — published `AUTH_END_USER` only; submit records the end-user subject; existing one-submission rule → 409.

Realm isolation: operator principals are rejected on these routes (`end-user-authentication-required`); end-user tokens stay rejected on operator routes via existing `EndUserRealmGateFilter` (regression). Tenant fail-closed (no membership → 403 `end-user-tenant-access-denied`); reserved `platform` denied (existing `requireExplicitBusinessTenantId` → 400). Missing tenant header → 403.

领域隔离：这些路由拒绝操作员主体；终端令牌在操作员路由上仍由既有门禁拒绝。租户失败关闭；保留租户 `platform` 拒绝。

### Tests / 测试

`EndUserShellBackendTest` 6/6 plus named regressions, all passing (75/75):

- `EndUserShellBackendTest` 6
- `EndUserPhoneAuthTest` 4
- `EndUserWeChatAuthTest` 6
- `EndUserCrossRealmIsolationTest` 34
- `EndUserAccountStoreTest` 3
- `EndUserTokenStoreTest` 3
- `JdbcQuestionnairePortTest` 14
- `QuestionnaireApiSecurityTest` 5

Did not run or fix `OperatorBootstrapTest.readerRoleGetsOnlyReadPermissions`.

### Next / 下一步

**WP7-2** — [done] see below. Next is **WP7-3** (uni-app pages). Do not start WP7-3 here.

## WP7-2 [done] — end-user object upload and list

Feature SHA `44c04c6` (`44c04c6648f25545b6f08c94e78eba801d039182`). Backend only: no uni-app, no WP7-3.

本切片仅后端；不改 uni-app、不启动 WP7-3。

### Routes / 路由

- `POST /api/v1/end-user/objects` — multipart part `file`; `EndUserPrincipal` + explicit business tenant (`X-Tenant-Id`). Writes bytes via existing `ObjectStorage`, registers `object_descriptor` with the end-user subject as owner. Reuses `platform.storage.upload.max-bytes` (default 50MB) and `ObjectContentTypes`. Oversize → 413 `object-upload-too-large`; disallowed MIME → 415 `object-content-type-not-allowed`. Audit via existing `ObjectAccessAudit` / `AuditPort` (`object.upload`).
- `GET /api/v1/end-user/objects` — lists objects the caller owns or holds an unexpired `SUBJECT` grant for in the current tenant (`limit`/`offset`, default 50, max 200). Does not list organization grants, inbox attachments, or unregistered `entity_blob`.
- Download unchanged: `POST /api/v1/end-user/objects/download-links` then local HMAC redeem `GET /api/v1/end-user/objects/content`.

Object keys are a single name (`eu-{subjectId}-{uuid}`); slashes are rejected by the existing grant/storage rules. Chunked end-user upload was **skipped** (entity chunked sessions are not trivially reusable).

对象键为单名（不含斜杠）。本切片不做终端分块上传。

### Tests / 测试

`EndUserObjectUploadTest` 4/4 plus named regressions, all passing (99/99):

- `EndUserObjectUploadTest` 4
- `EndUserShellBackendTest` 6
- `EndUserCrossRealmIsolationTest` 34
- `ObjectGrantAccessTest` 18
- `ObjectAccessIsolationTest` 8
- `ObjectAccessAuditTest` 5
- `ObjectDownloadLinkServiceTest` 19
- `ObjectUploadCapTest` 5

Did not run or fix `OperatorBootstrapTest.readerRoleGetsOnlyReadPermissions`.

### Next / 下一步

**WP7-3** — [done] see below. Next is **WP7-4** (formal acceptance and close WP7). Do not start WP7-4 here.

## WP7-3 [done] — uni-app pages on end-user routes

Feature SHA `a1c7014` (`a1c70145fc967d26da85c6ce3e6f494e36a0778e`). Client only: no backend or `web/` changes, no WP7-4.

本切片仅 uni-app 客户端；不改后端与操作员控制台，不启动 WP7-4。

### Pages and APIs / 页面与接口

- 新页面：`pages/profile/profile`（查看/修改称呼、退出登录）、`pages/files/files`（列表、`uni.uploadFile` 字段 `file`、签名链接打开）。
- 既有页面改走终端路由：站内信 ` /api/v1/end-user/inbox/**`；登录问卷列表/读取/提交 `/api/v1/end-user/questionnaires`、`/{id}`、`/{id}/responses`（已提交 409 中文提示）。公开填写码（ANON）路径保留。
- 首页本地导航增加「我的资料」「我的文件」。仍用首页链接，未加 tabBar（与原壳一致）。
- HTTP：`uni.request` / `uni.uploadFile`；带终端 Bearer 与 `X-Tenant-Id`。401 先刷新再重试，失败则进入登录页。403/409/413/415 弹出中文说明。

### Verify / 校验

Installed deps with `npm ci` (lockfile). Ran:

- `npx vue-tsc --noEmit` — 通过
- `npx uni build` (`build:h5`) — 通过（DONE Build complete）
- `npx uni build -p mp-weixin` (`build:mp-weixin`) — 通过（DONE Build complete）

`node_modules` / `dist` 未提交。为通过 vue-tsc 对 HTML `button type` 的检查，去掉了页面上的 `type="primary"`（含登录页与首页原有按钮），不影响功能。

### Next / 下一步

**WP7-4** — ✅ done (see close section above). The `type="primary"` regression noted in Verify was fixed in WP7-4 (`d52f80f`).

## Proposed later slices / 后续切片

See ADR 0019. Sketch:

- **WP7-1** — [done] backend profile, own inbox, AUTH_END_USER questionnaires under `/api/v1/end-user/**`.
- **WP7-2** — [done] backend end-user object upload and list through `ObjectGrantStore`; reuse signed download; existing size cap and MIME allowlist.
- **WP7-3** — [done] uni-app pages and clients: profile, files, inbox/questionnaire on `/api/v1/end-user/**`; H5 + mini-program nav; no domain pages.
- **WP7-4** — [done, `bf30bdf`] formal acceptance and close. **WP7 package closed.**

Next package is **WP8 (consent and export approval hooks)**; do not start it here.

## Open points not settled from existing docs / 现有文档无法锁死、故不臆造

See ADR 0019. WP7-1 applied the conservative defaults listed above; they remain changeable later.

## SHA

- WP7-0：`2b43c74` (`2b43c746a2ebf849d3ea695af7b07ea8273777cf`)
- WP7-1：`b7131e5` (`b7131e5fa5a3047db54f78e00287105c80441b47`)
- WP7-2：`44c04c6` (`44c04c6648f25545b6f08c94e78eba801d039182`)
- WP7-3：`a1c7014` (`a1c70145fc967d26da85c6ce3e6f494e36a0778e`)
- WP7-4 typings / primary button：`d52f80f` (`d52f80f40f4ddc047d7fd5f8ae4565b22c4df9c7`)
- WP7-4 acceptance：`bf30bdf` (`bf30bdfceb08b51b49e657dd85b0d77b24adcf2d`)
