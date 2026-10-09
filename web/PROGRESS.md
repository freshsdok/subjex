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
    Next: Lockout-1 done (see below). Multi-IdP / SAML / SCIM deferred; optional IP/global rate-limit remains.
    下一片：登录锁定/限流。多 IdP / SAML / SCIM 延后。

Zero-code Z1 — 通用实体引擎（2026-10-08）:

Z1-1. [done] EntityCatalog (Spring-free classpath `entities/*.entity.yaml`), field kinds `userRef`/`orgRef` (VARCHAR like text; default maxLength 64), sample `demo-ticket` YAML + draft + Flyway `V12__demo_ticket.sql`. No generic JDBC/REST yet. Local commit only (no push).
    实体目录（无 Spring）、预留 userRef/orgRef、样例 demo-ticket 与 V12。尚未做通用 JDBC/REST。仅本地提交。

Z1-2. [done] Generic JDBC CRUD (`GenericEntityStore`) + REST `/api/v1/entities/{entityKey}/records` (`GenericEntityEndpoint`); `EntityCatalog` + store beans in `PlatformWiring`; PUT/DELETE/POST entities authenticated (declared permission in endpoint); `tenantScoped:true` → 400 this slice; demo-ticket covered by store tests. `service_note` bespoke path unchanged. Local commit only (no push).
    通用 JDBC CRUD + 按 entityKey 的 `/records` REST；接线与安全扩展；本片不支持 tenantScoped；demo-ticket 测例。service_note 专用路径未动。仅本地提交。

Z1-3. [done] Parallel generic read for `service_note`: store tests via `GenericEntityStore`; `ServiceNoteEntityEndpoint.list` reads through generic store (compat `/notes` JSON); writes stay on `JdbcServiceNoteStore`. Quickstart/docs prefer Bearer; Basic = local/script opt-in (off outside `local`; Basic-1 later landed). Local commit only (no push).
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

Z2-3. [done] Detail prefers `GET /records/{id}` (list+find fallback for `/notes`); live thin Org/User pickers then (**O8**) Subject/Organization pickers; forms JSON `entityKey`; local commit only (no push).
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

Hot-reload promoted metadata — 已晋升元数据热加载（2026-10-09）:

HR-1. [done] Explicit load order DRAFT > PROMOTED > classpath: `JdbcDeclarationStore.latestDraft` / `latestPromoted`; `EffectiveDeclarationService.resolutionSource`; `hasEntityDraft` = open DRAFT only; `/effective` adds `source`; tests cover promote hot-reload + draft beats promoted + no-tenant classpath. Local commit only (no push). No tenantScoped / Basic-off / org writes / lockout / page-blocks.
    明确加载顺序（草稿 > 已晋升 > classpath）；解析来源；晋升后无新草稿则 hasEntityDraft=false；`/effective` 增加 source；单测覆盖热加载。仅本地提交。不开 tenantScoped / Basic 关闭 / 组织写 / 锁定 / 积木。
    Next options: tenantScoped generic path; Basic off outside local; FormFields/sorter; login lockout; org write+console.
    下一波可选：通用 tenantScoped；非 local 关 Basic；FormFields/分拣器；登录锁定；组织写+控制台。

Tenant-scoped generic entities — 通用实体租户隔离（2026-10-09）:

TS-1. [done] Generic `/records` + `entity.record.upsert` support `tenantScoped: true`: access gate via `DeclarationAccess.requireTenantWhenScoped`; row isolation on physical `tenant_id` (stamp/filter; body `tenantId`/`tenant_id` stripped); Flyway tables must include the column (generator not auto-adding yet). Local commit only (no push). No Basic-off / page-blocks / lockout / org writes.
    通用 records 与 upsert 支持 tenantScoped：租户门禁 + 物理 `tenant_id` 隔离；表须含该列（生成器尚未自动加）。仅本地提交。不开 Basic 关闭 / 积木 / 锁定 / 组织写。
    Next was Basic off outside local (see Basic-1 below).
    下一片为非 local 关 Basic（见下方 Basic-1）。

HTTP Basic safer default — 非 local 默认关 Basic（2026-10-09）:

Basic-1. [done] `platform.auth.http-basic-enabled` default **false** (`PLATFORM_AUTH_HTTP_BASIC_ENABLED`); **true** under `application-local.yml`; `PlatformSecurityConfiguration` enables/disables `.httpBasic`; OpenAPI Basic scheme kept with opt-in note; tests enable Basic via `src/test/resources/application.properties`; `HttpBasicDisabledSecurityTest` asserts Basic 401 + Bearer OK when off. Local commit only (no push). No page-blocks / lockout / org writes.
    配置默认关 Basic，local 打开；安全链按开关接线；OpenAPI 保留方案并注明可选；测试资源打开 Basic；关时 Basic→401、Bearer 仍通。仅本地提交。不开积木/锁定/组织写。
    Next options: FormFields/sorter; login lockout; org write+console; migration generator auto-`tenant_id`.
    下一波可选：FormFields/分拣器；登录锁定；组织写+控制台；迁移生成器自动加 tenant_id。

