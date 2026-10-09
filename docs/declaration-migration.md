# Declaration versioning and migration / 声明版本与迁移

Stage 5 of [`lowcode-roadmap.md`](lowcode-roadmap.md). Checked-in drafts + human process — not online schema edit.
低代码路线阶段 5。检入草稿 + 人工流程——不是在线改 schema。

## Version on every declaration / 每份声明都有版本

| Kind / 种类 | File / 文件 | Key / 键 | Rule / 规则 |
| --- | --- | --- | --- |
| Form | `forms/*.form.yaml` | `version` | Required integer ≥ 1. Renderer fail-closed if missing/invalid. 必填整数 ≥ 1；缺或非法则渲染拒绝。 |
| Entity | `entities/*.entity.yaml` | `version` | Same. Also names the Flyway-style draft (`V{version}__{table}.sql`). 同上；并命名迁移草稿。 |
| Flow | `flows/*.flow.yaml` | `version` | Same. Catalog JSON exposes `version`. 同上；目录 JSON 暴露 `version`。 |

Bump `version` when fields (or other contract-facing keys) change in a way that old submissions or generated drafts must stay distinguishable.
字段或其它契约面键变更、需要与旧提交/旧草稿区分时，递增 `version`。

Adding or changing required `domainAction` (or its catalog field set) is a contract-facing change — bump the form `version` (samples moved to `version: 2` when `domainAction` became required).
新增或变更必填的 `domainAction`（或其目录字段集）属于契约面变更——递增表单 `version`（样例在 `domainAction` 必填时升到 `version: 2`）。

## Form submissions store the declaration version / 表单提交记下声明版本

`platform-app` Flyway `V5__form_submission_declaration_version.sql` adds `form_submission.declaration_version`.
On accept, `JdbcFormSubmissionStore` writes the current form YAML `version`. History APIs return `declarationVersion` so operators can correlate rows with a git revision of the form.
接受提交时写入当前表单 YAML 的 `version`。历史接口返回 `declarationVersion`，便于对照某次 git 上的表单声明。

Reading an old submission: use the form YAML (or git tag/commit) whose `version` matches `declarationVersion`. Do not reinterpret old `values_json` with a newer field list without an explicit migration note.
解读旧提交：用 `version` 与 `declarationVersion` 一致的那份表单 YAML（或对应 git 修订）。不要在没有迁移说明的情况下，用新字段列表重读旧 `values_json`。

## Entity YAML → migration drafts / 实体 YAML → 迁移草稿

When `tenantScoped: true`, the physical table **must** include `tenant_id VARCHAR(64) NOT NULL` (index/PK as the author chooses). Generic CRUD stamps and filters that column. `EntityMigrationGenerator` **does** auto-add `tenant_id VARCHAR(64) NOT NULL` when the field list omits it (still a single PRIMARY KEY on the entity PK; authors may edit indexes/PK in the draft).
`tenantScoped: true` 时表必须含 `tenant_id VARCHAR(64) NOT NULL`（索引/主键自定）。通用 CRUD 盖章与过滤该列。字段列表未写该列时，`EntityMigrationGenerator` **会**自动追加 `tenant_id VARCHAR(64) NOT NULL`（主键仍为实体单列；草稿中可人工改索引/主键）。


When entity fields change:

1. Bump `version` in the entity YAML.
2. Regenerate the draft under `entity-declare/.../db/migration-draft/` (see stage 1 notes in the roadmap). New file name `V{version}__{table}.sql`. Promote into `platform-app/.../db/migration/` when ready (sample: `V11__service_note.sql`).
3. A human reviews the draft, edits if needed, and only then copies it into `platform-app` `db/migration/` if the table should exist in the shared platform DB.
4. Prefer additive SQL (`ADD COLUMN`). Do **not** auto-drop columns. Rollback = redeploy the prior declaration (and prior applied migration only if you wrote a compensating migration by hand).

实体字段变更时：升 `version` → 重新生成草稿 → 人工审阅后才可能移入 `platform-app` Flyway。优先加列；**不要**自动删列。回滚 = 重新部署上一份声明（补偿迁移仅人工编写）。

Form/flow declarations do not auto-generate DB migrations; only the submission store column above is platform Flyway. Form field shape lives in YAML + `values_json`.
表单/流程声明不自动生成库表迁移；平台 Flyway 只多了上面的提交版本列。字段形状留在 YAML 与 `values_json`。

## Non-goals / 明确不做

- No browser / runtime schema editor.
- No automatic drop of columns or rewrite of historical `values_json`.
- No online bump of `version` without a checked-in YAML change.

不做浏览器/运行时 schema 编辑器；不自动删列或改写历史 JSON；不在未改检入 YAML 的情况下在线抬版本。

## Migration queue (MQ-1) / 迁移队列（MQ-1）

Schema/field changes are **not** casual console DDL. Operators enqueue reviewed SQL into `declaration_migration` (Flyway V16).

