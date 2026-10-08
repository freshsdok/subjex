# web/ progress — 前端进度笔记

Slice log — 切片记录（每片做完即本地提交）:

1. [done] Scaffold: create-next-app 16.3.8 (TS, Tailwind 4, ESLint, App Router, src/). Node 20.19 is enough (next needs >=20.9).
   脚手架：已建好，Node 20.19 够用。
2. [done] Dev deps: vitest@4.1.11 (vitest 5 needs Node 22) + openapi-typescript; scripts test/typecheck/gen:api. Debian npm 9.2 crashed (edgesOut bug) -> use npm 10 at ~/.local/bin (on PATH via ~/.bashrc).
   开发依赖已装；系统自带 npm 9.2 有 bug，改用 ~/.local/bin 下的 npm 10。
3. [done] `openapi.json` (snapshot of /api/v1/openapi.json, 10 paths) -> `src/api/schema.d.ts` via `npm run gen:api`. The earlier 403 was a stale 12:38 jar; rebuilt jar returns 200.
   接口类型已生成；之前的 403 是旧 jar 导致，重建后正常。
4a. [done] `src/server/operator-session.ts` (in-memory session map, random id in httpOnly SameSite=Strict cookie `subjex_session`, 8h), `/api/session` POST sign-in (verifies via /api/v1/me) + DELETE sign-out, `/api/platform/[...segments]` GET/PUT proxy to /api/v1. Smoke-tested with curl: 401 without session, 401 wrong password, 200 after login, 401 after logout.
    代理与会话已完成并手测通过。
4b. [done] `/login` (client SignInForm, reasons shown inline), `(console)` route group layout (header with operator + sign-out, side nav) and overview page listing permissions; `src/i18n/phrases.ts` zh/en (cookie `subjex_language`, default zh); `src/server/platform-reader.ts` server reads with redirect on 401. Dropped Google fonts (system font stack). Smoke: / -> 307 /login signed out; signed in shows permissions.
    登录页与控制台外框完成。
5a. [done] Nav from `(console)/console-sections.ts` (locked sections greyed with 'needs permission X' tooltip, not hidden); `components/page-state.tsx` (PageHeading, ForbiddenNotice on 403, LoadFailedNotice); `/services` and `/audit` tables; `fillPhrase` placeholders.
    服务页、审计页、导航权限置灰已完成。
5b. [done] `/config` table (key, effective value, origin word); `config-override-cell.tsx` edit -> review summary (before/after, audited) -> confirm save via PUT proxy; read-only notice without config.write. Smoke: PUT returned origin=override and audit entry recorded.
    配置页与两步确认修改已完成。
5c. [done] `/deploy` (applied/not-applied notice first, then workloads), `/forms` (disabled preview fieldset), `/codegen` (record source preview + field/type table).
    部署、表单、代码生成三页完成。
5d. [done] `components/appearance-switcher.tsx` in header (language select + skin select, cookies `subjex_language`/`subjex_skin`, 1 year); `server/skin.ts` maps /api/v1/skins `--page-*` variables onto console CSS variables on the console wrapper. Login page uses default skin.
    语言与外观切换完成。
6a. [done] Vitest 8 tests in `tests/` (phrases, operator-session expiry, skin mapping via exported `skinStyleFor`); `next build` passes (11 routes, all dynamic).
    单元测试 8 个通过，生产构建通过。
6b. [done] Playwright Chromium screenshots in `docs/screenshots/` (login, wrong password, overview, services, config review, audit, en+calm). Install lives under /workspace/web-shots (not in repo).
    截图已落盘。
6c. [done] `docs/design-notes.md` (Nacos/Consul/Spring Boot Admin/Keycloak real issue URLs + 待讨论 6 点), web README, root README console section.
6d. [done] Pushed as remote `2d9577f` (tree of all web/ slices since 8abf74d). Local commits remain as history; remote is one squash-style Git Data commit.
    已推送到 GitHub。
5. [todo] Screens: config, audit, deploy, forms, codegen; i18n zh/en; three skins.

Dev runtime — 开发运行环境: `next dev -p 3000` pid in /tmp/nextdev.pid, log /tmp/nextdev.log; docker container `subjex-web-dev-pg` (postgres:16-alpine, 127.0.0.1:15432, subjex/subjex/subjex); app log /tmp/subjex-dev/app.log, pid in /tmp/subjex-dev/app.pid. Rebuild jar first: `mvn -o -q package -DskipTests -pl platform-app -am`.