Page blocks FormFields + FlowSorter — 页面积木 FormFields 与流程分拣器（2026-10-09）:

Blocks-1. [done] FormFields real renderer (`fieldPickerRole` + shared controls; DeclaredSubmitForm uses `<FormFields>`); FlowSorter chip links + `buildDefaultFlowSorterOptions` (fail-closed); list page shows sorter only when `list.blocks` includes `FlowSorter`; catalog FormFields/FlowSorter → `runtime`; Section/Tabs still stubs. Vitest fieldPickerRole + flow-sorter. Local commit only (no push). No login lockout / org writes / push.
    FormFields 真渲染并接入声明式提交；FlowSorter 默认分支链接（显式 blocks）；目录升为 runtime；Section/Tabs 仍桩。仅本地提交。
    Next was login lockout (see Lockout-1 below).
    下一片为登录锁定（见下方 Lockout-1）。

Login lockout — 登录失败锁定（2026-10-09）:

Lockout-1. [done] Flyway `V17__operator_login_lockout`; `JdbcOperatorLoginLockout` (assert/record/clear); wired on `POST /auth/login` (+ MFA verify failures count); config `platform.auth.lockout-max-failures=5` / `lockout-duration=PT15M`; active lock → **429** `{"reason":"login-lockout"}`; success clears; audit REFUSED with `:lockout` when operator known. Local commit only (no push). No org writes / Section-Tabs / SCIM.
    V17 表 + JDBC 锁定存储；口令登录接线（MFA 校验失败也计数）；默认 5 次 / 15 分钟；锁定中 429；成功清除。仅本地提交。不开组织写 / Section-Tabs / SCIM。
    Next was Section/Tabs (see Blocks-2 below).
    下一片为 Section/Tabs（见下方 Blocks-2）。

Page blocks Section + Tabs — 页面积木 Section 与 Tabs（2026-10-09）:

Blocks-2. [done] Section card wrapper (title/hint/className); Tabs client tablist (`tabs` array, controlled/uncontrolled, empty muted note); detail wires Fields/Raw when `detail.blocks` includes Tabs, Section wrap when includes Section; list wraps ListTable when `list.blocks` includes Section; catalog → `runtime`; vitest `resolveActiveTabId`. Local commit only (no push). No org writes / super-admin / push.
    Section 卡片包裹；Tabs 客户端标签栏；详情/列表按 blocks 显式接线；目录升为 runtime；单测默认选中。仅本地提交。不开组织写 / 超管 / 推送。
    Next options: org write+console; migration generator auto-`tenant_id`.
    下一波可选：组织写+控制台；迁移生成器自动加 tenant_id。

Org write APIs — 组织写接口（2026-10-09）:

Org-W1. [done] Flyway `V18__org_write.sql` (`org.write` → platform-operator only), `OperatorPermission.ORG_WRITE`, `JdbcOrgDirectory` upsert unit/membership + remove + setUnitState, `OrgApiEndpoint` PUT units/memberships + DELETE memberships (tenant grant + audit `org.unit.upsert` / `org.membership.upsert|remove`), security PUT/DELETE need `org.write`; H2 + MockMvc tests. Local commit only (no push). No console UI / SCIM / super-admin break-glass.
    V18 `org.write`；JDBC 写路径；PUT/DELETE HTTP + 租户授权与审计；安全与测例。仅本地提交。不开控制台 / SCIM / 超管破窗。
    Next was Org-W2 (see below).
    下一片为 Org-W2（见下）。

Org console thin page — 组织控制台薄页（2026-10-09）:

Org-W2. [done] Console `/org`: tenant cookie `subjex_org_tenant` (select when `admin.read`, else free-text); units + memberships tables via platform proxy; `org.read` gate / `org.write` forms with review→confirm (PUT units/memberships, DELETE memberships, disable/enable); nav `navOrg` + zh/en phrases; vitest cookie/id helpers. Local commit only (no push). No super-admin break-glass / SCIM / tree designer.
    控制台 `/org`：租户 cookie；单元与成员表；读写门禁与两步确认写操作；导航与中英文；辅助单测。仅本地提交。不开超管破窗 / SCIM / 树设计器。
    Next was SA-1 (see below).
    下一片为 SA-1（见下）。

Super-admin break-glass — 超管破窗（2026-10-09）:

SA-1. [done] `PlatformRoles.SUPER_ADMIN`; directory expands full `platform_permission` catalog when subject holds `platform.super-admin` (role_permission stays 0); ordinary `JdbcOperatorAdmin.create` / `OperatorBootstrap` refuse the role; dedicated `SuperAdminBootstrap` + `platform.operator.super-admin-bootstrap` (off by default, exits after upsert, fixed ids + `*` grant); docs + H2 tests. Local commit only (no push). No console badge / MFA special-case / SCIM / auto-seed on local / V19 role_permission.
    `PlatformRoles.SUPER_ADMIN`；名录破窗展开完整权限目录；普通创建/开通拒绝该角色；专用超管开通开关默认关；文档与 H2 测例。仅本地提交。不开控制台徽章 / MFA 特例 / SCIM / local 自动种子 / V19 授权行。
    Next was Mig-TID (see below).
    下一片为 Mig-TID（见下）。

