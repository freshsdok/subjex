# Low-code deepening roadmap / 低代码加深路线

Ordered stages after Node 6 (§15 in `ARCHITECTURE.md`). Architecture summary: `ARCHITECTURE.md` §17.

节点六之后的有序加深。架构摘要见 `ARCHITECTURE.md` 第 17 节。

## Non-goals / 明确不做

Stages **0–6** (shipped declaration chain) keep the following. The **zero-code target** (see [Zero-code model](#zero-code-model--七维零代码模型)) revises designer/schema authoring into a structured console **page builder / configurator** (visual blocks from a common component catalog) + **decided** dual-track promotion — still not a free canvas, casual online DDL, or arbitrary scripts.

阶段 **0–6**（已交付的声明链路）仍遵守下表。**零代码目标**（见 [零代码模型](#zero-code-model--七维零代码模型)）把设计器/schema 编写收成「结构化控制台**页面构建器/配置器**（常用组件积木可视化搭建）+ **已拍板**双轨晋升」，仍不是自由画布、随意在线 DDL 或任意脚本。

| Non-goal / 不做 | Why / 原因 |
| --- | --- |
| Unrestricted visual / free-canvas designer (v1) | v1 uses a structured **page builder / configurator**: compose pages from a catalog of **most-used components as visual building blocks** — not Retool-style free canvas. 首期用结构化页面构建器（常用组件积木），不做自由画布。 |
| Casual / ad-hoc online DDL for field changes | Field and schema changes follow **strict DB management** (versioned controlled migrations; promote with dual-track). No casual console-driven DDL. 字段/schema 变更走严格库管与晋升，禁止控制台随意在线 DDL。 |
| Arbitrary scripts embedded in pages | Actions / algorithms / AI only via fail-closed catalogs. 页面内禁止任意脚本；动作/算法/AI 只走白名单目录。 |
| AI auto-write without human confirm | Critical state changes need an explicit confirm step (or an audited allow-auto flag). AI 写关键状态默认需人工确认。 |
| Form marketplace / per-tenant form store | Same as Node 6. 与节点六一致。 |
| Forced init wizard | `subjex-init` is optional; `--skip` / typing `skip` / never running it are all fine. 初始化可跳过。 |
| Bootstrap on every start | Operator bootstrap stays one-shot and off by default (`docs/operator-permissions.md`). 开通仍是一次性、默认关闭。 |

## Stages (exact order) / 阶段（严格顺序）

Do not start stage *N+1* until stage *N* is committed and usable for the next slice.

前一阶段未提交可用前，不开始后一阶段。

| # | Stage / 阶段 | Intent / 意图 | Status / 状态 |
| --- | --- | --- | --- |
| 0 | Skippable CLI init / 可跳过 CLI 初始化 | Generate local stubs (env / `application-local.yml`), Redis + `OPERATOR_SESSION_SECRET` notes, compose hint, optional bootstrap command text. Interactive defaults + `--yes`. Explicit skip. | done |
| 1 | Entity YAML → migration / CRUD drafts / 实体 YAML → 迁移与 CRUD 草稿 | Checked-in entity declarations drive Flyway drafts and thin JDBC CRUD stubs. | done |
| 2 | Page / flow declarations / 页面与流程声明 | Declarative pages and flows on top of entities. | done |
| 3 | Permission / tenant on declarations / 声明上的权限与租户 | Named permissions and tenant boundaries on the same declaration set. | done |
| 4 | Side-effect catalog / 副作用目录 | Enumerable side effects for flows and audit. | done |
| 5 | Versioning / 版本化 | Version conventions for declarations and generated artifacts. | done |
| 6 | Console debug UX / 控制台调试 UX | Operator-console read/debug experience after the declaration chain. | done |

## Stage 0 notes — init / 第 0 阶段说明

- Module: `tools/subjex-init` (thin Java main, no Spring).
- Run: see [`docs/quickstart.md`](quickstart.md) §0, or README “Init (optional)”.
- Does **not** start the database, Redis, or operator bootstrap. It only writes stubs and prints hints.
- 不启动数据库、Redis，也不执行开通；只写 stub 并打印提示。

## Stage 1 notes — entity drafts / 第 1 阶段说明

- Module: `entity-declare` (thin renderer + generators; no Spring).
- Sample: `entities/service-note.entity.yaml` → checked-in drafts under `db/migration-draft/` and `com.subjex.entity.generated`.
- Spec keys: `entityKey`, `tableName`, required `version` (≥ 1), `fields[]` with `name` / `kind` (`text`|`integer`) / `required` / optional `maxLength`.
- First field is the primary key. SQL drafts are **not** applied by `platform-app` Flyway until a human moves them (sample `service_note` promoted as V11).
- 第一个字段视为主键。SQL 草稿在人工移入 `platform-app` 迁移目录前不会被应用（样例 `service_note` 已作为 V11 迁入）。

### Product decision — entity host wiring / 产品决策：实体接入宿主

**`entity-declare` is not a permanent draft-only module.** Stage 1 delivered checked-in drafts on purpose. Post-stage #3 moved `V1__service_note.sql` into `platform-app` Flyway as `V11__service_note.sql`. Runtime CRUD is now **`GenericEntityStore`** (bespoke host `JdbcServiceNoteStore` removed after the write cutover). The module itself stays Spring-free; regenerate drafts here, promote SQL into Flyway when the table changes.

**`entity-declare` 不是永久只出草稿的模块。** 第 1 阶段故意只检入草稿。阶段后第 3 项已将 `V1__service_note.sql` 迁入 `platform-app` Flyway（`V11__service_note.sql`）。运行时 CRUD 现为 **`GenericEntityStore`**（专用宿主 `JdbcServiceNoteStore` 已在写路径切换后删除）。模块本身保持无 Spring；在此重生草稿，表变更时再迁入 Flyway。

Regenerate drafts / 重新生成草稿:

```shell
mvn -pl entity-declare -am -DskipTests package
java -cp "entity-declare/target/entity-declare-0.1.0-SNAPSHOT.jar:$(mvn -pl entity-declare -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)" \
  com.subjex.entity.declare.EntityDraftWriteMain \
  entity-declare/src/main/resources/entities/service-note.entity.yaml \
  entity-declare/src/main/resources/db/migration-draft \
  entity-declare/src/main/java/com/subjex/entity/generated \
  com.subjex.entity.generated
```


## Stage 2 notes — page / flow / 第 2 阶段说明

- Module: `page-declare` (thin renderer/validator; no designer).
- Samples: `flows/endpoint-publication.flow.yaml` (form submissions) and `flows/service-note.flow.yaml` (entity → page → form; see [Post-stage #4](#sample-flow-notes-post-stage-4--贯通样例说明阶段后第-4-项)).
- Spec keys: `flowKey`, `titleEn`, `titleZh`, optional `formKey`, optional `entityKey`; `list` (`path`, `apiPath`, optional `itemsKey`); `detail` (`path` with `{id}`, `apiPath`, optional `itemsKey`, `idField`); `submit` (`path`, `apiPath`, `redirectTo`).
- Platform: `PageCatalog` + `GET /api/v1/pages` / `GET /api/v1/pages/{flowKey}` (`page.read`).
- Console (`web/`): `/pages` index, `/pages/[flowKey]` list, `/pages/[flowKey]/[id]` detail, `/pages/[flowKey]/new` submit-then-redirect. No visual designer.
- 模块 `page-declare`：校验渲染；样例接表单提交或实体列表 API；控制台按声明渲染列表/详情/提交跳转，无设计器。


## Stage 3 notes — permission / tenant / 第 3 阶段说明

- Spec keys on form / entity / flow YAML: required `permission` (dotted lowercase, e.g. `registry.write`), optional `tenantScoped` (boolean, default `false`). Missing permission is rejected at render / catalog load (fail-closed).
- Samples: forms `endpoint-publication` → `registry.write`, `config-override` → `config.write`; flow + entity → `page.read`; all `tenantScoped: false`.
- Catalog JSON exposes `permission` / `tenantScoped` on index and detail so the console can hide or grey out entries.
- API enforcement (`DeclarationAccess`): form detail, page detail, and form submissions check the declaration's permission; when `tenantScoped`, require `X-Tenant-Id` via `TenantGuard`. Security filter stays generic (authenticated) so new forms do not need new matcher lines.
- 声明键：必填 `permission`，可选 `tenantScoped`（默认 false）；缺权限在渲染/目录加载即拒。目录 API 暴露标志；接口按声明权限与租户检查，免改安全配置。


## Stage 4 notes — side-effect catalog / 第 4 阶段说明

- Catalog: `form-render` checked-in `effects/side-effect-catalog.yaml` + `SideEffectKey` enum (`audit.write`, `task.enqueue`, `extension.invoke`) with typed param names; unknown keys fail-closed at render.
- Form YAML optional `effects[]`: `key` + `params` map referencing catalog keys. Samples: `endpoint-publication` declares audit + task enqueue; `config-override` declares audit + extension invoke.
- Runtime: `FormSideEffectRunner` after successful validate + domain action; calls `OperatorActionAudit` / `TaskMessagePort` / resolves `PlatformExtension` under the same operator (tenant `platform` when not scoped). Domain register/config handlers stay form-specific; hardcoded audit beside them removed in favor of declarations.
- No online designer.
- 目录检入 YAML + 枚举；表单可选 `effects`；提交后经已有端口执行；未知键渲染即拒；无设计器。


## Stage 5 notes — versioning / 第 5 阶段说明

- Required integer `version` (≥ 1) on form / entity / flow YAML; renderers fail-closed if missing or invalid. Samples use `version: 1`.
- Catalog JSON exposes `version` on form/page index and detail.
- `form_submission.declaration_version` via platform Flyway `V5`; written on accept; history returns `declarationVersion`.
- Process doc: [`docs/declaration-migration.md`](declaration-migration.md) — bump version on field changes; entity YAML → new migration draft; read old submissions against old versions; rollback = redeploy prior declaration, do not auto-drop columns.
- No online schema edit.
- 表单/实体/流程 YAML 必填整数 `version`；目录暴露版本；提交落库记下声明版本；迁移策略见 declaration-migration.md；无在线改 schema。


## Stage 6 notes — console debug UX / 第 6 阶段说明

- API: form submit returns structured success (`submissionId`, `declarationVersion`, `submittedAt`, `effects[]`) and problems (`kind: validation` + `fieldErrors[]`, or `kind: permission_denied` + `permission`). OpenAPI / `web/openapi.json` / `schema.d.ts` aligned.
- Console: shared `FormDebugPanel` on `/forms` and `/pages/.../new` shows validation, permission denied, persist OK, and effect summary in one place. No designer.
- Helpers: `web/src/lib/form-debug.ts` + vitest; backend validation / security tests cover the new shapes.
- 接口：提交成功与失败均为结构化 JSON；控制台表单与页面新建共用调试面板；无设计器。

## Slice log / 切片记录

- A. [done] §17 + this roadmap + skippable `tools/subjex-init` + quickstart/README docs; local commits only (no push).
- B. [done] Stage 1: `entity-declare` — `service-note` YAML, renderer/validator, Flyway SQL draft + record/CRUD/JDBC stubs, `EntityDraftWriteMain` CLI, tests; local commits only (no push).
- C. [done] Stage 2: `page-declare` — `endpoint-publication` flow YAML, PageRenderer/validator, `PageCatalog` + pages JSON API, console list/detail/submit-redirect; tests; local commits only (no push).
- D. [done] Stage 3: `permission` + `tenantScoped` on form/entity/flow YAML; renderer fail-closed; catalog APIs expose flags; `DeclarationAccess` on form/page/submission APIs; console greys out lacking permission; tests; local commits only (no push).
- E. [done] Stage 4: side-effect catalog YAML + `SideEffectKey`; form `effects` on samples; `FormSideEffectRunner` invokes audit/task/extension ports after submit; renderer rejects unknown keys; tests; local commits only (no push).
- F. [done] Stage 5: required `version` on form/entity/flow YAML; catalog exposes version; `declaration_version` on submissions (Flyway V5); `docs/declaration-migration.md`; tests; local commits only (no push).
- G. [done] Stage 6: structured form submit success/problem payloads; console debug/result panel on forms and page submit; OpenAPI aligned; tests; local commits only (no push). All six deepening stages complete locally.
- H. [done] Product lock: entity-declare will be wired into `platform-app` (not permanent draft-only); documented here + README + pre-release note. Local commit only (no push).
- I. [done] Domain action declarative: catalog + `domainAction` on forms; `FormDomainActionRunner` replaces formKey if-branches; tests + docs; local commit only (no push).
- J. [done] Sample flow entity→page→form: `service-note` flow + form + `entity.serviceNote.save`; list API + console `/pages/service-note`; tests + docs; local commit only (no push).
- K. [done] Entity → platform-app (step 3): Flyway `V11__service_note.sql`; `JdbcServiceNoteStore` replaces in-memory bean; same `GET /api/v1/entities/service-note/notes` JSON; tests + docs; local commit only (no push).
- L. [done] Z0 docs: zero-code seven-dimension model + people/org base (dim 0), dual-track default, Z1–Z6 + algo/AI phases, non-goals; `ARCHITECTURE.md` §18 + README pointer; local commit only (no push).
- M. [done] Product lock: **dual-track B decided** (console drafts + promote to Git YAML); first wave = Z1–Z4 (Z1↔Z3 overlap OK); YAML-only interim rejected as product path; `ARCHITECTURE.md` §18 + README; local commit only (no push).
- N. [done] Priority lock: **R1 decided** — Z1 generic entity CRUD first, then **thin** people/org base (tree + membership + read-only), then Z2 generic pages (reserve User/Org pickers), then Z3/Z4; SCIM and complex dual-role deferred; `ARCHITECTURE.md` §18; local commit only (no push).
- O. [done] People model lock: **permission tiers** (not separate login account types as primary split); **platform super-admin isolated** from all other accounts; `ARCHITECTURE.md` §18; local commit only (no push).
- P. [done] UX/runtime locks: **page builder/configurator** first (most-used components as visual blocks; not free canvas); **algo/AI thin** in first wave (catalog + 1–2 stubs); **strict DB management** for field/schema changes (controlled migrations / no casual online DDL; align dual-track promote); permission tiers + isolated super-admin already locked; `ARCHITECTURE.md` §18; local commit only (no push).
- Q. [done] Dual-track ops locks: promote via **internal git** (self-hosted / in-platform; **not** GitHub-dependent); declaration drafts **tenant-scoped**; platform **super-admin** own role name (e.g. `platform.super-admin`); `ARCHITECTURE.md` §18; local commit only (no push).
- R. [done] Runtime/ops locks: **promoted declaration metadata may hot-reload**; **schema/table changes** go through **migration queue** and declaration switches only after migration completes (**bound together**); first-wave **page blocks** listed (generic form components, ListTable, FormFields, DetailReadonly, Section/Tabs, SubmitBar, UserPicker/OrgPicker placeholders, flow sorter/router); Z1 engine = **new sample and/or parallel read-adapt `service_note`** (old JDBC may remain then delete); **HTTP Basic** safer default (off outside `local`) — curl/docs with Z1, implementation may follow; `ARCHITECTURE.md` §18; local commit only (no push).
- S. [done] Z1-1: EntityCatalog + userRef/orgRef + demo-ticket YAML/V12; local commit only.
- T. [done] Z1-2: generic JDBC CRUD + REST /records by entityKey (demo-ticket); local commit only.
- U. [done] Z1-3: parallel generic read for service_note + Basic safer-default docs; local commit only.
- V. [done] Thin-org-1: org_unit + membership Flyway V13, org.read, platform.super-admin role reserved, JDBC reader; local commit only.
- W. [done] Thin-org-2: read-only GET /api/v1/org/units|memberships + org.read security; local commit only.
- X. [done] Z2-1: page-block catalog + React stubs (ListTable wired; User/Org pickers placeholder); local commit only.
- Y. [done] Z2-2: demo-ticket flow/form + entity.record.upsert generic domain action; local commit only.
- Z. [done] Z2-3: detail GET /records/{id}, live User/Org pickers (thin), forms entityKey; local commit only.
- AA. [done] Z3-1: declaration_revision + JdbcDeclarationStore + declaration.read/write; local commit only.
- AB. [done] Z3-2: declaration draft HTTP + effective overlay (DB over classpath); local commit only.
- AC. [done] Z3-3: runtime overlay when tenant header present (entity/form/flow); Z3 baseline landed; local commit only.
- AD. [done] Z4-1: console /declarations draft YAML editor (tenant-scoped); local commit only.
- AE. [done] Z4-2: structured flow block composer + optional list/detail/submit.blocks; local commit only.
- AF. [done] Z4-3: thin entity/form structured wizards; Z4 baseline landed; local commit only.
- AG. [done] Z5-1: internal git promote core + PROMOTED state + declaration.promote; local commit only.
- AH. [done] Z5-2: POST promote API + audit + promote history; local commit only.
- AI. [done] Z5-3: console promote on /declarations; Z5 baseline landed; local commit only.
- AJ. [done] thin algo/AI catalog + stubs + optional domain actions; local commit only.
- AK. [done] Z6-1: entity field kinds boolean/enum/date/entityRef + store validate/coerce + migration types; tests-only (no Flyway); local commit only.
- AL. [done] Z6-2: list filter/sort + entity/form wizard kinds + form FieldKind; page widgets deferred; local commit only.
- AM. [done] Z6-3: page form widgets boolean/enum/date + FieldDocument.enumValues; Z6 baseline landed; local commit only.
- AN. [done] Thin declared-list filter/sort UI (searchParams + bar; generic /records only); local commit only.
- AO. [done] service-note form writes cut over to GenericEntityStore (`entity.record.upsert`); bespoke `JdbcServiceNoteStore` removed; `/notes` list shim kept; local commit only.
- AP. [done] MQ-1: `declaration_migration` queue (V16) + `declaration.migrate` + store/HTTP enqueue·list·review; **no apply**; local commit only.
- AQ. [done] MQ-2: apply REVIEWED entity DDL (fail-closed) + entity promote bind to APPLIED/CANCELLED; local commit only.
- AR. [done] MQ-3: console migration queue on `/declarations` (entity); local commit only. **Migration-queue baseline landed.**


## Thin algo/AI progress / 薄算法·AI 进度

- [done] Classpath catalogs: `platform-app/.../capabilities/algorithm-catalog.yaml`, `ai-catalog.yaml`.
- [done] Java: `CapabilityKind`, `CapabilityId`, `CapabilityCatalog` (fail-closed), `CapabilityRunner` stubs (`algo.hashFingerprint` SHA-256, `ai.summarizePreview` template; no store write; no vendor SDK / no platform-app→model-gateway hard dep).
- [done] Domain actions: `capability.algo.hashFingerprint`, `capability.ai.summarizePreview` (`inputText`) wired in `FormDomainActionRunner`.
- [done] `GET /api/v1/capabilities` with `page.read`.
- [done] Tests: catalog, runner, FormDomainActionRunner arms, CapabilityApiSecurityTest.
- Local commit only (no push).


## Z6 field kinds progress / Z6 字段种类进度

- [done] Z6-1: `boolean` (JDBC BOOLEAN), `enum` (VARCHAR + required `enumValues`), `date` (VARCHAR(10) ISO `yyyy-MM-dd`), optional `entityRef` (VARCHAR + optional `refEntityKey`).
- [done] `EntityRenderer` rejects enum without values, `enumValues` on non-enum, `maxLength` on boolean/date.
- [done] `GenericEntityStore` coerce/validate; `EntityMigrationGenerator` / stub Java types.
- [done] Unit tests (inline YAML); **no** demo-ticket Flyway in this slice (latest still V15).
- [done] Z6-2: list filter/sort API (`sort`/`order`/`filterField`/`filterValue`); entity-wizard kinds; form `FieldKind` boolean/date/enum + form-wizard. Page runtime widgets deferred (Z6-3).
- [done] Z6-3: shared `form-field-input` helper + publication / declared-submit widgets (checkbox / date / select / number / text); `FieldDocument.enumValues` on forms API; loaders pass through.
- [done] Thin list filter/sort UI on declared list pages (URL searchParams; hide for non-`/entities/.../records` paths).
- **Z6 baseline landed.**
- Local commit only (no push).


## Migration queue progress / 迁移队列进度

- [done] MQ-1: Flyway `V16__declaration_migration.sql` (`declaration_migration` + index); permission **`declaration.migrate`** (platform-operator; separate from `declaration.promote`).
- [done] `JdbcDeclarationMigrationStore` — enqueue PENDING, list, listForRevision, findById, markReviewed / markApplied / markFailed / markCancelled.
- [done] HTTP: `GET|POST /api/v1/declarations/{kind}/{key}/migrations`, `POST …/migrations/{id}/review` (GET=`declaration.read`; POST=`declaration.migrate`).
- [done] MQ-2: `DeclarationMigrationApplyService` — REVIEWED + entity only; fail-closed single-statement `ALTER TABLE`/`CREATE TABLE`; reject DROP/TRUNCATE/ALTER…DROP; txn execute → APPLIED / FAILED; `POST …/migrations/{id}/apply` + audit `declaration.migrate.apply`.
- [done] Entity promote bind: block 409 if any migration for revision is not APPLIED/CANCELLED; no rows → allow; form/flow unchanged.
- [done] MQ-3: `/declarations` entity migration queue UI (list/enqueue/review/apply review→confirm); `canMigrate`; zh/en; vitest helpers; proxy POST already allowed.
- **Migration-queue baseline landed.**
- Local commit only (no push).


## Domain action notes (post-stage #2) / 领域动作说明（阶段后第 2 项）

- Catalog: `form-render` checked-in `actions/domain-action-catalog.yaml` + `DomainActionKey` enum (`registry.register`, `config.override`, entity saves, `capability.algo.hashFingerprint`, `capability.ai.summarizePreview`); each action lists required form field names.
- Form YAML required `domainAction` (fail-closed at render); required catalog fields must exist on the form. Samples bumped to `version: 2`.
- Runtime: `FormDomainActionRunner` switches on the declared key (not `formKey`). Catalog JSON exposes `domainAction` on form index/detail.
- New form reusing an existing key: YAML only. New key: add enum + catalog row + one switch arm.
- 目录检入 YAML + 枚举；表单必填 `domainAction`；执行按声明键而非 formKey；复用已有键只需 YAML。


## Sample flow notes (post-stage #4) / 贯通样例说明（阶段后第 4 项）

- Flow: `page-declare` `flows/service-note.flow.yaml` — `entityKey: service-note`, `formKey: service-note`; list/detail → `GET /api/v1/entities/service-note/notes` (`itemsKey: notes`, `idField: noteId`); submit → form submissions then redirect to list.
- Form: `form-render` `forms/service-note.form.yaml` — fields align with `entities/service-note.entity.yaml`; `domainAction: entity.record.upsert` + `entityKey: service-note`; permission `page.read`.
- Runtime: **writes via `GenericEntityStore`** (`entity.record.upsert` / Flyway V11 `service_note`). Bespoke `JdbcServiceNoteStore` **removed**. List also on generic `GET /api/v1/entities/service-note/records`; `/notes` remains a **compatibility shim** (same `{ notes: [...] }` JSON for the console flow).
- Console click-through: `/pages` → **Service notes / 服务备注** → list (may be empty) → **New** → fill noteId/title → submit → back on list → open detail.
- 流程绑实体与表单；**写经通用存储**；列表亦可走 `/records`；`/notes` 为兼容垫片。控制台从 `/pages/service-note` 走通列表→新建→详情。

## Entity host notes (post-stage #3) / 实体接入说明（阶段后第 3 项）

- Flyway: `platform-app/.../db/migration/V11__service_note.sql` (promoted from `entity-declare/.../migration-draft/V1__service_note.sql`).
- Runtime: `GenericEntityStore` + `EntityCatalog` own service-note CRUD (reads + form writes). Bespoke `JdbcServiceNoteStore` / `ServiceNoteStore` host bean **removed**. `entity-declare` generated store stubs remain Spring-free drafts. Flyway V11 table kept.
- API: compatibility `GET /api/v1/entities/service-note/notes` → `{ "notes": [ { noteId, title, body, priority } ] }` (list via generic store). Prefer `GET /api/v1/entities/service-note/records` for new clients.
- List order: `ORDER BY note_id` (stable; draft has no `updated_at`). Empty table is valid.
- 迁移 V11；**写与读均经通用存储**；`/notes` 兼容垫片保留；新客户端优先 `/records`；空表可用。

## Post-stage next work / 阶段后下一步

Ordered follow-ups after stages 0–6 (do in this sequence unless the owner renumbers):

阶段 0–6 之后的后续工作（除非负责人改序，否则按此顺序）：

| # | Work / 工作 | Intent / 意图 | Status / 状态 |
| --- | --- | --- | --- |
| 1 | Entity host product lock / 实体接入产品锁定 | Document that `entity-declare` drafts will enter `platform-app` later — not permanent draft-only. | done (this note) |
| 2 | Domain action declarative / 领域动作声明化 | Replace `FormSubmissionEndpoint` `formKey` if-branches with enumerable `domainAction` + catalog so new forms need less Java. | done |
| 3 | Entity → platform-app / 实体接入宿主 | Move `service-note` migration into platform Flyway; replace in-memory store with JDBC; keep `/api/v1/entities/service-note/notes` shape. | done |
| 4 | Sample flow spanning entity→page→form / 贯通样例 | One flow that ties an entity, page, and form end-to-end. | done |

Owner order for the current pass: **1 → 2 → 4 → 3** (lock → domainAction → flow sample → entity migrate) — **all done locally**.
当前回合负责人顺序：**1 → 2 → 4 → 3** — **本机已全部完成**。

Further product direction: **zero-code seven dimensions + people/org base** — see the next section.
后续产品方向：**零代码七维 + 人员/组织底座** — 见下一节。

## Zero-code model / 七维零代码模型

Agreed product model (owner confirmed). **Declaration remains the single source of truth.** Console editing writes the same declaration shape; runtime interprets it. This sits **on top of** stages 0–6 (YAML → validate → host), not a parallel stack.

已确认的产品模型。**声明仍是唯一真相。** 控制台编辑写同一套声明形状；运行时解释执行。叠在阶段 0–6（YAML → 校验 → 宿主）之上，不是另起炉灶。

### Platform base — people & org (dim 0) / 平台底座 — 人员与组织（第 0 维）

| Topic / 主题 | Rule / 规则 |
| --- | --- |
| Tenant / 租户 | Hard **isolation** boundary for data and grants. 数据与授权的硬隔离边界。 |
| Org / 组织 | `org_unit` **tree inside a tenant** (department/team); never use tenant to fake a department. 租户内组织树；不用租户冒充部门。 |
| People model / 人员模型 | **Permission separation**, not separate login account types as the primary split. Same subject/account machinery can serve console and business work; what differs is **roles / named permissions** (and org scope). 主分割是**权限分层**，不是先拆两套登录账号类型。 |
| Platform super-admin / 平台超管 | Own **role name** (e.g. `platform.super-admin`), **isolated** from all other accounts/roles: distinct privilege set (and typically a dedicated bootstrap / break-glass path). No ordinary operator or business role inherits or aliases full platform power. 独立角色名（如 `platform.super-admin`），与其他一切账号/角色分开；普通操作员/业务角色不得继承或冒充全平台权力。 |
| Console vs business work / 控制台 vs 业务办事 | “Operator” vs “business user” is a **permission tier** (what you may open and mutate), not a mandatory second account product. One person may hold both tiers only if explicitly granted. 「操作员 / 业务」是权限档位，不强制第二套账号产品。 |
| Identity source / 身份来源 | **IdP / OIDC primary**; platform stores bindings + local grants. **SCIM** (full directory sync) later. 以 IdP/OIDC 为主；全量 SCIM 后置。 |
| Permissions / 权限 | Named permissions stay fail-closed. Org only supplies **scope** (e.g. own unit and below) — it does **not** replace permission strings. 具名权限 fail-closed；组织只做范围，不替代 permission。 |

**Decided / 已拍板：people model** (owner confirmed) — split by **permission tiers**; platform **super-admin** is its **own role name** (e.g. `platform.super-admin`), isolated from all other accounts/roles; do **not** treat separate login account types as the primary design axis.

**已拍板：人员模型**（负责人确认）——按**权限分层**区分；平台超管为**独立角色名**（如 `platform.super-admin`），与其他账号/角色隔离；不以「分登录账号类型」为主轴。

**Decided / 已拍板：priority R1** (owner confirmed).

**已拍板：优先级 R1**（负责人确认）。

Sequence: **Z1** (generic entity CRUD) → **thin people/org** (`org_unit` tree + membership + read-only APIs; reserve `userRef`/`orgRef` field kinds even if pickers land with Z2) → **Z2** (generic pages; reserve UserPicker / OrgPicker slots) → **Z3/Z4** (declaration store + structured configurator). **Deferred:** full SCIM sync, complex dual-role / concurrent appointments.

顺序：**Z1** 通用实体 CRUD → **薄人员/组织**（组织树 + membership + 只读 API；字段种类预留 `userRef`/`orgRef`）→ **Z2** 通用页（预留选人/选部门组件位）→ **Z3/Z4**。后置：全量 SCIM、复杂兼岗。

Rejected as default sequencing: R2 (org-first for strong approval demos); R3 (strict two-team parallel — not fit for single-thread delivery).

已否决为默认排期：R2（组织优先）；R3（严格双线并行）。

### Seven dimensions / 七维

| # | Dimension / 维度 | Configures / 配置什么 | Runtime role / 运行时 |
| --- | --- | --- | --- |
| 1 | Data / entity / 数据（实体） | Objects, fields, refs, validation | Generic CRUD / query |
| 2 | Page + basic components / 页面 + 基本组件 | **Structured page builder / configurator** (not free canvas): design **most-used** components (inputs, select, table, sections, pickers…) as **visual building blocks** to compose list/form/detail. Components are dim-2 bricks, not an 8th dim. **结构化页面构建器/配置器**（非自由画布）：常用组件作积木可视化搭页。 | Declaration → render |
| 3 | Flow / 流程 | Navigation and steps (list → detail → create → edit; success/fail branches) | Business path |
| 4 | Permission / tenant / 权限与租户 | Who may read/write; tenant isolation | Fail-closed gate |
| 5 | Actions / side-effects / 动作与副作用 | After submit: persist, task, audit, extension | Whitelist catalog execution |
| 6 | Algorithm modules / 算法模块 | Deterministic catalog: rules, scoring, routing, validators, aggregates — same in → same out; testable/replayable | Pure functions on declaration inputs → entity/flow vars |
| 7 | AI modules / AI 能力 | Model calls via existing **`model-gateway`**: summarize, classify, extract, draft | Non-deterministic; **human confirm** before critical writes (aligns with task confirm points) |

**Algorithm vs AI hard boundary / 算法与 AI 硬边界：** algorithms are deterministic and catalog-only; AI goes through the gateway with metering and must not silently mutate critical state unless the flow has an explicit confirm (or audited allow-auto) step. Neither dimension allows pasting arbitrary scripts into pages.

算法为确定性目录项；AI 经网关记账，关键写回默认需确认。两者都不得在页面内贴任意脚本。

**Decided / 已拍板：page builder** (owner confirmed) — first wave ships a **structured builder/configurator**, not a free canvas; invest in the **most-used page components** as visual building blocks.

**已拍板：页面构建器**（负责人确认）——首波交付**结构化构建器/配置器**，不做自由画布；优先设计**最常用页面组件**作为可视化积木。


**Decided / 已拍板：first-wave page blocks** (owner confirmed) — first wave component catalog includes: **generic form components**; **ListTable**; **FormFields** (generated from entity fields); **DetailReadonly**; **Section / Tabs**; **SubmitBar**; **UserPicker / OrgPicker** placeholders (wire real data after thin org); **flow sorter / router** (流程分拣器). Not free canvas; not charts / large rich-text editors in v1.

**已拍板：首波页面积木**（负责人确认）——首波组件目录含：**通用窗体组件**；**ListTable**；**FormFields**（按实体字段生成）；**DetailReadonly**；**Section / Tabs**；**SubmitBar**；**UserPicker / OrgPicker** 占位（薄组织后再接真数据）；**流程分拣器**。非自由画布；v1 不做图表 / 大型富文本编辑器。

**Decided / 已拍板：algorithm / AI thin first wave** (owner confirmed) — first wave includes only a **thin layer**: capability **catalog + 1–2 stubs** (wireable from flow/action), **not** full algorithm or AI engines.

**已拍板：算法/AI 首波薄层**（负责人确认）——首波仅**目录 + 1～2 个桩**（可从流程/动作挂接），不做完整算法/AI 引擎。

**Decided / 已拍板：strict schema / field migration** (owner confirmed) — changing fields must follow **strict database management**: versioned **controlled migrations** / **migration queue**, reviewed and applied through the **dual-track promote** path; declaration cutover is **bound** to migration completion (see hot-reload bind above). **No casual online DDL** from the console.

**已拍板：字段/schema 严格库管**（负责人确认）——改字段必须走版本化**受控迁移 / 迁移队列**，经双轨晋升审核落地；声明切换与迁移完成**绑定**（见上节热加载绑定）；**禁止**控制台随意在线 DDL。

### Declaration truth & dual-track / 声明真相与双轨

**Decided / 已拍板：dual-track B** (owner confirmed — not a mere recommendation; **not** YAML-only as the product path).

**已拍板：双轨 B**（负责人确认——不再是「推荐」；**不以**「仅改 YAML」作为产品路径）。

1. Operators edit **tenant-scoped drafts** in the console (**structured page builder / configurator** — visual blocks from the component catalog). Drafts are **isolated per tenant** (not a single platform-global draft bucket).
2. Drafts promote through review → **export YAML into an internal git** (self-hosted / in-platform git mechanism). Promotion **does not depend on GitHub** (or any external hosted git SaaS).
3. Production prefers **promoted** declarations (classpath / released revision from that internal git) for audit and `git diff` reproducibility.

控制台改**租户隔离**草稿 → 审核晋升 → 导出 YAML 到**内部 git**（自建 / 平台内置 git，**不依赖 GitHub**）；生产默认跑已晋升声明，便于评审与回滚。

**Decided / 已拍板：draft scope** (owner confirmed) — declaration drafts are **tenant-scoped**.

**已拍板：草稿作用域**（负责人确认）——声明草稿按**租户**隔离。

**Decided / 已拍板：promote git** (owner confirmed) — dual-track promote uses **internal git** (self-hosted or in-platform). **Not** dependent on GitHub.

**已拍板：晋升 git**（负责人确认）——双轨晋升走**内部 git**（自建或平台内置），**不依赖** GitHub。


**Decided / 已拍板：hot-reload bound to migration** (owner confirmed) — **promoted declaration metadata** may **hot-reload** in running hosts. **Schema / table changes** must go through a **migration queue**; the host **switches** to the new declaration revision **only after** the matching migration has completed. Metadata reload and schema apply are **bound together** (no declare-ahead of unapplied DDL; no DDL without the paired declaration cutover).

**已拍板：热加载与迁移绑定**（负责人确认）——**已晋升声明元数据**可在运行中**热加载**。**改表 / schema** 必须走**迁移队列**；宿主仅在对应迁移**完成后**才切换到新声明修订。元数据热加载与 schema 应用**打勾绑定**（禁止未迁表先切声明；禁止无配对声明切线的裸 DDL）。

**First delivery wave / 首波交付：** Z1–Z2 (generic runtime) **and** Z3 (declaration store / tenant drafts) **and** Z4 (structured configurator) are **in scope**. Z1 and Z3 may **overlap** (generic CRUD can land while draft storage is wired). Z5 (promote into internal git / gated apply) follows closely so drafts do not become a second permanent truth. Promoted YAML (from internal git / released revision) remains the **promoted** baseline and clone-reproducible path — not the day-to-day authoring UX.

首波含 Z1–Z2 **以及** Z3 声明库（租户草稿）与 Z4 结构化配置器；Z1 与 Z3 可重叠推进。Z5 晋升进内部 git 紧随其后，避免草稿变成第二永久真相。已晋升 YAML（内部 git / 发布修订）仍是已晋升基线与可复现路径，不是日常创作面。

Alternatives **rejected** as the product path: YAML-only interim (engineers keep editing files forever); DB-only truth (harder for clone-and-reproduce); promote via **GitHub-dependent** PR as the required path. Docs-only deferral is closed by this decision.

已否决作为产品路径：长期仅 YAML；仅 DB 真相；以依赖 GitHub 的 PR 为必经晋升路径。仅文档暂缓亦因本决策关闭。

### Zero-code non-goals / 零代码明确不做

| Non-goal / 不做 | Why / 原因 |
| --- | --- |
| Free-form arbitrary scripts in pages | Collapses into an IDE; breaks fail-closed catalogs. 页面任意脚本会塌成 IDE。 |
| Unrestricted designer canvas as v1 | Cost and safety; **structured page builder** with common components as blocks first. 首期不做自由画布，先做结构化构建器。 |
| Casual online DDL / ad-hoc alter from console | Schema changes use **strict DB management** + dual-track promote. 改表走严格库管与晋升。 |
| Full algorithm or AI engines in first wave | Thin catalog + stubs only until later. 首波仅薄目录与桩。 |
| Form marketplace | Same as Node 6 / stages 0–6. 与既有非目标一致。 |
| Auto-write from AI without confirm | Non-determinism + audit; confirm (or explicit allow-auto) required. AI 无确认自动写库禁止。 |

### Phased slices (Z) / 分阶段切片（Z）

Do in order unless the owner renumbers. **First wave still Z1–Z4** (dual-track), but **serial priority is R1:** Z1 → thin people/org → Z2 → Z3/Z4 (Z1↔Z3 technical overlap still OK once Z1 lands). Then Z5 promote. **Z6** field kinds still later, but **any** field/schema change (including Z1 entity drafts) must use **strict controlled migrations** — never casual online DDL. **Algorithm/AI:** thin catalog + 1–2 stubs may land **alongside** first wave; full engines stay later. SCIM / complex dual-role stay deferred.

除非负责人改序，否则按序。**首波仍是 Z1–Z4**（双轨），**串行优先级 R1：** Z1 → 薄人员/组织 → Z2 → Z3/Z4。再 Z5。字段/schema **一律受控迁移**（禁随意在线 DDL）。算法/AI 首波仅**薄目录+桩**，完整引擎后置。SCIM / 复杂兼岗后置。

| # | Slice / 切片 | Intent / 意图 | Wave / 波次 |
| --- | --- | --- | --- |
| Z0 | Docs / 文档 | Zero-code model + **dual-track decided** in roadmap + `ARCHITECTURE.md`. | done (docs) |
| Z1 | Generic entity CRUD / 通用实体 CRUD | Metadata-driven table + REST by `entityKey`; **new sample and/or parallel read-adapt `service_note`** (old JDBC may remain then delete) — **new entity → zero Java**. Reserve `userRef`/`orgRef` kinds. Schema/field evolution only via **migration queue** bound to declaration cutover (hot-reload metadata only after migration completes). Prefer HTTP Basic **off** outside `local` (docs with Z1; impl may follow). | **R1 #1 / baseline landed** (Z1-1..3; bespoke write **removed**, generic owns service-note writes) |
| thin org | Thin people/org base / 薄人员组织底座 | `org_unit` tree + membership + **read-only** APIs; wire **permission tiers** (console vs business work); **`platform.super-admin` (own role name)** out of ordinary grants; **no** mandatory second account type; **no** SCIM / complex dual-role yet. | **R1 #2 / baseline landed** (Thin-org-1..2) |
| Z2 | Generic flow pages / 通用流程页 | list/detail/new/edit fully declaration-driven; first-wave blocks: **generic form components**, **ListTable**, **FormFields**, **DetailReadonly**, **Section/Tabs**, **SubmitBar**, **UserPicker/OrgPicker** (thin live), **flow sorter/router** — **new business page → zero bespoke front-end**. | **R1 #3 / baseline landed** (Z2-1..3; detail GET + thin pickers + forms entityKey) |
| Z3 | Declaration store / 声明存储 | `declaration_revision` (or equivalent) + **tenant-scoped** drafts; load order: DB overlay over classpath. May overlap Z1 **after** Z1 baseline lands. | **R1 #4 / baseline landed** (Z3-1..3) |
| Z4 | Console page builder / configurator / 控制台页面构建器 | **Structured builder** (not free canvas): compose pages from **most-used visual component blocks**; wizards also cover entity/form/flow/permission; writes drafts in Z3. | **R1 #4 / first wave** |
| Z5 | Promote / 晋升 | Export YAML into **internal git** (self-hosted / in-platform; **not** GitHub-dependent) / gated apply + audit — closes the dual-track loop. | right after first wave |
| Z6 | Field kinds / 字段种类 | kinds + list filter/sort + wizards + form coerce + page form widgets (`boolean`/`enum`/`date`). | **Z6 baseline landed** |
| thin algo/AI | Algorithm / AI thin layer / 算法与 AI 薄层 | **Catalog + 1–2 stubs** only (bindable from flow/action); not full engines. | **first wave (thin) / landed** |
| later | Full algorithm engines / 完整算法引擎 | Rich deterministic modules beyond stubs. | after first wave |
| later | Full AI catalog / 完整 AI 目录 | Broader gateway-backed capabilities; confirm-before-write. | after first wave |


### Z1 sample strategy & HTTP Basic / Z1 样例策略与 HTTP Basic

**Decided / 已拍板：Z1 generic engine samples** (owner confirmed) — the generic entity engine **first eats a new sample entity and/or parallel read-adapts `service_note`**. **Cutover [done]:** bespoke `JdbcServiceNoteStore` write path **removed**; form writes use `entity.record.upsert` → `GenericEntityStore`. `/notes` list shim kept for console `itemsKey` compatibility.

**已拍板：Z1 通用引擎样例**（负责人确认）——通用实体引擎**先吃新样例实体，和/或并行只读适配 `service_note`**。**旧专用 JDBC** 可暂留，待通用路径覆盖后再删。不以大爆炸切换阻塞 Z1。

**Decided / 已拍板：HTTP Basic safer default** (owner confirmed) — prefer **safer default**: HTTP Basic **off outside `local`** (console already on Bearer). Update **curl examples and docs with Z1**; **implementation may follow later** (explicit opt-in to re-enable Basic for scripts).

**已拍板：HTTP Basic 更安全默认**（负责人确认）——非 `local` 默认**关闭** HTTP Basic（控制台已走 Bearer）。**与 Z1 同期改 curl/文档**；**实现可稍后**（脚本需显式打开兼容）。

### Relation to shipped stages 0–6 / 与已交付 0–6 的关系

Keep `form-render` / `page-declare` / `domainAction` / side-effect catalogs. Replace “hand-promote Flyway + bespoke Store per entity” with **generic runtime** (Z1: demo-ticket + service-note; bespoke service-note write **removed**) and “edit YAML only as authoring” with **tenant-scoped console drafts + promote into internal git** (Z3–Z5, dual-track decided; not GitHub-dependent). Promoted **metadata** may hot-reload; **schema** changes stay on the migration queue bound to declaration cutover.

保留现有声明模块与目录；用通用运行时替换「人工迁 Flyway + 专用 Store」（新样例和/或并行适配 `service_note`，旧 JDBC 可暂留再删）；用**租户隔离控制台草稿 + 内部 git 晋升**（双轨已拍板，Z3–Z5，不依赖 GitHub）替换「日常只会改 YAML」。已晋升**元数据**可热加载；**改表**仍走与声明切换绑定的迁移队列。