字段/schema 变更**不是**控制台随意 DDL。操作员把审阅过的 SQL 入队到 `declaration_migration`（Flyway V16）。

| Status / 状态 | Meaning / 含义 |
| --- | --- |
| `PENDING` | Enqueued, awaiting review / 已入队待审 |
| `REVIEWED` | Human accepted SQL text; not executed yet / 人工接受 SQL，尚未执行 |
| `APPLIED` | Executor ran successfully (MQ-2) / 执行成功（MQ-2） |
| `FAILED` | Executor failed (MQ-2) / 执行失败（MQ-2） |
| `CANCELLED` | Abandoned before apply / 执行前放弃 |

**Permission:** `declaration.migrate` (enqueue + review) on `platform-operator`; list uses `declaration.read`. Chosen **new** permission (not reuse `declaration.promote`) so promote (git metadata) stays separate from schema queue ops.

**权限：** 入队/审阅用新建的 `declaration.migrate`（不复用 `declaration.promote`），晋升（git 元数据）与改表队列分离；列表用 `declaration.read`。

**MQ-1 scope:** persist + HTTP list/enqueue/review. Prefer additive SQL; no auto-drop.

**MQ-1 范围：** 落库 + HTTP 列表/入队/审阅。优先加列；不自动删列。

## Apply + promote bind (MQ-2) / 执行与晋升绑定（MQ-2）

**Apply** `POST /api/v1/declarations/{kind}/{key}/migrations/{id}/apply?tenantId=` (`declaration.migrate`, audit `declaration.migrate.apply`):

- Status must be `REVIEWED` (else 409). Kind must be **`entity`** (form/flow → 400).
- Fail-closed `sqlText`: trim; single statement only (at most one trailing `;`); must start with `ALTER TABLE` or `CREATE TABLE`; reject `DROP` / `TRUNCATE` / `ALTER TABLE … DROP`.
- Execute via `JdbcTemplate` in a transaction → `APPLIED`; on DDL error → `FAILED` + truncated `errorMessage` → HTTP 500.

**Entity promote bind:** before git write / `PROMOTED`, if any migration row exists for the same tenant+kind+key+**revision** that is not `APPLIED` and not `CANCELLED` (i.e. `PENDING` / `REVIEWED` / `FAILED`) → refuse promote **409**. **No** migration rows for that revision → allow (metadata-only). Form/flow promote unchanged.

**Schema-change path:** enqueue → review → **apply** → then promote. Do not promote entity revisions with open or failed migrations.

**执行** 同上路径；仅 entity；失败关闭白名单 DDL；成功 APPLIED，失败 FAILED。**实体晋升**与同修订迁移绑定；无迁移行则可晋升。表单/流程不绑。有 schema 变更时：入队 → 审阅 → **执行** → 再晋升。

## Console UI (MQ-3) / 控制台（MQ-3）

`/declarations` (entity kind only): list migrations, enqueue (revision + `sqlText`) with review→confirm, Review (`PENDING`→`REVIEWED`) and Apply (`REVIEWED`→`APPLIED`/`FAILED`) with review→confirm. Form/flow show a one-line note. Gate: `declaration.migrate` (`canMigrate`). Platform web proxy already forwards POST for `…/migrations` paths.

声明页仅 **entity** 展示迁移队列：列表、入队（审阅→确认）、审阅、执行；表单/流程一行说明。权限 `declaration.migrate`。代理已转发迁移 POST。

**Migration-queue baseline landed** when MQ-1..3 are done: persist + apply bind + console.

**迁移队列基线齐**（MQ-1..3）：落库 + 执行绑定 + 控制台。


## Runtime for promoted tenant-only entities (RT-1) / 无 classpath 已晋升实体的运行时（RT-1）

Classpath samples (`demo-ticket`, `service-note`) keep DRAFT → PROMOTED → classpath overlay on JDBC when `tableName` + PK match.

**New tenant entities with no classpath sample:** `EffectiveDeclarationService.runtimeEntity` serves JDBC/submit **only** from latest **PROMOTED**, and only when migrations for that revision are settled (**APPLIED** or **CANCELLED**) **and** at least one row is **APPLIED** (table exists). Open PENDING/REVIEWED/FAILED → **409**. DRAFT-only → empty (**404**). Form/page detail + submit with `X-Tenant-Id` use effective overlay (DRAFT > PROMOTED > classpath). Forms/pages **index** merges tenant keys when the header is present. Console `/pages/*` sends `subjex_declaration_tenant` as `X-Tenant-Id`.

classpath 样例仍可草稿覆盖。**无 classpath 的租户新实体**：仅 **PROMOTED** 且同修订迁移已结清并至少一条 **APPLIED** 才进 JDBC；未结清 409；仅草稿 404。带租户头时表单/页面详情与提交走生效覆盖；目录合并租户键。控制台页面复用声明租户 cookie 作 `X-Tenant-Id`。


## Auto-enqueue ALTER ADD on entity draft save (RT-4) / 实体草稿保存自动入队加列（RT-4）