Migration generator tenant_id — 迁移生成器自动 tenant_id（2026-10-09）:

Mig-TID. [done] `EntityMigrationGenerator` auto-appends `tenant_id VARCHAR(64) NOT NULL` when `tenantScoped: true` and no field already maps to that column; no duplicate; PK stays single-column; docs + generator tests. Local commit only (no push). No GenericEntityStore / composite PK rewrite / ALTER / sample YAML flips.
    隔离且字段未含 `tenant_id` 时自动加列；不重复；主键仍单列；文档与测例。仅本地提交。不改通用存储 / 复合主键 / ALTER / 样例 YAML。
    Next was Cap-2 (see below).
    下一片为 Cap-2（见下）。

Capability stubs Cap-2 — 能力桩加深（2026-10-09）:

Cap-2. [done] Stubs `algo.normalizeWhitespace` (trim + collapse) + `ai.suggestTitlePreview` (first line / ~60 chars, no store write); domain actions `capability.algo.normalizeWhitespace` / `capability.ai.suggestTitlePreview`; catalog + runner + FormDomainActionRunner arms; tests. Local commit only (no push). No POST invoke / flow YAML / console UI / real gateway.
    桩：空白归一化 + 标题建议预览；领域动作挂接；目录/执行器/表单动作与测例。仅本地提交。不开 POST invoke / 流程 YAML / 控制台 / 真网关。
    Next was Cap-3 (see below).
    下一片为 Cap-3（见下）。

Capability Cap-3 — 流程挂接 + 试跑 API（2026-10-09）:

Cap-3. [done] Flow `submit.capabilityId` (optional `algo.`/`ai.` + camelCase, fail-closed format); Pages API exposes it; `POST /api/v1/capabilities/{id}/run` with `{inputText}` → `{capabilityId,result}` under `page.read`; catalog fail-closed; stubs only. Local commit only (no push). Cap-4 console UI / real gateway out of scope.
    流程可选 `submit.capabilityId`；Pages 暴露；试跑 POST（`page.read`）；仍为桩。仅本地提交。Cap-4 控制台 / 真网关后置。
    Next was Cap-4 (see below).
    下一片为 Cap-4（见下）。

Capability Cap-4 — 能力控制台薄页（2026-10-09）:

Cap-4. [done] Console `/capabilities`: catalog table + try-run panel (`GET/POST` via platform proxy); `page.read` gate; stub note (no store write / no model-gateway); nav `navCapabilities` + zh/en; vitest title/path helpers. Local commit only (no push). Real gateway / catalog editing out of scope.
    控制台 `/capabilities`：目录表 + 试跑面板；`page.read` 门禁；桩说明；导航与中英文；辅助单测。仅本地提交。真网关 / 目录编辑后置。
    Next options: Mac push of Cap-2..4; real model-gateway later.
    下一波可选：本机推送 Cap-2..4；真 model-gateway 后置。

Explainable AccessDecision AX-1 — 可解释上下文权限（2026-10-09）:

AX-1. [done] `AccessDecision` / `AccessResource` / `AccessAction` + `AccessChecker`; declaration API paths (forms/pages/entities/submit) return structured 403 with subject/tenant/resource/action/matchedPermission/denyReason (`orgScope` null for AX-2); backward-compatible `permission`; optional audit `access.deny`; unit + MockMvc tests; docs. Local commit only (no push). No org descendants / Cedar / collapsing Spring matchers.
    可解释判定核心接声明路径；403 带决策字段；组织范围/外置策略后置。仅本地提交。
    Next: AX-2 orgScope self+descendants.
    下一片：AX-2 本部门及下级。

Org scope AX-2 — 组织范围本部门及下级（2026-10-09）:

AX-2. [done] `OrgScope` (SELF_AND_DESCENDANTS: roots + unitIds); `JdbcOrgDirectory.resolveSelfAndDescendants` / descendant BFS; `AccessDecision.orgScope` structured; `AccessChecker` deny `org_out_of_scope`; Org GET filter + write gate on `/api/v1/org/**`; FormProblemDocument + `access.deny` audit; tests. Local commit only (no push). No Zanzibar / other modes / Cedar-Casbin (AX-3) / generic entity org_unit filter.
    本部门及下级范围接可解释判定与组织 API 竖切；无成员则不过滤。仅本地提交。不上 Zanzibar / 其它模式 / Cedar·Casbin（AX-3）/ 通用实体过滤。
    Next options: AX-3 policy port (Cedar/Casbin subset adapter); Mac push of AX-1+AX-2.
    下一波可选：AX-3 策略端口；本机推送 AX-1+AX-2。

Policy engine port AX-3 — 策略引擎端口（2026-10-09）:

AX-3. [done] `PolicyEngine` + `PolicyPrincipal` / `PolicyResource` / `PolicyContext`; `SqlRbacPolicyEngine` delegates to `AccessChecker` (no Cedar/Casbin Maven deps); Org membership write scope is a real caller; docs map Cedar/Casbin subset; unit tests mirror AccessChecker deny reasons. Local commit only (no push). No jars / no custom DSL / no Spring matcher rewrite.
    策略端口对齐 Cedar/Casbin 子集形状；SQL 首适配；组织成员写已接线。仅本地提交。不引依赖 / 无自研 DSL / 不改 Spring matcher。


Repair-ticket runtime RT-1 — 报修单运行时闸门（2026-10-09）:

RT-1. [done] No-classpath PROMOTED entities on JDBC when migration APPLIED; FormSubmission + forms/pages index use effective tenant overlay; `/pages/*` + proxy send `subjex_declaration_tenant` as `X-Tenant-Id`. Local commit only (no push). Wizard / auto SQL / auto pages out of scope.
    无 classpath 已晋升实体在迁移 APPLIED 后可运行；提交与目录走租户生效声明；页面与代理带声明租户头。仅本地提交。向导/自动 SQL/自动出页后置。


Repair-ticket console wizard RT-2 — 报修单控制台唯一入口（2026-10-09）:

RT-2. [done] `/declarations` **新建业务表** wizard creates entity+form+flow drafts for one key (repair-ticket defaults: title/location/urgency enum/assignee userRef; `page.read`; tenantScoped; audit.write only; flow blocks ListTable/FormFields/DetailReadonly/SubmitBar). Classpath samples badge「样例」; empty-state points to wizard. Form `FieldKind.USER_REF` + wizard kinds. Vitest business-table-wizard. Local commit only (no push). Auto migration enqueue = RT-4; promote auto-page / permission catalog = later RT.
    控制台「新建业务表」一次写出三份草稿；样例标回归；表单支持 userRef。仅本地提交。自动迁表入队见 RT-4；晋升出页见 RT-5；权限目录后置。


Repair-ticket auto-enqueue RT-4 — 报修单草稿加列自动入队（2026-10-09）:

RT-4. [done] Entity draft PUT auto-enqueues PENDING `CREATE TABLE` (brand-new) or `ALTER TABLE … ADD COLUMN` (vs latest PROMOTED, else classpath sample, else empty). Idempotent per revision+SQL; fail-closed on kind/SQL type / tableName / PK change before draft insert; no auto-APPLY / DROP / RENAME. `EntityMigrationGenerator` queue helpers; `DeclarationMigrationAutoEnqueueService`; H2 + generator tests; docs. Local commit only (no push). Promote auto-page = RT-5 [done]; permission catalog = later RT.
    实体草稿保存自动入队 PENDING 建表/加列；幂等；危险变更落库前失败关闭；不自动执行。生成器队列助手 + 服务 + 测例 + 文档。仅本地提交。晋升出页见 RT-5；权限目录后置。


Repair-ticket runtime pages RT-5 — 晋升后运行时页面（2026-10-09）:

RT-5. [done] After FLOW promote, pages catalog + list/new/detail paths resolve from effective flow (DRAFT > PROMOTED > classpath); `DeclarationRuntimePages.ensureAfterFlowPromote` fail-closes on path/four-block contract for tenantScoped; no separate page YAML materialization. H2 + MockMvc tests; docs. Local commit only (no push). RT-3/6/7 [done] below.
    流程晋升后页面目录与三路径从生效 flow 出现；晋升校验固定路径与（租户隔离）四积木；不另写 page YAML。测例与文档。仅本地提交。见下方 RT-3/6/7。


Repair-ticket pick-only + audit + wizard-first RT-3/6/7 — 权限只选、版本审计、向导优先（2026-10-09）:

RT-3/6/7. [done] Draft save/promote: permission must be in `OperatorPermission` catalog (400 unknown); form effects whitelist `audit.write`|`task.enqueue` only (`extension.invoke` classpath-only). Form `audit.write` records tenant/actor/entityKey/declarationVersion/resolutionSource (Flyway V19). Console: permission `<select>` from catalog on business-table + entity/form wizards; demote single-kind template + entity/form wizards behind advanced disclosure; path hint wizard → migrate → promote → pages. Tests + docs. Local commit only (no push).
    草稿保存/晋升：权限只选目录；表单副作用白名单；审计带租户与声明版本关联（V19）。控制台权限下拉；收窄单种/实体表单向导为高级；路径提示。测例与文档。仅本地提交。


RT wave on github/main — 报修单控制台闭环已推送（2026-10-09）:

RT-push. [done] Squashed RT-1…RT-7 onto `github/main` as `a680231` (parent `32eca66` AX squash; tree `58eba251` = local `d90d2f5`). Fast-forward only, never force. Local RT commits remain as history.
    已 squash 推到 GitHub main；本地 RT 提交链保留作历史。

Outbox bus adapter — 出箱标准总线出口适配器（2026-10-09）:

OB-1. [done] `DeliveryPort` / `DeliveryAttempt` / `DeliveryTransport` in contract; `OutboxSocketPublisher` implements port; `JdbcTaskMessagePort` + relay use port; `platform.delivery.transport=socket` default; `DeliveryCircuitBreaker` wraps selected transport only; socket demoted to demo in docs. Local commit only.
    传输端口化；默认 socket；文档标明演示限制。仅本地提交。