Round 2 — 第二轮（2026-10-05 决策后）:

7a. [done] Decisions + tech debt TD-1 (in-memory session) recorded in docs/design-notes.md; login page language select.
    决策与技术债已记录；登录页可选语言。
7b. [done] Overview sections: service health, recent audit (latest 5), unapplied deploy notice; each section degrades to a permission hint on 403.
    概览分区已上线。
7c. [done] Real form submit: POST /api/v1/forms/{formKey}/submissions validates fields, registers service (registry.write + audit), UI edit → review → confirm; proxy POST enabled.
    表单真提交已打通。

7d. [done] Pushed as remote `96c66e5` (decisions, overview sections, form submit).
    已推送到 GitHub。

Node 5 / TD-1 — 节点五会话:

8a. [done] Operator sessions use Redis when SESSION_REDIS_URL or REDIS_URL is set; otherwise in-memory (tests + single-node). Call sites are async.
    配置了 Redis 地址则会话进 Redis；否则仍用内存。调用方已改为 async。

Auth roadmap (design only) — 认证路线图（仅设计，2026-10-05）:

9a. [done] Inventory + plan in `docs/auth-roadmap.md` (no token/SSO/MFA/UI code this slice).
    盘点现状并写入路线图；本片不实现令牌 / SSO / MFA / 界面。
    Next slices (see roadmap): A operator console create/password/grants → B tenants → C Bearer tokens (drop Basic-at-rest) → D TOTP → E OIDC.
    下一片：A 操作员控制台补齐 → B 租户管理 → C 平台令牌 → D TOTP → E OIDC。

Auth roadmap Slice A — 操作员控制台补齐（2026-10-05）:

9b. [done] Console operator admin UI against existing `/api/v1/operators/**`: create (login/password/role, review→confirm), admin password reset, tenant grants editor (add/remove including `*`, review→confirm). Fail-closed notice for empty grants. zh/en phrases. Minimal vitest for grant helpers.
    控制台操作员管理：新建、管理员重置口令、租户授权编辑（含通配 *）；无授权失败关闭提示；中英文案；授权辅助单测。
    Next: Slice B tenants API + `/tenants` UI. Do not start C–E until A/B land as planned.
    下一片：B 租户 API 与界面。按计划先不要开 C–E。

Auth roadmap Slice B — 租户管理（2026-10-05）:

9c. [done] `tenant.manage` permission (V7); `/api/v1/tenants` list/get/create/rename/disable/enable; soft-disable = SUSPENDED; reserved `platform` immutable; task submit refuses SUSPENDED; console `/tenants` with review→confirm; nav; zh/en; vitest helpers.
    权限、REST、软禁用、保留租户不可改、禁用租户拒绝任务、控制台页与导航、中英文案、辅助单测。
    Next: Slice C Bearer tokens (drop Basic-at-rest). Do not start D–E until C lands.
    下一片：C 平台令牌。按计划先不要开 D–E。

Auth roadmap Slice C — 平台令牌（2026-10-05）:

9d. [done] Opaque Bearer access + rotating refresh; console drops Basic-at-rest entirely. Login via `/api/v1/auth/login`; session stores encrypted access/refresh; proxy Bearer + silent refresh; logout revokes family. Vitest session tests updated.
    不透明 Bearer + 轮换刷新；控制台不再静态保留 Basic。登录走令牌端点；会话只存加密令牌；代理 Bearer 与静默刷新；退出吊销族。
    Next: Slice D TOTP MFA. Do not start E until D lands as planned.
    下一片：D TOTP。按计划先不要开 E。

Auth roadmap Slice D — TOTP MFA（2026-10-08）:

9e. [done] TOTP (RFC 6238) enroll/confirm/disable; login mfaToken challenge + verify; recovery codes once; AES-GCM secret at rest (V9); console second login step + Operators self enroll/disable. `platform.mfa.required=false` initially.
    TOTP 登记/确认/关闭；登录挑战与校验；恢复码一次性；密钥 AES-GCM 入库（V9）；控制台二次登录与操作员页自助登记。默认不强制。
    Next: Slice E OIDC. Do not start E until product asks.
    下一片：E OIDC。等产品明确要求再开。