When `PUT /api/v1/declarations/entity/{key}` saves a draft, the platform compares the draft to the **baseline** schema and enqueues PENDING migration jobs automatically (operators still review → apply; nothing is auto-APPLIED).

保存实体草稿时，相对**基线** schema 自动入队 PENDING 迁移（仍须审阅→执行；不会自动 APPLIED）。

| Baseline / 基线 | When / 何时 | Enqueued SQL / 入队 SQL |
| --- | --- | --- |
| Latest `PROMOTED` | Tenant has a promoted revision | `ALTER TABLE … ADD COLUMN` per new field |
| Classpath sample | No PROMOTED; key exists on classpath (`demo-ticket`, `service-note`) | Same ALTER ADD for fields not on sample |
| Empty | Brand-new tenant-only entity | One `CREATE TABLE` (includes auto `tenant_id` when `tenantScoped`) |

**Rules / 规则**

- One queue row per statement (CREATE once, or one ALTER ADD per added field) — matches manual enqueue.
- Idempotent: same revision + normalized `sqlText` will not insert another non-`CANCELLED` duplicate.
- Does **not** invent DROP / RENAME / ALTER COLUMN. Field removals are ignored for auto-SQL.
- Fail-closed before draft insert: `tableName` change, primary-key column change, or existing-field kind / SQL type change → `400`.
- Manual console enqueue / review / apply unchanged. Form/flow drafts do not auto-enqueue.

每语句一行队列；同修订同 SQL 幂等；不造 DROP/RENAME；危险变更落库前 400；手工入队仍可用；表单/流程不自动入队。


## Runtime pages after flow promote (RT-5) / 流程晋升后运行时页面（RT-5）

Pages are **derived from flow YAML** at read time — there is **no** separate page declaration kind and **no** write-side page YAML materialization on promote.

页面在读时**从 flow YAML 派生**——**没有**独立 page 声明种类，晋升时也**不**另写 page YAML。

| Step / 步骤 | Behavior / 行为 |
| --- | --- |
| Promote `flow` | {@code DeclarationRuntimePages.ensureAfterFlowPromote}: fail-closed unless `list`/`submit`/`detail` paths are `/pages/{key}`, `/pages/{key}/new`, `/pages/{key}/{id}`; when `tenantScoped: true`, also require blocks ListTable + FormFields + DetailReadonly + SubmitBar across the three pages |
| GET `/api/v1/pages` (+ `X-Tenant-Id`) | Merges classpath with tenant keys; each key resolves via DRAFT &gt; PROMOTED &gt; classpath (`EffectiveDeclarationService.effectiveFlow`) |
| GET `/api/v1/pages/{flowKey}` | Same overlay; unknown tenant-only key without draft/promoted → **404** |
| Console | Dynamic routes `/pages/[flowKey]`, `/new`, `/[id]` already render the four blocks; cookie tenant → `X-Tenant-Id` (RT-1) |

Entity JDBC still requires PROMOTED + APPLIED migrations (RT-1). Form/page **metadata** may show from DRAFT overlay; business-table **data** needs promote + applied schema.

实体 JDBC 仍须 PROMOTED + 迁移 APPLIED。表单/页面**元数据**可走草稿覆盖；业务表**数据**仍须晋升与已执行迁移。


## Pick-only permissions + effect whitelist (RT-3) / 权限只选与副作用白名单（RT-3）

On tenant draft **save** and **promote**, YAML is rendered then constrained:

| Check / 校验 | Rule / 规则 |
| --- | --- |
| `permission` | Must be a name from `OperatorPermission` (e.g. `page.read`); inventing codes → **400** |
| Form `effects` | Only `audit.write` and `task.enqueue`; `extension.invoke` remains classpath-sample only → **400** on tenant draft |

Console business-table / entity / form wizards use a `<select>` over the same catalog (no free text).

保存与晋升时权限须在平台目录；租户草稿表单副作用仅审计与任务入队。控制台权限为下拉选择。


## Versioned audit on form audit.write (RT-6) / 表单审计版本关联（RT-6）

`audit.write` side effects call `OperatorActionAudit.recordFormEffect`, writing `audit_entry` with request **tenant**, **actor**, optional **entity_key**, **declaration_version** (form YAML version), and **resolution_source** (`draft`|`promoted`|`classpath`). Flyway **V19** adds the linkage columns.

表单 `audit.write` 写入租户、操作者、实体键、声明版本与解析来源（V19 列）。


## Console-only business-table path (RT-7) / 控制台业务表路径（RT-7）

Non-dev path: **新建业务表** → entity migration queue (review/apply) → promote entity/form/flow → `/pages/{key}`. Single-kind “create from template” and entity/form structured wizards are **advanced** (collapsed); classpath samples = regression only.

非开发路径：新建业务表 → 迁移队列 → 晋升 → 页面。单种模板与实体/表单向导为高级入口；样例仅回归。