OB-2. [done] Module `subjex-outbox-kafka`: KafkaDeliveryPublisher + auto-config when transport=kafka; fail-fast host/topic; TLS/SASL gate; MockProducer tests; DeliveryCircuitBreaker in contract. platform-app does not depend on the module. Local commit only.
    Kafka 可选模块与安全门、单测；platform-app 默认不依赖。仅本地提交。

## Production gaps P1–P7 (2026-10-09 Asia/Shanghai) / 生产缺口

Local commits only (not pushed). Checklist: `docs/release/v0.1.0-alpha.1-checklist.md`. P6 drill **PASS** (`2026-10-09-backup-restore.md`); **no `DRILL-PENDING.md`**. Tag still blocked by **remote CI verify + intentional cut** (not by missing drill).
仅本地提交。P6 演练已 PASS；无 DRILL-PENDING。tag 仍待远端 CI 与主动发版（非缺演练）。

O1. [done] Model freeze docs ADR+ontology pack; review approve; pushed github/main `de1846ae`.
O2. [done] Flyway V22 organization/membership/organization_relation/tenant_organization + JdbcOrganizationStore; MODEL/MEM/ORG/TENANT gates; AUTH/MIG/DECL stubs; legacy org_unit kept; local commit only (no push).

O3. [done] V23 org_unit_organization_map; OrganizationOntologyBackfill (per-tenant 1:1, no cross-tenant merge); JdbcOrgDirectory dual-read prefer-new when fully backfilled + write-through; MIG-01/02/03; docs o3-dual-read; legacy tables kept. Local commit only (no push).

O4. [done] OrgScope modes NONE/UNRESTRICTED/SELF/SELF_AND_DESCENDANTS/EXPLICIT; AccessChecker fail-closed on missing scope; SqlRbacPolicyEngine tenant_mismatch; JdbcOrgDirectory resolveSelf + ontology Membership/Relation (TenantOrganization filter); AUTH-01..05; legacy dual-read kept. Local commit only (no push).
    组织范围五模式；缺 scope fail-closed；租户不一致拒绝；从 Membership+OrganizationRelation 推导；AUTH 门禁；旧表双读保留。仅本地提交。

O5. [done] `/api/v1/organizations` + memberships; console `/org`; legacy `/api/v1/org` deprecated; OrganizationApiSecurityTest; local commit `f447c08`.
    Organization API 与控制台；旧 API 弃用。本地提交。

O6. [done] Zero-code: `subjectRef`/`organizationRef` (dual-accept userRef/orgRef); SubjectPicker + OrganizationPicker; wizards/samples write canonical; DECL-01/02 green; no O7 DROP. Local commit only (no push).
    零代码引用与选人积木收敛；门禁 DECL 绿；不删旧表。仅本地提交。

O7. [done] E2E-01 cutover; Flyway V24 DROP `org_unit`/`org_membership`; remap kept; deprecated `/api/v1/org/**` thin adapter via `JdbcOrgDirectory`; see `docs/ontology/o7-e2e-pass.md`. (PROGRESS catch-up; work landed before O8.)
    E2E 门禁与旧表 DROP；映射保留；旧 API 薄适配。进度补记。

O8-Inventory. [done] 2026-10-09 CST; HEAD `226e808`; wrote `docs/ontology/o8-inventory.md`. Gate baseline: Production ontology count=2, Legacy domain consumers=8, Legacy SQL consumers=2, Policy legacy dependency=7, Zero-code legacy refs=13. Next slice = **O8-1** (Policy直连新模型). Local commit only (no push).
    O8 盘点完成；五门禁基线见上；下一片 O8-1。仅本地提交。


O8-1. [done] 2026-10-09 CST; `OrganizationScope` + `OrganizationScopeResolver` (Membership+CONTAINS, no map); Organization API + AccessChecker/PolicyContext on OrganizationScope; OrgScope deprecated for legacy `/org`; tests 75 green (focused). Next = **O8-2** (field/JSON renames). Local commit only (no push). See `docs/ontology/o8-1-pass.md`.
    OrganizationScope 与本体范围解析；新 API/策略不经 map；旧 OrgScope 仅旧 API。下一片 O8-2。仅本地提交。


O8-2. [done] 2026-10-09 CST; OrganizationScope JSON `rootOrganizationIds`/`organizationIds`; OrgScope field names aligned; PolicyResource organizationId-only (ATTR_ORG_UNIT_ID deprecated unused); tests 87 green. Next = **O8-3** (Legacy isolation). Local commit only (no push). See `docs/ontology/o8-2-pass.md`.
    组织范围字段命名收口；策略 JSON 用 organization*；下一片 O8-3。仅本地提交。


O8-3. [done] 2026-10-09 CST; moved OrgUnit/OrgMembership/JdbcOrgDirectory/OrgApiEndpoint/OrgScope → `org.legacy`; Backfill decoupled; LegacyPackageBoundaryTest; next = **O8-5** (frontend/zero-code per inventory order). Local commit only. See `docs/ontology/o8-3-pass.md`.
    旧模型隔离到 org.legacy；新包禁止依赖；下一片 O8-5。仅本地提交。

