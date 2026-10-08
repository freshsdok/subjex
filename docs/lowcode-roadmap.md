# Low-code deepening roadmap / 低代码加深路线

Ordered stages after Node 6 (§15 in `ARCHITECTURE.md`). Architecture summary: `ARCHITECTURE.md` §17.

节点六之后的有序加深。架构摘要见 `ARCHITECTURE.md` 第 17 节。

## Non-goals / 明确不做

| Non-goal / 不做 | Why / 原因 |
| --- | --- |
| Visual / drag-drop designer | Declarations stay in-repo YAML/text; no browser authoring surface. 声明留在仓库文本里，不做浏览器设计器。 |
| Online schema edit | No runtime UI that mutates entity/page/flow schema. 无运行时改 schema 的界面。 |
| Forced init wizard | `subjex-init` is optional; `--skip` / typing `skip` / never running it are all fine. 初始化可跳过。 |
| Bootstrap on every start | Operator bootstrap stays one-shot and off by default (`docs/operator-permissions.md`). 开通仍是一次性、默认关闭。 |
| Form marketplace / per-tenant form store | Same as Node 6. 与节点六一致。 |

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
- First field is the primary key. SQL drafts are **not** applied by `platform-app` Flyway until a human moves them.
- 第一个字段视为主键。SQL 草稿在人工移入 `platform-app` 迁移目录前不会被应用。

### Product decision — entity host wiring / 产品决策：实体接入宿主

**`entity-declare` is not a permanent draft-only module.** Stage 1 delivered checked-in drafts on purpose. Post-stage #4 already depends on the generated types + port from `platform-app` via an in-memory `ServiceNoteStore` and a list API so the sample flow works. Post-stage #3 still owns moving the SQL draft into Flyway and swapping the bean for JDBC.

**`entity-declare` 不是永久只出草稿的模块。** 第 1 阶段故意只检入草稿。阶段后第 4 项已在 `platform-app` 依赖生成类型与端口，用内存 `ServiceNoteStore` 与列表 API 跑通样例流程。阶段后第 3 项仍负责把 SQL 草稿迁入 Flyway，并把 Bean 换成 JDBC。

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
- J. [done] Sample flow entity→page→form: `service-note` flow + form + `entity.serviceNote.save`; in-memory `ServiceNoteStore` + `GET /api/v1/entities/service-note/notes`; console `/pages/service-note`; tests + docs; local commit only (no push). Step 3 (Flyway JDBC) still planned.

## Domain action notes (post-stage #2) / 领域动作说明（阶段后第 2 项）

- Catalog: `form-render` checked-in `actions/domain-action-catalog.yaml` + `DomainActionKey` enum (`registry.register`, `config.override`); each action lists required form field names.
- Form YAML required `domainAction` (fail-closed at render); required catalog fields must exist on the form. Samples bumped to `version: 2`.
- Runtime: `FormDomainActionRunner` switches on the declared key (not `formKey`). Catalog JSON exposes `domainAction` on form index/detail.
- New form reusing an existing key: YAML only. New key: add enum + catalog row + one switch arm.
- 目录检入 YAML + 枚举；表单必填 `domainAction`；执行按声明键而非 formKey；复用已有键只需 YAML。


## Sample flow notes (post-stage #4) / 贯通样例说明（阶段后第 4 项）

- Flow: `page-declare` `flows/service-note.flow.yaml` — `entityKey: service-note`, `formKey: service-note`; list/detail → `GET /api/v1/entities/service-note/notes` (`itemsKey: notes`, `idField: noteId`); submit → form submissions then redirect to list.
- Form: `form-render` `forms/service-note.form.yaml` — fields align with `entities/service-note.entity.yaml`; `domainAction: entity.serviceNote.save`; permission `page.read` (demo; tighten in step 3 if needed).
- Runtime stub (not step 3): `InMemoryServiceNoteStore` implements generated `ServiceNoteStore`; seeded with one demo row; `FormDomainActionRunner` saves on submit. **No Flyway migrate into `platform-app` yet.**
- Console click-through: `/pages` → **Service notes / 服务备注** → list (seeded row) → **New** → fill noteId/title → submit → back on list → open detail.
- Handoff to step 3: move `entity-declare/.../migration-draft/V1__service_note.sql` into `platform-app` Flyway; implement JDBC `ServiceNoteStore` bean; drop or gate the in-memory bean; keep the same API path and JSON shape so the flow YAML stays valid.
- 流程绑实体与表单；列表/详情读实体桩 API；提交经领域动作写入内存桩。步骤 3 换 JDBC+Flyway，路径与 JSON 形状保持。控制台从 `/pages/service-note` 走通列表→新建→详情。

## Post-stage next work / 阶段后下一步

Ordered follow-ups after stages 0–6 (do in this sequence unless the owner renumbers):

阶段 0–6 之后的后续工作（除非负责人改序，否则按此顺序）：

| # | Work / 工作 | Intent / 意图 | Status / 状态 |
| --- | --- | --- | --- |
| 1 | Entity host product lock / 实体接入产品锁定 | Document that `entity-declare` drafts will enter `platform-app` later — not permanent draft-only. | done (this note) |
| 2 | Domain action declarative / 领域动作声明化 | Replace `FormSubmissionEndpoint` `formKey` if-branches with enumerable `domainAction` + catalog so new forms need less Java. | done |
| 3 | Entity → platform-app / 实体接入宿主 | Move `service-note` migration into platform Flyway; replace in-memory store with JDBC; keep `/api/v1/entities/service-note/notes` shape. | planned (next) |
| 4 | Sample flow spanning entity→page→form / 贯通样例 | One flow that ties an entity, page, and form end-to-end. | done |

Owner order for the current pass: **1 → 2 → 4 → 3** (lock → domainAction → flow sample → entity migrate).
当前回合负责人顺序：**1 → 2 → 4 → 3**。