Auth roadmap Slice E — OIDC SSO（2026-10-08）:

9f. [done] OIDC RP + PKCE; `operator_idp_link` (deny unlinked); `/api/v1/auth/oidc/{status,start,callback}` → same Bearer as C; SSO skips local TOTP; admin bind/unlink API + Operators UI; console SSO button + callback routes. Flyway V10. `platform.oidc.enabled=false` by default.
    OIDC 依赖方 + PKCE；绑定表（未绑定拒绝）；OIDC 端点签发与 C 相同 Bearer；SSO 跳过本地 TOTP；管理员绑定 API 与界面；控制台 SSO。迁移 V10。默认关闭。
    Next: login lockout / rate-limit follow-up. Multi-IdP / SAML / SCIM deferred.
    下一片：登录锁定/限流。多 IdP / SAML / SCIM 延后。

Zero-code Z1 — 通用实体引擎（2026-10-08）:

Z1-1. [done] EntityCatalog (Spring-free classpath `entities/*.entity.yaml`), field kinds `userRef`/`orgRef` (VARCHAR like text; default maxLength 64), sample `demo-ticket` YAML + draft + Flyway `V12__demo_ticket.sql`. No generic JDBC/REST yet. Local commit only (no push).
    实体目录（无 Spring）、预留 userRef/orgRef、样例 demo-ticket 与 V12。尚未做通用 JDBC/REST。仅本地提交。

Z1-2. [done] Generic JDBC CRUD (`GenericEntityStore`) + REST `/api/v1/entities/{entityKey}/records` (`GenericEntityEndpoint`); `EntityCatalog` + store beans in `PlatformWiring`; PUT/DELETE/POST entities authenticated (declared permission in endpoint); `tenantScoped:true` → 400 this slice; demo-ticket covered by store tests. `service_note` bespoke path unchanged. Local commit only (no push).
    通用 JDBC CRUD + 按 entityKey 的 `/records` REST；接线与安全扩展；本片不支持 tenantScoped；demo-ticket 测例。service_note 专用路径未动。仅本地提交。

Z1-3. [done] Parallel generic read for `service_note`: store tests via `GenericEntityStore`; `ServiceNoteEntityEndpoint.list` reads through generic store (compat `/notes` JSON); writes stay on `JdbcServiceNoteStore`. Quickstart/docs prefer Bearer; Basic = local/script opt-in (off outside `local` later; impl may follow). Local commit only (no push).
    service_note 并行通用读；`/notes` 兼容垫片；写仍专用 JDBC；Basic 文档更安全默认。仅本地提交。
    Next was thin people/org (done as Thin-org-1 below). Optional later: switch form writes to generic + delete bespoke JDBC; implement Basic-off outside `local`.
    下一片原为薄人员/组织（见下方 Thin-org-1）。可选：写切通用并删专用 JDBC；实现非 local 关闭 Basic。

Thin people/org — 薄人员/组织（2026-10-08）:

Thin-org-1. [done] Flyway `V13__org_unit.sql` (`org_unit` tree + `org_membership`), `org.read` granted to platform-operator/reader, reserved role `platform.super-admin` (zero ordinary permissions, no subject_role), `OperatorPermission.ORG_READ`, `JdbcOrgDirectory` read model + H2 tests; local commit only (no push). No HTTP yet.
    V13 组织树与成员表；`org.read`；预留隔离超管角色；JDBC 只读目录与测例。尚无 HTTP。仅本地提交。

Thin-org-2. [done] Read-only `OrgApiEndpoint` `GET /api/v1/org/units|memberships?tenantId=` (+ optional `subjectId` on memberships); `org.read` on `GET /api/v1/org/**`; MockMvc 401/403/200/400 tests; local commit only (no push). No write APIs / console UI / SCIM; `platform.super-admin` still unused.
    只读组织 HTTP + `org.read` 安全；测例齐全。无写接口/控制台/SCIM；超管仍未分配。仅本地提交。
    Permission tiers (console vs business) remain the people split; reserved super-admin stays isolated. Thin-org baseline complete → Z2 next.
    人员仍按权限分层（控制台 vs 业务）；隔离超管未启用。薄组织基线齐 → 下一片 Z2。