O8-5. [done] 2026-10-09 CST; subjectRef/organizationRef only; deleted UserPicker/OrgPicker; demo field organization + V25 rename; phrases 组织; zero-code legacy refs 13→0; next = **O8-4** (map/backfill). Local commit only. See `docs/ontology/o8-5-pass.md`.
    零代码别名清零；控制台组织文案；下一片 O8-4。仅本地提交。

O8-4. [done] 2026-10-09 CST; Organization API writes ontology-only (no backfill/map); JdbcOrgDirectory ontology-first reads; Backfill migration/legacy-only; formal map SQL → 0; next = **O8-6**. Local commit only. See `docs/ontology/o8-4-pass.md`.
    正式运行时退出 map/回填；下一片 O8-6。仅本地提交。

O8-6. [done] 2026-10-09 CST; ArchUnit + O8 metric gates; E2E Relationship≠Authorization + scope without map; ATTR_ORG_UNIT_ID removed; **O8 FULL PASS**. See `docs/ontology/o8-full-pass.md`.
    O8 架构门禁与 E2E；五指标全绿；FULL PASS。

O8. [DONE FULL PASS] 2026-10-09 CST; metrics ontology=1; legacy domain/SQL/policy/zero-code=0; V24 DROP; map no formal consumers; subjectRef/organizationRef only.

O8-push. [done] Squashed O8 inventory…O8-6 onto `github/main` as `1e431b2` (parent `ae44c78` O7; tree `241dcbd` = local `8af9dd9`). Fast-forward only, never force. Local O8 commits remain as history.
    已 squash 推到 GitHub main；本地 O8 提交链保留作历史。

## Pre-alpha track (before v0.1.0-alpha.1) / Alpha 前轨道（2026-10-09）

Locked order (owner): **1** AuthZ Cedar via PolicyEngine → **2** controlled migration UX → **3** real AI + mandatory confirm → **4** horizontal scale MVP (shared breaker/rate-limit) → **5** independent config center MVP → then CI fix + docs + alpha tag.
已锁定顺序：1 AuthZ 接 Cedar → 2 受控迁移体验 → 3 真 AI+强制确认写 → 4 水平扩展 MVP → 5 独立配置中心 MVP → 再修 CI/文档/打 alpha。
**Items 1–5 + CI-fix + docs收口 DONE.** Capacity squash `9446a74` was red (gateway actuator MockMvc + trivy-action `0.28.0` removed). **CI-fix-2** follows. **No `v0.1.0-alpha.1` tag.**
项 1–5 已推；`9446a74` CI 红，修 CI-fix-2。**未打 alpha tag**。

AuthZ-1a. [done] 2026-10-09 CST; chose **Cedar** (not Casbin); inventory; ADR `docs/authz/cedar-or-casbin-adr.md`. Local commit only (no push).
    选定 Cedar；盘点与 ADR。仅本地提交。

AuthZ-1b. [done] 2026-10-09 CST; `CedarPolicyEngine` + `cedar-java:4.10.0:uber`; baseline.cedar; FFI fail-soft; PlatformWiring still SQL. Local commit only (no push).
    Cedar 适配器落地；默认仍 SQL。仅本地提交。

AuthZ-1c. [done] 2026-10-09 CST; dual-run 12 golden cases; anonymous later aligned in 1d. Local commit only (no push).
    双跑金样对齐。仅本地提交。

AuthZ-1d. [done] 2026-10-09 CST; default **`platform.authz.engine=cedar`**. **Item 1 (AuthZ Cedar) DONE.** Local commit only (no push).
    AuthZ 项 1 收工。仅本地提交。

## Pre-alpha track item 2 — controlled migration UX / 受控迁移体验

MigUX-2a. [done] 2026-10-09 CST; inventory MQ+RT-4 vs console; plan `docs/lowcode/migration-ux-deepen.md` (2a–2d); tiny win: entity draft save refreshes migration list. Local commit only (no push).
    盘点与加深计划；保存后刷新迁移列表。仅本地提交。

MigUX-2b. [done] 2026-10-09 CST; `loadMigrations(key?, kind?)`; auto-load after open entity + 新建业务表; PUT `enqueuedMigrationIds` + toast 「已入队 N 条 PENDING」; manual SQL under Advanced. Local commit only (no push).
    打开/保存自动拉队列；入队反馈；手工入队收进高级。仅本地提交。

MigUX-2c. [done] 2026-10-09 CST; guided 「审阅并执行」 (SQL confirm → review+apply); cancel PENDING/REVIEWED (`POST …/cancel`); FAILED highlight + Advanced re-queue; discrete Review/Apply kept. Local commit only (no push).
    一键审阅并执行（仍确认 SQL）；取消入队；失败行强调。仅本地提交。

MigUX-2d. [done] 2026-10-09 CST; path chip draft→PENDING→REVIEWED→APPLIED→promote; migrate audit panel (`declaration.migrate.*` via GET /audit); phrases polish. **Item 2 (controlled migration UX) DONE.** Local commit only (no push).
    路径芯片 + 迁移审计；项 2 收工。仅本地提交。

