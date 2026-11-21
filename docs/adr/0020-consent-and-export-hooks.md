# ADR 0020 — Consent records and export-approval hooks / 同意记录与导出审批钩子

- Status: **Accepted** — direction only (WP8-0). Implementation starts at WP8-1.
- Date: 2026-11-20 (Asia/Shanghai)
- Scope: Generic consent records (grant / withdraw / query-current / history) and approval hooks for batch or sensitive personal-data export, plus audit and (when approved) a short-lived signed download of the artifact via WP3. Not legal-text content, not medical cross-border judgment, not a second audit or outbox bus, not tenant-level ZIP export (TN-2), not OAuth client-scope consent (ADR 0003 / ID-3).
- Relates: ADR [0004](0004-observability-audit-trace.md) (`AuditPort`), ADR [0014](0014-end-user-identity.md) (end-user realm), ADR [0015](0015-object-storage-authorization.md) (ObjectGrant + signed download), ADR [0016](0016-thin-reusable-workflow.md) (declarable thin workflow), ADR [0003](0003-user-center-oidc.md) (OAuth consent — do not reuse), inventory in [`docs/compliance/wp8-consent-export-progress.md`](../compliance/wp8-consent-export-progress.md). Locked canvas: 「Subjex 底座工作需求」WP8.

## Context / 背景

WP7 is closed (`4ea515a`, acceptance `bf30bdf`). The locked base-work doc’s next package is **WP8 · 同意与导出审批钩子** (P1). Verbatim scope:

- 目标：为后续跨境传输和敏感数据调阅预留平台能力，不在内核写死法律意见。
- 同意记录：主体、目的、文本版本、时间、撤销。
- 字段或对象可标敏感级别；批量导出须经审批钩子并写入审计。
- 不在 Subjex 内核实现具体医疗跨境合规判定，只提供记录与门禁。
- 验收：可查询某主体的同意与撤销历史；未审批的敏感数据导出被拒绝并留痕。

Factual inventory lives in the progress note. In short: there is no purpose-consent port or sensitivity mark today. OAuth `oauth_consent` and TN-2 `POST /api/v1/tenants/{id}/export` are different products and must not be overloaded. Reuse `AuditPort`, WP4 thin workflow, `PolicyEngine` for *permission* checks only, WP3 object grant / signed download, and the existing `outbox_event` bus (no second bus). End-user APIs stay under `/api/v1/end-user/**`.

WP7 已关闭。下一包是同意与导出审批钩子。盘点见进度笔记：今日没有目的同意端口与敏感标记；OAuth 同意与租户 ZIP 导出不可挪用；复用审计、薄工作流、对象授权短时下载与现有出箱。

## Decision / 决策

### D1 — Consent record model and `ConsentPort`

Introduce a new **`ConsentPort`** in `platform-contract` (JDBC implementation in `platform-app`). Do **not** store or extend `oauth_consent`.

A consent record is scoped to **tenant + subject** and holds:

- a **purpose key** supplied by the tenant / domain module (an opaque identifier, not legal prose);
- a **consent-version id** supplied by the tenant (identifies the text the subject saw; the kernel does not store the legal text body);
- **timestamps** (when granted, when withdrawn);
- enough identity to audit (`AuditPort` on grant and withdraw).

Port operations:

- **`grant`** — record that the subject granted the purpose at that version;
- **`withdraw`** — record that the subject withdrew consent for that purpose;
- **`query-current`** — the current (not withdrawn) grant for a tenant + subject + purpose, if any;
- **`history`** — append-only grant and withdraw events, so a subject’s consent and withdrawal history is queryable (canvas acceptance).

History is **append-only**: grant and withdraw write new facts; past rows are not rewritten. Current state is derived from the latest fact for that tenant + subject + purpose. Reserved tenant `platform` is not a home; missing tenant fails closed (ADR 0014).

新增 `ConsentPort`，不扩展 OAuth 同意表。记录按租户+主体，含租户提供的目的键与文本版本标识、时间与审计；提供授予、撤销、查询当前、追加式历史。内核不存放法律正文。

### D2 — Domain modules check consent with a fail-closed hook

Domain modules (and later kernel gates that need a consent check) call a fail-closed hook, e.g. **`ConsentPort.require(tenantId, subjectId, purposeKey, …)`**. If there is no current grant that satisfies the call, the hook refuses; it does not “best-effort allow.” This is the canvas “记录与门禁”: the kernel records and gates; it does not decide whether a purpose is legally sufficient.

域模块经失败关闭的 `ConsentPort.require(...)` 检查当前同意；没有满足条件的当前授予则拒绝。内核只做记录与门禁，不判断某目的在法律上是否充分。

Whether `require` must name an exact consent-version id, or whether a current grant for the purpose is enough after the tenant publishes a newer text, is **open** (see below).

### D3 — Export request lifecycle: approval hook, then artifact, then WP3 download

Personal-data **batch export**, and any export of data marked **sensitive**, must go through an **approval hook** and **audit**. An unapproved sensitive export is **rejected and audited** (canvas acceptance). The kernel does not implement medical cross-border compliance judgment.

Lifecycle (architecture, not a schema in this slice):

