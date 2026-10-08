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

When entity fields change:

1. Bump `version` in the entity YAML.
2. Regenerate the draft under `entity-declare/.../db/migration-draft/` (see stage 1 notes in the roadmap). New file name `V{version}__{table}.sql`.
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