## Pre-alpha track item 3 — real AI + mandatory confirm / 真 AI + 强制确认写

AI-3a. [done] 2026-10-09 CST; inventory Cap stubs / CapabilityRunner / model-gateway / console try-run; plan `docs/ai/capability-real-ai-plan.md` (3a–3d); scaffold `ModelCompletionClient` + `LocalStubModelCompletionClient` + `AiWriteConfirmGate` / in-memory (no API keys; runner still stub). First capability: **ai.summarizePreview**. Local commit only (no push).
    盘点与计划；补全端口 + 写回确认门闩脚手架。仅本地提交。

AI-3b. [done] 2026-10-09 CST; `CapabilityRunner` → `ModelCompletionClient` for summarizePreview + suggestTitlePreview; `platform.ai.completion.mode=stub|http` (default stub); HTTP client fail-closed / no live vendor; preview-only. Local commit only (no push).
    Runner 接补全客户端；默认 stub。仅本地提交。

AI-3c. [done] 2026-10-09 CST; `POST …/write-ticket` + `…/write-back` (consume + noop sink); console issue ticket → confirm write-back; audit `capability.ai.preview`/`confirm`. Local commit only (no push).
    写回须确认票；默认 noop。仅本地提交。

AI-3d. [done] 2026-10-09 CST; E2E `AiSummarizePreviewConfirmE2ETest` (run→ticket→write-back once→reject); plan walkthrough + secrets/HTTP-gated notes. **Item 3 (real AI + mandatory confirm) DONE.** Local commit only (no push).
    E2E + 文档收工；项 3 完成。仅本地提交。

## Pre-alpha track item 4 — horizontal scale MVP / 水平扩展

Scale-4a. [done] 2026-10-09 CST; inventory breaker/rate-limit/relay lock/object-store/K8s P4; plan `docs/scale/horizontal-mvp-plan.md` (4a–4d); extract `DeliveryCircuitBreakerPort`; prefer JDBC shared counters over Redis for Java; **replicas:1 default kept**. Next = **Scale-4b** (`JdbcRateLimitPort` + backend switch). Local commit only (no push).
    盘点与计划；熔断端口抽出；默认仍单副本。下一片 4b。仅本地提交。

Scale-4b. [done] 2026-10-09 CST; `JdbcRateLimitPort` + Flyway `V26__rate_limit_window`; `platform.rate-limit.backend=process|jdbc` (default **process**); gateway process-only (jdbc fail-closed); dual-instance H2 shared-budget test; **replicas:1 default kept**. Next = **Scale-4c** (shared delivery breaker). Local commit only (no push).
    JDBC 共享限流；默认仍 process；下一片 4c 共享熔断。仅本地提交。

Scale-4c. [done] 2026-10-09 CST; `JdbcDeliveryCircuitBreakerPort` + Flyway `V27__delivery_circuit_breaker`; `platform.delivery.circuit-breaker.backend=process|jdbc` (default **process**); OutboxSocket/Kafka use port; dual-instance shared open-state test; **replicas:1 default kept**. Next = **Scale-4d** (gate/docs for replicas>1 when shared backends on). Local commit only (no push).
    JDBC 共享投递熔断；默认仍 process；下一片 4d 门禁与文档。仅本地提交。

Scale-4d. [done] 2026-10-09 CST; gate: advertise replicas>1 **only when** both rate-limit + circuit-breaker backends are `jdbc`; `single-replica-gate.md` + SECURITY §10 + ARCHITECTURE; object-store multi-pod caveat; default manifests still **replicas:1**; optional `platform-app-shared-backends.snippet.yaml`. **Item 4 (horizontal scale MVP) DONE.** Next = **item 5** independent config center MVP (suggested Config-5a inventory+plan). Local commit only (no push).
    多副本门禁与文档收工；项 4 完成。下一片项 5 配置中心。仅本地提交。

## Pre-alpha track item 5 — independent config center MVP / 独立配置中心

Config-5a. [done] 2026-10-09 CST; inventory ConfigSource/`config_override`/HTTP `/config`+`/api/v1/config`/HttpConfigSource/console; plan `docs/config/independent-config-center-plan.md` (5a–5d); extract `ConfigCenterPort` (`ConfigCatalog` implements); deepen DB+HTTP not new microservice; **Item 4 already DONE**. Next = **Config-5b** (namespace + revision schema). Local commit only (no push).
    盘点与计划；抽出配置中心端口；下一片 5b 命名空间+版本。仅本地提交。

Config-5b. [done] 2026-10-09 CST; Flyway `V28__config_override_namespace_revision` (namespace+revision, PK `(namespace, config_key)`); `ConfigCenterPort`/`JdbcConfigOverride` namespace-aware put returns revision; optional API `namespace` (default `default`, POST entries still 204); `JdbcConfigNamespaceRevisionTest`. Next = **Config-5c** (ETag/poll). Local commit only (no push).
    命名空间+修订号；下一片 5c ETag/轮询。仅本地提交。