Zero-code Z2 — 通用流程页（2026-10-08）:

Z2-1. [done] Page-block catalog (`page-declare/.../blocks/page-block-catalog.yaml`) + React stubs under `web/src/components/page-blocks/` (ListTable wired into declared list; DetailReadonly on detail; SubmitBar on submit; User/Org pickers disabled placeholders; Section/Tabs/FlowSorter/FormFields stubs); phrases + vitest helpers; local commit only (no push).
    页面积木目录 + React 桩；ListTable 已接声明列表；选人/选部门禁用占位。仅本地提交。
    Next was Z2-2 (landed below).
    下一片曾为 Z2-2（见下）。

Z2-2. [done] demo-ticket flow/form on generic `/records` + `entity.record.upsert` domain action (form `entityKey` fail-closed; fields validated by entity at runtime); local commit only (no push).
    demo-ticket 流程/表单走通用 `/records`；通用 `entity.record.upsert`（表单 `entityKey` 失败关闭；字段运行时按实体校验）。仅本地提交。

Z2-3. [done] Detail prefers `GET /records/{id}` (list+find fallback for `/notes`); live thin OrgPicker/UserPicker (field names `orgUnit`/`assignee`; text fallback without tenantId); forms JSON `entityKey` on detail+index; catalog User/Org → `runtime-thin`; local commit only (no push).
    详情优先单条 GET；选人/选部门接薄组织只读（无租户则文本）；表单 API 暴露 entityKey。仅本地提交。
    **Z2 baseline landed** → next is Z3 (declaration store). Do not start Z3 in this commit.
    **Z2 基线齐** → 下一片 Z3 声明库。本提交不开 Z3。

Zero-code Z3 — 声明库（租户草稿）（2026-10-08）:

Z3-1. [done] Flyway `V14__declaration_draft.sql` (`declaration_revision` tenant-scoped revisions + `declaration.read`/`declaration.write`), `JdbcDeclarationStore` (saveDraft / latest / listLatest / listRevisions), `OperatorPermission.DECLARATION_*`, H2 tests; local commit only (no push). No HTTP / catalog overlay / promote yet.
    V14 租户声明修订表与读写权限；JDBC 草稿存取与测例。尚无 HTTP / 目录覆盖 / 晋升。仅本地提交。

Z3-2. [done] Declaration draft HTTP (`/api/v1/declarations`) + `EffectiveDeclarationService` (DB DRAFT over classpath) + `/effective` summary; YAML validated on PUT; local commit only (no push). Runtime catalogs still classpath (Z3-3 may swap).
    声明草稿 HTTP、生效覆盖服务与 `/effective`；PUT 前校验 YAML。运行时目录仍 classpath（Z3-3）。仅本地提交。

Z3-3. [done] Runtime overlay when `X-Tenant-Id` present: entity CRUD + form/flow detail + `entity.record.upsert` via `EffectiveDeclarationService`; safe entity rule (classpath must exist; tableName+PK match else 409); classpath unchanged without tenant header. **Z3 baseline landed** → Z4 next. Local commit only (no push). No Z4 builder / Z5 promote.
    带租户头时运行时覆盖（实体/表单/流程）；实体安全规则；无头仍 classpath。**Z3 基线齐** → 下一片 Z4。仅本地提交。不开 Z4/Z5。


Zero-code Z4 — 结构化页面构建器（2026-10-08）:

Z4-1. [done] Console `/declarations`: tenant + kind picker, list latest drafts (+ classpath sample hints), YAML textarea editor with review→confirm save via PUT drafts API, effective overlay summary; tenant cookie `subjex_declaration_tenant`; declaration.read/write gates; zh/en; vitest helpers; local commit only (no push). Visual ListTable/FormFields composer is Z4-2.
    控制台声明草稿 YAML 编辑器（租户/种类、列表、核对保存、生效摘要）；cookie 记租户；权限门闩与文案单测。可视化积木编排属 Z4-2。仅本地提交。
    Next: Z4-2 visual block composer on top of this shell. No promote / entity wizard yet.
    下一片：Z4-2 在本壳上做可视化积木编排。尚不晋升 / 实体向导。