1. **Request** — open an export request for a subject in an explicit business tenant (what is in scope is declared by the caller; the kernel does not invent domain datasets).
2. **Approval** — the request is decided through a **declarable thin workflow** (WP4 / ADR 0016): states, transitions, history, optional dual / org-scoped approval, registered as a new machine without special-casing the kernel (same pattern as `order-approval`). `PolicyEngine` is reused only to check *named permissions* of the operator (or other actor) who starts or acts; it is not the approval ledger and not a legal engine.
3. **Generate** — after approval, a **pluggable exporter SPI** produces the artifact. The kernel ships no medical / hospital / visa / insurance exporter. Unconfigured production fails closed.
4. **Download** — the artifact is stored via existing `ObjectStorage`, registered on `ObjectGrantStore` with a required owner, and redeemed through the existing short-lived signed-download issue path (`ObjectDownloadLinkService` / `ObjectDownloadEndpoint`). No second download product.
5. **Audit** — request, approval or denial, artifact issue, download, and rejected unapproved attempts all go through `AuditPort`.

TN-2 `POST /api/v1/tenants/{id}/export` (whole-tenant operator ZIP) and questionnaire `exportCsv` stay as they are and are not this pipeline.

批量或敏感个人数据导出必须走审批钩子并审计；未审批的敏感导出拒绝并留痕。审批复用可声明的薄工作流；策略引擎只做权限检查。获准后由可插拔导出 SPI 生成产物，经 WP3 对象授权与短时签名下载。不挪用 TN-2 租户 ZIP。

**Who may request, and who approves**, is **not locked by the canvas**. Recommended default (changeable later, not canvas-locked):

- An **end user** may request an export of **their own** data in the current business tenant, under `/api/v1/end-user/**`.
- An **operator** with a named permission, in a granted business tenant, may request an export for a subject in that tenant (operator routes, not the end-user prefix).
- **Approvers** are operators selected by the export-approval workflow declaration (org scope / dual approval as that declaration says). The exact permission names, whether an operator may approve their own request, and whether an end-user request can be auto-approved in some non-sensitive case, stay open.

### D4 — Reuse the existing audit and outbox; end-user routes stay under `/api/v1/end-user/**`

- One `AuditPort`. No second audit channel.
- One `outbox_event` bus via `TaskMessagePort`. No second outbox, and no overloading `idempotency_claim.task_id`. This slice does not add an outbox event type; a later notice, if any, must use the existing bus.
- New end-user consent and own-export-request APIs, when implemented, live under `/api/v1/end-user/**` (ADR 0014 realm gate). Operator approval and operator-initiated export stay off that prefix.

复用唯一审计端口与唯一出箱；终端接口落在 `/api/v1/end-user/**`。

### D5 — Out of scope for this package

- Legal-text bodies and legal opinions in the kernel (tenants supply purpose keys and consent-version ids only).
- Medical / hospital / visa / insurance wording, or any concrete medical cross-border compliance judgment, in the kernel.
- Domain-specific exporters in the kernel.
- **Retention or erasure automation** for a data-subject request — the canvas does not require it. TN-2 tenant hard-erase remains a separate operator lifecycle tool.
- Overloading `oauth_consent` or TN-2 tenant ZIP export.
- Rewriting `web/` or starting uni-app consent/export pages in WP8-0 (the canvas does not require a UI in this package; whether later slices add thin APIs only or also pages is open).
- WP9 audit/SIEM export, and WP8-1 implementation, are not started here.

本包不做：内核法律正文与法律意见、医疗跨境判定、域导出器、保留/擦除自动化（画布未要求）、挪用 OAuth 同意或租户 ZIP、本切片改 UI 或启动 WP8-1。

## Consequences / 后果

- WP8-1 can add `ConsentPort` and an append-only store without waiting on export or sensitivity schema details that remain open.
- Export cannot silently skip the hook for sensitive or batch cases: unapproved attempts fail closed and are audited.
- Approvals get workflow history “for free”; legal policy stays out of `PolicyEngine`.
- Exporters remain pluggable, consistent with NotificationPort / PaymentPort.
- Two existing “consent” / “export” surfaces (OAuth, TN-2) stay documented as *not* WP8, reducing accidental reuse.

WP8-1 可先做同意端口；敏感或批量导出不能绕过审批；审批历史走薄工作流；法律判断不进策略引擎。

## Proposed slices / 建议切片

- **WP8-0** — this inventory + ADR (docs only).
- **WP8-1** — `ConsentPort` (grant / withdraw / query-current / history + fail-closed `require`), persistence, `AuditPort`, end-user routes under `/api/v1/end-user/**`. No export pipeline.
- **WP8-2** — sensitivity marks on fields or objects; export request; thin-workflow approval hook; unapproved sensitive / batch export rejected and audited.
- **WP8-3** — pluggable exporter SPI (`SubjectRecordExporter` / built-in `GenericSubjectRecordExporter`; no domain exporters in the kernel); WP3 store + short-lived signed download; audit.
- **WP8-4** — formal acceptance and close the WP8 package.

Do **not** start WP8-1 in this slice.

## Open points / 开放问题

The canvas does not settle the following; this ADR does **not** invent product scope for them. Recommended defaults above may change later.

画布未锁死以下各点，本文不臆造产品范围：

- Who may request an export and who approves it (recommended default in D3 only).
- Whether `ConsentPort.require` must match an exact consent-version id after the tenant publishes a newer text.
- Whether withdraw is per purpose only, or per purpose + version.
- The sensitivity vocabulary, and whether marks live on declaration fields, `object_descriptor`, or both.
- Whether a non-batch, non-sensitive personal-data export also needs the approval hook (the canvas explicitly gates **batch** export and **unapproved sensitive** export).
- Exact operator permission names, self-approval rules, and the export-approval workflow’s `entityKey` / states.
- Whether later slices add console or uni-app pages, or stay API-first.
- Whether an approved export also writes an `outbox_event` notice (not required by the canvas).
- Subject-level erasure / retention automation (out of scope unless a later doc requires it).