Config-5c. [done] 2026-10-09 CST; `ConfigETags` `"N"`; GET entries ETag + If-None-Match→304; PUT/POST If-Match→412; `HttpConfigSource` knownRevisions + conditional read/override; console revision column + If-Match on save; proxy forwards concurrency headers; history table skipped. Next = **Config-5d** (namespace UX + docs → Item 5 DONE). Local commit only (no push).
    ETag/If-Match；控制台修订号；下一片 5d 文档与命名空间打磨以完成项 5。仅本地提交。

Config-5d. [done] 2026-10-09 CST; console namespace picker (`default`+query); list/PUT honor namespace; honesty notice; ARCHITECTURE §7/§10/§16 + config-ui link plan. **Item 5 (independent config center MVP) DONE.** Next track = **CI fix + docs收口 + alpha** (items 1–5 complete). Local commit only (no push).
    命名空间 UX 与文档诚实收口；项 5 完成。下一轨修 CI/文档/打 alpha。仅本地提交。

## Release track — CI fix → docs收口 → alpha / 发版轨

CI-fix-1. [done] 2026-10-09 CST; VendorStartupTest: V16 `sql_text` → **TEXT** (MySQL utf8mb4 row-size) + V29 widen-after-repair; probes assert **management port** (P2; business-port readiness 401/404 expected). H2 V29 suite green; VendorStartupTest skipped locally (no Docker). image-scan earlier “Set up job” failure looks **runner/infra** — workflow YAML unchanged this slice; re-check on next CI run. Next = docs收口 then alpha (no tag yet). Local commit only (no push).
    MySQL 行大小与就绪探针管理口；无 Docker 未真跑厂商启动。image-scan 疑似基建。下一片文档收口。仅本地提交。

Docs-align-1. [done] 2026-10-09 CST; ARCHITECTURE §16/§18 (O8, Cedar default, Subject/Organization pickers, Basic-1 landed); lowcode-roadmap picker/O8 lines; CHANGELOG capacity 1–5 + CI-fix + P6 PASS (no DRILL-PENDING / Tag-blocked-on-drill lie); checklist tip reds = remote CI / image-scan / no tag; SECURITY §9 O8+Cedar; release-prep blockers refreshed. **Still no push / no alpha tag / not production-ready.** Next = remote verify then alpha cut when owner says so.
    文档对齐 O8/Cedar/能力轨/CI；检查表红项仅剩远端 CI 与未打 tag。仅本地提交。

Capacity-push. [done] 2026-10-09 CST; squashed AuthZ→Config + CI-fix-1 + docs收口 onto `github/main` as `9446a74` (parent `1949791`; tree matches local `75883ba` / `0b30dfe`). Fast-forward only, never force. **No alpha tag.** Actions: https://github.com/freshsdok/subjex/actions/runs/37894895756
    能力轨+CI+文档已 squash 推送；未打 tag。

CI-fix-2. [done] 2026-10-09 CST; `GatewayHttpFlowTest.actuatorStaysLocal` → management port (P2 `RANDOM_PORT` + `management.server.port=0` + HttpClient; business `/actuator` 404, no upstream). Trivy pin `aquasecurity/trivy-action@v0.36.0` (non-`v` tags removed after supply-chain remediation; `0.28.0` unresolved). Local GatewayHttpFlowTest 4/0/0. Next = squash-push then watch Actions (no alpha tag).
    网关探针改管理口；Trivy 钉 `v0.36.0`。下一片 squash 推送。

CI-fix-3. [done] 2026-10-09 CST; P1 Trivy deps on Boot **3.5.16** (last OSS 3.5): `tomcat.version=10.1.60`, `jackson-bom.version=2.21.7`, `postgresql.version=42.7.14`. Reactor tests green (VendorStartup excluded locally). **Residual:** spring-webmvc 6.2.19 CRITICAL → needs Framework 7.0.9 / Boot 4 (Jackson 3); Framework 7-on-3.5 tests break; Boot 4.0.8 POM needs testcontainers 2.x rename. Documented in `docs/release/image-scan.md`. **Ask owner:** Boot 4 track vs written P1 waiver before alpha tag. Next = squash-push overrides (image-scan may still red on spring CRITICAL).
    Tomcat/Jackson/PG 已升；spring-webmvc CRITICAL 需 Boot 4 或书面豁免。

Boot4-1. [done] 2026-10-09 CST; Spring Boot **4.0.8** + Framework 7.0.9; testcontainers 2.x rename; Jackson 3 (`tools.jackson` / `JacksonException`); Boot 4 package moves + `spring-boot-starter-webmvc-test` / `security-test`; jackson `datatype.datetime`; Kafka MockProducer Partitioner; consumer autoconfigure.exclude FQCNs. Plan `docs/release/boot4-migration.md`. Local `mvn test` green excl. VendorStartup. Next = squash-push → CI image-scan (expect spring-webmvc CRITICAL gone) → Boot4-2 polish / alpha when green. **No tag yet.**
    Boot 4.0.8 切片完成；待 squash 推送看 image-scan。未打 tag。