Z4-2. [done] Structured flow block composer on `/declarations` (list/detail/submit optional `blocks` in page-declare + API expose); console checklist/reorder + Apply to YAML (line-based merge, no yaml lib); zh/en; vitest + PageRenderer tests; local commit only (no push). Free canvas / entity-form wizards / promote deferred to Z4-3+.
    流程结构化积木编排（声明可选 blocks、API 暴露、控制台勾选调序并写回 YAML）；文案与单测。自由画布 / 实体表单向导 / 晋升留 Z4-3+。仅本地提交。
    Next: Z4-3 entity/form visual wizards (still not free canvas). No Z5 promote.
    下一片：Z4-3 实体/表单可视化向导。尚不晋升。

Z4-3. [done] Thin entity + form structured wizards on `/declarations` (same shell as YAML): field tables, Apply to YAML via line-based helpers (no yaml lib), preserve form effects or default audit.write; zh/en; vitest round-trips; local commit only (no push). **Z4 baseline landed** → Z5 promote next. No free canvas / promote this slice.
    实体/表单薄结构化向导（字段表、应用到 YAML、表单 effects 保留或默认审计桩）；文案与往返单测。**Z4 基线齐** → 下一片 Z5 晋升。本片不做自由画布/晋升。仅本地提交。
    Next: Z5 internal-git promote (gated). Thin algo/AI catalog stubs may follow after Z4.
    下一片：Z5 内部 git 晋升（门闩）。算法/AI 薄目录可在 Z4 后跟进。

Zero-code Z5 — 声明晋升进内部 git（2026-10-08）:

Z5-1. [done] Internal git promote core: `platform.declaration.git.dir`, Flyway V15 `declaration_promote` + `declaration.promote` (platform-operator), `InternalDeclarationGit` (system git CLI), `DeclarationPromoteService`, `PROMOTED` state; local commit only (no push / no HTTP). No GitHub / schema auto-DDL.
    内部 git 晋升核心：配置目录、V15 审计表与权限、系统 git CLI、晋升服务、PROMOTED；仅本地提交（无 push / 无 HTTP）。不依赖 GitHub / 不自动改表。
    Next: Z5-2 promote HTTP + audit. Console button is Z5-3.
    下一片：Z5-2 晋升 HTTP 与审计。控制台按钮属 Z5-3。

Z5-2. [done] POST `/api/v1/declarations/{kind}/{key}/promote` (`declaration.promote` + tenant grant + operator audit), GET `.../promotes` history (`declaration.read`); 404 missing / 409 already PROMOTED; local commit only (no push / no console). No GitHub / schema auto-DDL.
    晋升 HTTP：POST 晋升（权限+租户授权+操作员审计）、GET 晋升历史；缺修订 404 / 已晋升 409；仅本地提交（无 push / 无控制台按钮）。不依赖 GitHub / 不自动改表。
    Next: Z5-3 console promote button on `/declarations`.
    下一片：Z5-3 声明控制台晋升按钮。

Z5-3. [done] Console `/declarations` promote: review→confirm → POST promote (optional revision), show gitCommitSha + PROMOTED, 409 already-promoted, promote history panel; canPromote from declaration.promote; zh/en; vitest helpers; local commit only (no push / no GitHub). **Z5 baseline landed** — dual-track promote loop closed. No schema auto-DDL / algo stubs.
    控制台晋升：审阅→确认→POST（可选修订）、展示 git SHA 与 PROMOTED、409 已晋升、晋升历史；canPromote；文案与辅助单测；仅本地提交。**Z5 基线齐** — 双轨晋升闭环。不开自动改表/算法桩。
    Next was thin algo/AI (landed in capability commit) then Z6-1 below.
    下一片曾为算法/AI 薄层（已在 capability 提交落地），再 Z6-1（见下）。

Thin algo/AI — 算法与 AI 薄层（2026-10-08）:

Cap-1. [done] Capability catalogs + stubs (`algo.hashFingerprint`, `ai.summarizePreview`) + optional domain actions + `GET /api/v1/capabilities`; local commit only (no push).
    能力目录与桩 + 可选领域动作挂接 + 列表 API。仅本地提交。

Zero-code Z6 — 字段种类（2026-10-08）:

Z6-1. [done] Entity field kinds `boolean` / `enum` / `date` / `entityRef`: EntityFieldKind + EntityRenderer (`enumValues`, `refEntityKey`), GenericEntityStore coerce/validate, migration/stub SQL+Java types; covered by inline YAML unit tests (no demo-ticket Flyway this slice). Form FieldKind and console widgets unchanged. Local commit only (no push).
    实体字段种类 boolean/enum/date/entityRef：声明解析、校验与 JDBC coerce、迁移/桩列类型；单测用内联 YAML（本片不加 demo-ticket Flyway）。表单 FieldKind 与控制台控件未扩。仅本地提交。

Z6-2. [done] Generic list `sort`/`order`/`filterField`/`filterValue` (declared fields only, parameterized SQL); entity wizard kinds boolean/enum/date/entityRef (+ enumValues / refEntityKey YAML); form FieldKind boolean/date/enum + form-wizard + submission coerce. Deferred: page runtime widgets (publication-form / pages new still text|integer inputs). Local commit only (no push).
    通用列表筛选排序；实体向导扩种类；表单 FieldKind + 向导 + 提交 coerce。延期：页面运行时控件（仍 text/integer）。仅本地提交。

Z6-3. [done] Page form widgets: shared `web/src/lib/form-field-input.ts` (kind labels, control map, coerce/payload) wired into publication-form + declared submit-form; checkbox / date / select / number / text; forms API `FieldDocument.enumValues` + loaders pass-through; vitest; zh/en fieldKind* phrases. Thin list filter UI deferred (list API already supports query params). Local commit only (no push). **Z6 baseline landed.**
    页面表单控件：共用辅助 + 两表单接线；API 暴露 enumValues；单测与文案。薄列表筛选 UI 延期。**Z6 基线齐。**
    Next options: Mac push of local commits; roadmap gaps (list filter UI, full algo/AI, schema migration queue UX, etc.).
    下一波可选：本机推送本地提交；或路线图余项（列表筛选 UI、完整算法/AI、迁移队列 UX 等）。


List filter UI — 声明列表筛选/排序条（2026-10-08）:

LF-1. [done] Thin filter/sort bar on declared list pages when `apiPath` matches `/entities/.../records`: URL searchParams (`sort`/`order`/`filterField`/`filterValue`), GET form Apply + Clear; legacy `/notes` paths hide the bar; helper + vitest; zh/en. Local commit only (no push). Does not change service-note list flow.
    声明列表薄筛选/排序条（仅通用 records）；URL 查询参数；遗留 notes 隐藏；辅助与单测；中英文案。仅本地提交。不改 service-note 列表流。
    Next options: Mac push (~local commits); migration-queue UX; bespoke JDBC cutover.
    下一波可选：本机推送；迁移队列 UX；旧 JDBC 切通用。

Service-note cutover + migration queue — service-note 切流与迁移队列（2026-10-08）:

SN-1. [done] service-note form writes via `GenericEntityStore` (`entity.record.upsert`); bespoke `JdbcServiceNoteStore` removed; `/notes` list shim kept. Local commit only (no push).
    service-note 写入切到通用实体存储；专用 Store 删除；列表 shim 保留。仅本地提交。

MQ-1. [done] `declaration_migration` queue (Flyway V16) + `declaration.migrate` + store/HTTP enqueue·list·review; no apply. Local commit only (no push).
    迁移队列表与权限、入队/列表/审阅 HTTP；本片不执行 DDL。仅本地提交。

MQ-2. [done] Apply REVIEWED entity DDL (fail-closed) + entity promote blocked until migrations for that revision are APPLIED/CANCELLED (or none). Local commit only (no push).
    执行已审阅 DDL + 实体晋升与迁移绑定。仅本地提交。

MQ-3. [done] Console `/declarations` migration queue (entity only): list, enqueue (revision+sqlText) review→confirm, Review/Apply review→confirm, status/errorMessage; form/flow one-line note; `canMigrate` from `declaration.migrate`; zh/en; vitest helpers; platform proxy POST already covers migrations paths. Local commit only (no push). **Migration-queue baseline landed.**
    声明页迁移队列控制台（仅实体）；入队/审阅/执行两步确认；文案与辅助单测；代理已支持 POST。**迁移队列基线齐。**
    Next options: Mac push of local commits; further roadmap gaps.
    下一波可选：本机推送本地提交；或其它路线图余项。

